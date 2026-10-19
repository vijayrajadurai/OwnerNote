package com.shopai.app.brain.chat

import com.shopai.app.brain.KaiFormat
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.KaiMood
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiServices
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.ProductRef
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Daily Sales Note by chat (owner, 14 Oct 2026) — a salon, a tea shop, a beauty parlour, a power tool rental, a hotel:
 *
 *  - "oru hair cutting" → Kai asks its price once ("Hair cutting evlo owner?" → "150"); the draft shows it; Confirm writes the
 *    sale to the day's Daily Cash Note (Money In, its services in the note) and keeps the price in the owner's own memory.
 *  - "cutting plus shaving" next time: the prices are known — only the draft, then Confirm.
 *  - "ithuvaraikum evlo sales": today's total so far, service by service; "Oct 10 evlo sales": that day's, with a PDF on ask.
 *  - "innaikku avlothan" / "day close": the day's total by service — Confirm closes the day (the screen's own Close day).
 *
 * Nothing is written before Confirm, a price is kept only with it, and "Save aagiduchu" is said only after the note has the entry.
 * The note takes no entry before the day's opening Kallapetti amount — Kai asks it once, with the first sale.
 */
class KaiDailySales(
    private val tools: KaiTools,
    private val books: KaiBooks,
    private val memory: KaiMemoryAccess?,
    private val now: () -> LocalDateTime,
) {
    /** A sale being put together: its services with amounts (null = asked), how it was paid, the opening asked with it. */
    private data class Draft(
        val key: String,
        val items: List<KaiServices.Item>,
        val amounts: List<BigDecimal?>,
        val upi: Boolean,
        val newPrices: Map<String, BigDecimal>,
        val opening: BigDecimal?,
        val needsOpening: Boolean,
        val said: String,
        /** The language the sale was said in: a bare "150" answer keeps it. */
        val lang: KaiLang = KaiLang.TANGLISH,
    ) {
        val total: BigDecimal get() = amounts.fold(BigDecimal.ZERO) { a, b -> a + (b ?: BigDecimal.ZERO) }
        val missing: Int get() = amounts.indexOfFirst { it == null }
    }

    private sealed interface Waiting
    /** Kai asked one service's price. */
    private data class PriceAsked(val draft: Draft, val index: Int) : Waiting
    /** Kai asked the day's opening Kallapetti amount. */
    private data class OpeningAsked(val draft: Draft) : Waiting
    /** A sale's draft is on screen, waiting for Confirm. */
    private data class Shown(val draft: Draft) : Waiting
    /** "Day close pannava?" is on screen. */
    private data class CloseAsked(val day: LocalDate) : Waiting
    /** A service's price to keep (changed, or a new service): waiting for Confirm. */
    private data class PriceToKeep(val key: String, val name: String, val price: BigDecimal, val old: BigDecimal?) : Waiting
    /** Menu items said in chat ("menu: cutting 100, shaving 80"): waiting for Confirm. */
    private data class MenuToKeep(val key: String, val items: List<Pair<String, BigDecimal>>) : Waiting
    /** A service said that isn't on the Menu, with items of the owner's that may be it ("Gents Haircut", "Kids Haircut"): which one. */
    private data class ChoiceAsked(val sale: KaiServices.SaleLine, val index: Int, val choices: List<String>, val said: String, val lang: KaiLang) : Waiting

    private var waiting: Waiting? = null

    /** The key of the sale draft shown for Confirm, null when none (Kai's notebook follows it; read only). */
    fun shownKey(): String? = (waiting as? Shown)?.draft?.key
    /** Prices confirmed in this conversation, for a phone with no business memory open. */
    private val sessionPrices = LinkedHashMap<String, BigDecimal>()
    private var lastSaleAt: LocalDateTime? = null

    fun busy() = waiting != null
    fun reset() { waiting = null }

    // ------------------------------------------------------------ prices (the owner's own memory)

    /** The shop's Menu ([KaiMenu]: what the owner added, on the Menu screen or in chat) — name → price. */
    private suspend fun prices(): Map<String, BigDecimal> {
        // (the Menu itself when the business memory is open — an item removed on the Menu screen is gone here too; this
        // conversation's prices only for a phone with no business memory open)
        val open = runCatching { memory?.current() }.getOrNull() ?: return sessionPrices.toMap()
        return KaiMenu.items(open).associate { it.name to it.price }
    }

    private suspend fun keepPrice(name: String, price: BigDecimal) {
        val saved = runCatching { memory?.current()?.let { KaiMenu.save(it, name, price); true } }.getOrNull() == true
        if (!saved) sessionPrices[name] = price
    }

    private fun priceOf(prices: Map<String, BigDecimal>, name: String) = prices.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    // ------------------------------------------------------------ words

    private val yes = Regex("""(?i)^\s*(ama|aama|aamaa|amaa|aam|aamam|yes|yeah|yep|seri|sari|ok|okay|okey|confirm|confirm pannu|save|save pannu|podu|pottudu|add pannu|done|correct|ஆமா|ஆம்|ஆமாம்|எஸ்|சரி|ஓகே|உறுதி|போடு)\s*[.!]*\s*$""")
    private val no = Regex("""(?i)^\s*(venam|vendam|veanam|no|nope|illa|illai|cancel|cancel pannu|vidu|venaam|வேணாம்|வேண்டாம்|இல்ல|இல்லை|கேன்சல்|நோ)\s*[.!]*\s*$""")
    private val cancelWord = Regex("""(?i)(?<![\p{L}])(cancel|venam|vendam|venaam|vidu)(?![\p{L}])|வேணாம்|வேண்டாம்|கேன்சல்""")
    private val upiWord = Regex("""(?i)(?<![\p{L}])(gpay|g\s*pay|google\s*pay|phonepe|phone\s*pe|paytm|upi|online|qr|scan)(?![\p{L}])|ஜிபே|போன்பே|யுபிஐ""")
    private val cashWord = Regex("""(?i)(?<![\p{L}])(cash|kaasu|rokkam)(?![\p{L}])|கேஷ்|ரொக்கம்|காசு""")
    private val salesWord = Regex("""(?i)(?<![\p{L}])(sales|sale|vyabaram|viyabaram|vyaparam|vithadhu|vitthadhu|collection|vasool|varavu|business)(?![\p{L}])|வியாபாரம்|விற்பனை|சேல்ஸ்|கலெக்ஷன்""")
    private val howMuch = Regex("""(?i)(?<![\p{L}])(evlo|evvalavu|evalo|yevlo|enna|how\s*much|total|motham|mothama|sollu|aachu|aagirukku)(?![\p{L}])|எவ்வளவு|மொத்தம்|சொல்லு|ஆச்சு""")
    private val soFar = Regex("""(?i)(?<![\p{L}])(ithuvaraikum|ithuvaraiku|ithu\s*varaikum|ithu\s*varaikkum|idhuvaraikum|idhu\s*varaikum|ippo\s*varaikum|ippa\s*varaikum|ippo\s*varai|now|so\s*far|till\s*now)(?![\p{L}])|இதுவரைக்கும்|இதுவரை|இப்போ வரைக்கும்""")
    private val dayClose = Regex("""(?i)(?<![\p{L}])(day\s*close|close\s*the\s*day|close\s*day|kadai\s*close|shop\s*close|sales\s*close|note\s*close|innaikku\s*close|innaiku\s*close|close\s*pannidu|close\s*pannu|close\s*panniru|mudinjiduchu|mudinjuduchu|mudinjiruchu|mudichitten|mudichiten)(?![\p{L}])|டே\s*க்ளோஸ்|கடை\s*க்ளோஸ்|க்ளோஸ்\s*பண்ணு|முடிஞ்சிடுச்சு|முடிஞ்சுடுச்சு""")
    private val thatsAll = Regex("""(?i)(?<![\p{L}])(avlo\s*dhaan|avlodhaan|avlothan|avlo\s*than|avlo\s*thaan|avlodhan|avvalavu\s*(?:dhaan|thaan|than)|that'?s\s*all)(?![\p{L}])|அவ்வளவுதான்|அவ்வளவு\s*தான்|அவ்ளோதான்|அவ்ளோ\s*தான்""")
    private val todayWord = Regex("""(?i)(?<![\p{L}])(innaikku|innaiku|inniki|inniku|innikku|today|indru|innaikkuu)(?![\p{L}])|இன்னைக்கு|இன்று""")
    private val pdfWord = Regex("""(?i)(?<![\p{L}])(pdf|report|share|anuppu|whatsapp)(?![\p{L}])|பிடிஎஃப்|ரிப்போர்ட்|வாட்ஸ்அப்""")
    private val noteWord = Regex("""(?i)(?<![\p{L}])(sales|sale|daily|note|cash\s*note|sales\s*note|day|innaikku|innaiku|inniki|nethu|vyabaram|kanakku)(?![\p{L}])|சேல்ஸ்|இன்னைக்கு|நேத்து""")
    private val stockAsk = Regex("""(?i)(?<![\p{L}])(stock|iruppu|irupu|irukku|iruku|kaila|kaiyila|left)(?![\p{L}])|ஸ்டாக்|இருப்பு|இருக்கு|கையில""")
    private val rateWord = Regex("""(?i)(?<![\p{L}])(rate|price|vilai|vilaiya|vela|charge|charges|amount)(?![\p{L}])|ரேட்|விலை""")
    private val changeWord = Regex("""(?i)(?<![\p{L}])(maathu|mathu|maathidu|change|ippo|ini|inimel|update|vachuko|vechuko|set)(?![\p{L}])|மாத்து|இனி|இப்போ""")
    /** "menu", "menu kaattu", "en menu enna", "மெனு காட்டு": the Menu itself. */
    private val menuAsk = Regex("""(?i)(?:en\s+|unga\s+|my\s+)?(?:menu|மெனு)\s*(?:list|kaattu|kaatu|sollu|enna|paaru|paakanum|show|காட்டு|சொல்லு|என்ன)?\s*[?.!]*""")
    private val rateList = Regex("""(?i)(?<![\p{L}])(rate|price|vilai|service|services)\s*(list|lists|ellam|ellaam|motham)(?![\p{L}])|(?<![\p{L}])(enna\s*enna|ella)\s*(service|services|rate|vilai)(?![\p{L}])|ரேட் லிஸ்ட்|விலை பட்டியல்""")

    private fun amountsIn(text: String): List<BigDecimal> {
        val t = com.shopai.app.brain.tools.KaiSpokenWords.normalize(text)
        val digits = Regex("""(?<![\d.])(\d[\d,]*(?:\.\d+)?)(?![\d])""").findAll(t).mapNotNull { it.groupValues[1].replace(",", "").toBigDecimalOrNull() }.toList()
        if (digits.isNotEmpty()) return digits
        return com.shopai.app.brain.KaiUnderstanding.amountsIn(t, now().toLocalDate()).map { BigDecimal.valueOf(it) }
    }

    private fun rupees(a: BigDecimal) = KaiFormat.rupees(a.toDouble())

    /** "hair cutting add", "2 tea": a few shop words read as English — an owner's Tanglish, unless said as an English sentence. */
    private fun ownersLang(text: String, lang: KaiLang): KaiLang =
        if (lang == KaiLang.ENGLISH && text.trim().split(Regex("""\s+""")).size <= 6 &&
            !Regex("""(?i)(?<![\p{L}])(is|are|the|a|an|i|my|me|please|how|what|was|did|do|does|for|of|to|sold|today|so\s+far)(?![\p{L}])""").containsMatchIn(text)) KaiLang.TANGLISH
        else lang
    private fun pick(lang: KaiLang, ta: String, tl: String, en: String) = when (lang) { KaiLang.TAMIL -> ta; KaiLang.TANGLISH -> tl; KaiLang.ENGLISH -> en }
    private fun turn(lang: KaiLang, mood: KaiMood, ta: String, tl: String, en: String, card: KaiCard? = null) =
        KaiTurn(ChatReply(pick(lang, ta, tl, en), mood, ChatIntent.GENERAL_BUSINESS_QUERY), card)

    // ------------------------------------------------------------ an answer to what Kai asked

    /** The owner's answer to a question Kai is waiting on (a price, the opening, Confirm on a draft, the day close) — null: not one. */
    suspend fun answer(said: String, raw: String, lang0: KaiLang): KaiTurn? {
        val w = waiting ?: return null
        val text = said.trim()
        // A bare number ("150", "0") reads as English: the answer keeps the language the sale was said in.
        val asked = when (w) { is PriceAsked -> w.draft.lang; is OpeningAsked -> w.draft.lang; is Shown -> w.draft.lang; else -> null }
        val lang = if (asked != null && lang0 == KaiLang.ENGLISH && (text.none { it.isLetter() } || yes.matches(text) || no.matches(text))) asked else lang0
        return when (w) {
            is PriceAsked -> {
                if (no.matches(text) || cancelWord.containsMatchIn(text) && amountsIn(text).isEmpty()) { waiting = null; return dropped(lang) }
                // "150", "நூத்தி ஐம்பது", or "cutting 150 shaving 80" for several at once.
                val again = KaiServices.saleIn(text, prices(), emptyList(), true) ?: KaiServices.saleIn(raw, prices(), emptyList(), true)
                var d = w.draft
                if (again != null && again.items.any { it.amount != null }) {
                    val amounts = d.amounts.toMutableList(); val learned = d.newPrices.toMutableMap()
                    for (i in again.items.filter { it.amount != null }) {
                        val at = d.items.indexOfFirst { it.name.equals(i.name, ignoreCase = true) }.takeIf { it >= 0 } ?: continue
                        if (amounts[at] == null) {
                            val unit = i.amount!!.divide(BigDecimal(i.qty), 2, RoundingMode.HALF_UP).stripTrailingZeros()
                            amounts[at] = unit.multiply(BigDecimal(d.items[at].qty)); learned[d.items[at].name] = unit
                        }
                    }
                    d = d.copy(amounts = amounts, newPrices = learned)
                } else {
                    val a = amountsIn(text).singleOrNull()?.takeIf { it.signum() > 0 } ?: run {
                        // Not a price: asked once more plainly, the sale kept (never guessed).
                        if (text.split(Regex("""\s+""")).size <= 3) return askPrice(w.draft, w.index, lang, again = true)
                        waiting = null; return null
                    }
                    val item = d.items[w.index]
                    val amounts = d.amounts.toMutableList(); amounts[w.index] = a.multiply(BigDecimal(item.qty))
                    d = d.copy(amounts = amounts, newPrices = d.newPrices + (item.name to a))
                }
                next(d, lang)
            }
            is OpeningAsked -> {
                if (no.matches(text) || cancelWord.containsMatchIn(text) && amountsIn(text).isEmpty()) { waiting = null; return dropped(lang) }
                val a = amountsIn(text).singleOrNull()?.takeIf { it.signum() >= 0 } ?: run {
                    if (text.split(Regex("""\s+""")).size <= 3) return askOpening(w.draft, lang, again = true)
                    waiting = null; return null
                }
                next(w.draft.copy(opening = a), lang)
            }
            is Shown -> {
                val d = w.draft
                when {
                    yes.matches(text) -> confirmSale(d.key, lang)
                    no.matches(text) || cancelWord.containsMatchIn(text) && text.split(Regex("""\s+""")).size <= 3 -> cancelSale(d.key, lang)
                    upiWord.containsMatchIn(text) && text.split(Regex("""\s+""")).size <= 4 && KaiServices.saleIn(text, prices(), emptyList(), true) == null ->
                        draftTurn(d.copy(upi = true), lang)
                    cashWord.containsMatchIn(text) && text.split(Regex("""\s+""")).size <= 4 && KaiServices.saleIn(text, prices(), emptyList(), true) == null ->
                        draftTurn(d.copy(upi = false), lang)
                    // "illa 200 dhaan" to a one-service draft: that amount (and the price kept, when it was being learned now).
                    d.items.size == 1 && Regex("""(?i)(illa|illai|no|இல்ல)""").containsMatchIn(text) && amountsIn(text).size == 1 -> {
                        val a = amountsIn(text).single()
                        val item = d.items.single()
                        val learned = if (d.newPrices.containsKey(item.name)) d.newPrices + (item.name to a.divide(BigDecimal(item.qty), 2, RoundingMode.HALF_UP).stripTrailingZeros()) else d.newPrices
                        draftTurn(d.copy(amounts = listOf(a), newPrices = learned), lang)
                    }
                    else -> null
                }
            }
            is CloseAsked -> when {
                yes.matches(text) -> closeDay(w.day, lang)
                no.matches(text) || Regex("""(?i)(innum|illa\s*innum|not\s*yet|இன்னும்)""").containsMatchIn(text) && text.split(Regex("""\s+""")).size <= 3 -> {
                    waiting = null
                    turn(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், day close பண்ணல. இன்னும் sale இருந்தா சொல்லுங்க.",
                        tl = "Seri Owner, day close pannala. Innum sale irundha sollunga.", en = "Okay Owner, the day stays open. Tell me the next sale.")
                }
                else -> { waiting = null; null }
            }
            is ChoiceAsked -> {
                val l = if (lang0 == KaiLang.ENGLISH && w.lang != KaiLang.ENGLISH && text.split(Regex("""\s+""")).size <= 3) w.lang else lang
                if (no.matches(text) || cancelWord.containsMatchIn(text) && text.split(Regex("""\s+""")).size <= 3) { waiting = null; return dropped(l) }
                val chosen = choiceIn(plainChoice(text), plainChoice(raw), w.choices) ?: w.choices.singleOrNull()?.takeIf { yes.matches(text) }
                    ?: run { waiting = null; return null }
                waiting = null
                val items = w.sale.items.toMutableList().also { it[w.index] = it[w.index].copy(name = chosen) }
                startSale(w.sale.copy(items = items), w.said, prices(), w.lang)
            }
            is MenuToKeep -> when {
                yes.matches(text) -> confirmPrice(w.key, lang)
                no.matches(text) -> { waiting = null; turn(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், மெனுவுல எதுவும் சேர்க்கல.", tl = "Seri Owner, menu-la edhuvum serkala.", en = "Okay Owner, nothing was added to the Menu.") }
                else -> { waiting = null; null }
            }
            is PriceToKeep -> when {
                yes.matches(text) -> confirmPrice(w.key, lang)
                no.matches(text) -> { waiting = null; turn(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், விலை மாத்தல.", tl = "Seri Owner, vilai maathala.", en = "Okay Owner, the price stays as it was.") }
                else -> { waiting = null; null }
            }
        }
    }

    // ------------------------------------------------------------ a line of the owner's

    /**
     * A sale ("oru cutting", "cutting plus shaving gpay"), a price ("cutting evlo?", "cutting rate 200", "pudhu service hair
     * botox 1500"), the rate list, a sales question ("ithuvaraikum evlo sales", "nethu sales evlo"), the day close, or the
     * day's PDF — null when the line is none of these. [busy]: another draft / list is open (its "avlo dhaan" is its own).
     */
    suspend fun line(said: String, raw: String, lang0: KaiLang, products: List<ProductRef>, people: List<String>, busy: Boolean): KaiTurn? {
        val text = said.trim()
        val lang = ownersLang(text, lang0)
        val today = now().toLocalDate()
        val prices = prices()
        val names = products.map { it.name }
        // The shop's category (the owner's pick at setup): only its kind of services — a salon's cutting, a restaurant's idli.
        val category = runCatching { tools.businessCategory() }.getOrNull()
        val kinds = KaiServices.kindsFor(category)
        // As the mic writes the name of a service the owner added ("ஹேர் போட்டாக்ஸ்" = their Hair botox): that name, by its sound.
        // The mic's Tamil letters for the owner's own Menu names, by sound, whatever language they typed them in ("ஃபேஸ் மசாஜ்" =
        // their "Face massage", "தலை முடி வெட்டு" = their "Thalai mudi vettu") — their Menu before Kai's own words (owner, 16 Oct 2026).
        // A part of the owner's Menu name, their spelling or not, typed or by mic — "cutting cambo", "hair cutting combo", the mic's
        // "கட்டிங் காம்போ" / "ஹேர் கட்டிங் காம்பவுண்ட்" / "ஹால் சொன்ன" — is that item, before Kai's own words (owner's phone, 17 Oct 2026).
        val sounded = menuByPart(ownBySound(menuBySound(raw, prices.keys, people), prices.keys), prices.keys)
            .let { if (it == raw) menuByPart(text, prices.keys).takeIf { t -> t != text } ?: raw else it }
        val heardMenu = sounded != raw
        val namesPerson = people.any { p -> p.isNotBlank() && Regex("""(?i)(?<![\p{L}])${Regex.escape(p)}(?![\p{L}])""").containsMatchIn(text) }

        // The Menu said in chat: "menu: cutting 100, shaving 80, facial 110" — kept only on Confirm.
        KaiMenu.parse(text, com.shopai.app.brain.tools.KaiSpokenWords.normalize(raw))?.let { return menuToKeep(it, prices, lang) }
        // The rate list / the Menu: the prices the owner gave.
        if ((rateList.containsMatchIn(text) || menuAsk.matches(text.trim())) && !namesPerson) return rateListTurn(prices, lang)

        // The day the owner asks about — "Monday", "munnaal", "anjaam thedhi", "Oct 5", "nethu" — or a span ("indha maasam",
        // "pona vaaram", "kadandha 7 naal"): only days already gone (owner, 19 Oct 2026: "yentha date la evlo sales nu ketalum").
        val dayAsked = ownDay(text, raw, today) ?: dayOf(text, people, today)
        val spanAsked = if (dayAsked == null) spanOf(text, people, today) else null

        // The PDF: "innaikku sales pdf kudu", "nethu report", "Oct 10 daily note pdf", "5th report PDF venum", "indha maasam sales PDF".
        if (pdfWord.containsMatchIn(text) && !namesPerson && Regex("""(?i)pdf|report|ரிப்போர்ட்|பிடிஎஃப்""").containsMatchIn(text) &&
            (noteWord.containsMatchIn(text) || (dayAsked != null || spanAsked != null) && !otherReport.containsMatchIn(text))) {
            spanAsked?.let { (from, to, kind) -> return rangePdfTurn(from, to, kind, lang) }
            return pdfTurn(dayAsked ?: today, lang)
        }

        // The day close: "innaikku avlothan", "day close pannu", "கடை க்ளோஸ்".
        if (!busy && !namesPerson && (dayClose.containsMatchIn(text) || thatsAll.containsMatchIn(text) && (todayWord.containsMatchIn(text) || recentSale())))
            closeAsk(today, lang)?.let { return it }

        // Sales so far / on a day / in a span: "ithuvaraikum evlo sales", "innaikku sales evlo", "nethu evlo vyabaram", "Oct 10 sales",
        // "Monday sales evlo", "indha maasam sales", "kadandha 7 naal sales"; and "indha maasam endha service adhigama pochu".
        val dated = dayAsked != null || spanAsked != null
        val topAsk = topWord.containsMatchIn(text) && serviceWord.containsMatchIn(text)
        val salesAsk = salesWord.containsMatchIn(text) && (howMuch.containsMatchIn(text) ||
            dated && text.trim().split(Regex("""\s+""")).size <= 6 && KaiServices.saleIn(text, prices, names, true, kinds) == null && !otherReport.containsMatchIn(text))
        if (!namesPerson && (soFar.containsMatchIn(text) && howMuch.containsMatchIn(text) || salesAsk || topAsk)) {
            if (spanAsked != null) rangeTurn(spanAsked.first, spanAsked.second, spanAsked.third, lang, topAsk)?.let { return it }
            else if (topAsk && dayAsked == null && !soFar.containsMatchIn(text) && !todayWord.containsMatchIn(text))
                rangeTurn(today.withDayOfMonth(1), today, ChatPeriod.Kind.THIS_MONTH, lang, true)?.let { return it }
            else {
                val span = KaiChatUnderstanding.understand(text, today, people).period
                val own = ownDay(text, raw, today) != null
                salesTurn(dayAsked ?: today, lang, forceAnswer = soFar.containsMatchIn(text) || own || dayAsked != null && span?.kind == ChatPeriod.Kind.TODAY)?.let { return it }
            }
        }

        // A price for one service: "cutting evlo", "cutting rate 200", "pudhu service hair botox 1500".
        (KaiServices.newServiceName(text) ?: KaiServices.newServiceName(sounded))?.takeIf { KaiServices.serviceNamed(text, prices.keys) == null || Regex("""(?i)pudhu|puthu|new|புது""").containsMatchIn(text) }?.let { name ->
            amountsIn(text).lastOrNull()?.takeIf { it.signum() > 0 }?.let { return priceToKeep(name, it, priceOf(prices, name), lang) }
        }
        // (never the shop's own product — "Horlicks stock evlo" is its stock — nor a stock question: only a service's price)
        val one = ((if (heardMenu) KaiServices.serviceNamed(sounded, prices.keys, kinds) else null) ?: KaiServices.serviceNamed(text, prices.keys, kinds)
            ?: KaiServices.serviceNamed(raw, prices.keys, kinds) ?: KaiServices.serviceNamed(sounded, prices.keys, kinds))
            ?.takeIf { n -> priceOf(prices, n) != null || names.none { p -> Regex("""(?i)(?<![\p{L}])${Regex.escape(n)}(?![\p{L}])""").containsMatchIn(p) } }
            ?.takeIf { !stockAsk.containsMatchIn(text) }
        if (one != null && rateWord.containsMatchIn(text) && !namesPerson) {
            val amount = amountsIn(text).lastOrNull()?.takeIf { it.signum() > 0 }
            if (amount != null && (changeWord.containsMatchIn(text) || Regex("""(?i)(rate|price|vilai)\s*(?:₹|rs\.?\s*)?\d""").containsMatchIn(text)))
                return priceToKeep(one, amount, priceOf(prices, one), lang)
            if (amount == null && howMuch.containsMatchIn(text)) return priceTurn(one, priceOf(prices, one), lang)
        }
        if (one != null && amountsIn(text).isEmpty() && Regex("""(?i)^\s*\S+(\s+\S+)?\s+(evlo|evvalavu|rate\s+enna|vilai\s+enna|price\s+enna|எவ்வளவு)\s*\??\s*$""").matches(text) && !namesPerson)
            return priceTurn(one, priceOf(prices, one), lang)

        // A sale — never a line about what Kai doesn't do ("biryani recipe sollu").
        if (namesPerson || KaiConversationCare.beyondBooks(text) != null) return null
        val serviceShop = KaiServices.serviceCategory(category) || runCatching { tools.sellsServices() }.getOrNull() == true
        // (the mic's own words only when Kai didn't already read them as the shop's product — "ஃப்ரோசன் பரோட்டா" is Frozen paratha)
        val lower = " " + text.lowercase(Locale.ROOT) + " "
        val productSaid = names.any { n -> n.lowercase(Locale.ROOT).split(Regex("""\s+""")).take(2).joinToString(" ").let { head -> head.length >= 3 && lower.contains(" $head ") } }
        // The salon master list's names as the mic wrote them ("கோல்ட் ஃபேஷியல்" = gold facial), by sound — a beauty shop's only.
        val heardServices = if (productSaid) text else KaiServices.withServiceSounds(text, kinds, people)
        val sale = (if (heardMenu && !productSaid) KaiServices.saleIn(sounded, prices, names, serviceShop, kinds)?.takeIf { l -> l.items.any { i -> priceOf(prices, i.name) != null } } else null)
            ?: (if (heardServices != text) KaiServices.saleIn(heardServices, prices, names, serviceShop, kinds) else null)
            ?: KaiServices.saleIn(text, prices, names, serviceShop, kinds)
            ?: (if (productSaid) null else KaiServices.saleIn(raw, prices, names, serviceShop, kinds) ?: KaiServices.saleIn(sounded, prices, names, serviceShop, kinds))
            ?: return otherShopsService(text, raw, sounded, prices, names, serviceShop, kinds, productSaid, lang)
        return startSale(sale, said, prices, lang)
    }

    /**
     * "hair cutting onnu" in a grocery (owner's phone, 15 Oct 2026): a service of another kind of shop, said plainly — it is not
     * this shop's, nothing is written, and how to add it if the owner does sell it. Never a guess at another topic ("idhu Kumar
     * ₹2,000 payment pathiyaa?"). Only a word that is plainly a service (not "tea", a grocery's goods too). Null otherwise.
     */
    private fun otherShopsService(text: String, raw: String, sounded: String, prices: Map<String, BigDecimal>, names: List<String>,
        serviceShop: Boolean, kinds: Set<KaiServices.Kind>?, productSaid: Boolean, lang: KaiLang): KaiTurn? {
        if (kinds == null) return null
        val any = KaiServices.saleIn(text, prices, names, serviceShop, null)
            ?: (if (productSaid) null else KaiServices.saleIn(raw, prices, names, serviceShop, null) ?: KaiServices.saleIn(sounded, prices, names, serviceShop, null))
            ?: return null
        val services = any.items.map { i -> KaiServices.catalogue.firstOrNull { it.name == i.name } ?: return null }
        if (services.any { !it.clear || it.kind in kinds }) return null
        val n = services.map { it.name }.distinct().joinToString(", ")
        val first = services.first().name
        waiting = null
        // An item of the owner's Menu with that name in it ("Hair cutting" said, "Hair cutting cambo" kept): named, never sold by itself.
        val near = prices.entries.firstOrNull { (o, _) -> first.split(' ').all { w -> o.split(Regex("""\s+""")).any { sameSound(w, it) } } && !o.equals(first, ignoreCase = true) }
        if (near != null) return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், $first உங்க மெனுவுல இல்ல. மெனுவுல “${near.key}” (${rupees(near.value)}) இருக்கு — அதுன்னா “${near.key} ஒன்னு”-ன்னு சொல்லுங்க. எதுவும் போடல.",
            tl = "Owner, $first unga menu-la illa. Menu-la “${near.key}” (${rupees(near.value)}) irukku — adhu-na “${near.key} onnu”-nu sollunga. Edhuvum podala.",
            en = "Owner, $first isn't on your Menu. “${near.key}” (${rupees(near.value)}) is — if it's that, say “${near.key} onnu”. Nothing was added.")
        return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், $n உங்க மெனுவுல இல்ல — Daily Sales Note-ல எதுவும் போடல. நீங்க $n பண்றீங்கன்னா “மெனு: $first 150” மாதிரி விலையோட சொல்லுங்க, இல்ல Daily Sales Note → Menu-ல சேருங்க.",
            tl = "Owner, $n unga menu-la illa — Daily Sales Note-la edhuvum podala. Neenga $n pannureenga-na “menu: $first 150” maadhiri vilaiyoda sollunga, illa Daily Sales Note → Menu-la serunga.",
            en = "Owner, $n isn't on your Menu — nothing went into the Daily Sales Note. If you do offer it, say “menu: $first 150” with its price, or add it in Daily Sales Note → Menu.")
    }

    /** [raw] with the owner's Menu names (English / Tanglish letters) put in where the mic wrote them in Tamil, by sound — as a whole phrase. */
    private fun menuBySound(raw: String, own: Collection<String>, people: List<String>): String {
        val latin = own.filter { n -> !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(n) && n.any(Char::isLetter) }
        if (latin.isEmpty() || !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(raw)) return raw
        return runCatching { com.shopai.app.brain.tools.KaiProductSound.withProducts(raw, latin, people) }.getOrDefault(raw)
    }

    /** Two words that sound the same: across scripts by sound ("காம்போ" / "cambo"), in English letters by their consonants ("combo" / "cambo"). */
    private fun sameSound(said: String, word: String): Boolean {
        val a = said.trim('.', ',', '!', '?')
        if (a.equals(word, ignoreCase = true)) return true
        val tamil = com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(a)
        if (tamil != com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(word)) return com.shopai.app.util.NameSound.same(a, word) ||
            tamil && com.shopai.app.brain.tools.KaiProductSound.sameWord(a, word)
        val k = com.shopai.app.util.NameSound.key(a)
        // ("combo" / "cambo": three consonants the same; two only with the same vowels — "cut" is no "cot")
        return k == com.shopai.app.util.NameSound.key(word) && (k.length >= 3 || k.length == 2 && com.shopai.app.util.NameSound.vowels(a) == com.shopai.app.util.NameSound.vowels(word))
    }

    /** The mic's slip on a name's last word: "காம்பவுண்ட்" (compound) for "cambo" — it starts with all of the name's sound (three consonants or more). */
    private fun startsAsSound(said: String, word: String): Boolean {
        val k = com.shopai.app.util.NameSound.key(word)
        return k.length >= 3 && com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(said) != com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(word) &&
            com.shopai.app.util.NameSound.key(said).startsWith(k)
    }

    private val sOnnu = Regex("""^(?:ச|ஸ)(?:ொ|ோ)(?:ன்ன|ண்ண|ன்னு|ண்ணு)\p{M}*$""")

    /**
     * [line] with the owner's whole Menu name put in where they said two words or more of it in a row (or a one-word name),
     * each word as typed or by its sound — "cutting cambo onnu" → "Hair cutting cambo onnu", "ஹேர் கட்டிங் காம்பவுண்ட்" → "Hair
     * cutting cambo". Only one item may fit; a name the line already says whole is left to the usual reading. The mic's "ஹால்
     * சொன்ன" ("Halls onnu" with its s on the next word) is that item and "onnu".
     */
    private fun menuByPart(line: String, own: Collection<String>): String {
        val names = own.filter { n -> !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(n) && n.any(Char::isLetter) }
        if (names.isEmpty()) return line
        val words = line.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (names.any { n -> " ${line.lowercase(Locale.ROOT)} ".contains(" ${n.lowercase(Locale.ROOT)} ") }) return line
        data class Fit(val name: String, val from: Int, val to: Int, val size: Int)
        val fits = mutableListOf<Fit>()
        for (n in names) {
            val nw = n.trim().split(Regex("""\s+"""))
            for (i in words.indices) for (j in nw.indices) {
                var k = 0
                while (i + k < words.size && j + k < nw.size && sameSound(words[i + k], nw[j + k])) k++
                // (the mic's slip on the name's last word, after the words before it matched)
                if (k >= 1 && i + k < words.size && j + k == nw.size - 1 && startsAsSound(words[i + k], nw[j + k])) k++
                // (up to the name's last word — "cutting cambo" is the Hair cutting cambo; "hair cutting" alone is not: Kai asks)
                if ((k >= 2 || k == 1 && nw.size == 1) && j + k == nw.size) fits += Fit(n, i, i + k, k)
            }
        }
        // "ஹால் சொன்ன": "Halls" with its s said on "onnu".
        if (fits.isEmpty()) for (i in 0 until words.size - 1) if (sOnnu.matches(words[i + 1])) {
            val joined = words[i] + "ஸ்"
            names.filter { n -> n.trim().split(Regex("""\s+""")).size == 1 && com.shopai.app.util.NameSound.same(joined, n) }.singleOrNull()?.let { n ->
                return (words.take(i) + n + "onnu" + words.drop(i + 2)).joinToString(" ")
            }
        }
        val best = fits.maxOfOrNull { it.size } ?: return line
        val top = fits.filter { it.size == best }
        val fit = top.distinctBy { it.name }.singleOrNull() ?: return line
        return (words.take(fit.from) + fit.name + words.drop(fit.to)).joinToString(" ")
    }

    /** [raw] with the owner's own service names put in where the mic wrote them in Tamil letters (only theirs — Kai's words are listed). */
    private fun ownBySound(raw: String, own: Collection<String>): String {
        val names = own.filter { n -> !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(n) && KaiServices.catalogue.none { it.name.equals(n, ignoreCase = true) } }
        if (names.isEmpty() || !com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(raw)) return raw
        val words = raw.trim().split(Regex("""\s+"""))
        for (n in names) {
            val size = n.trim().split(Regex("""\s+""")).size
            for (i in 0..words.size - size) {
                val span = words.subList(i, i + size).joinToString("")
                if (com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(span) && com.shopai.app.util.NameSound.same(span, n.replace(" ", "")))
                    return (words.take(i) + n + words.drop(i + size)).joinToString(" ")
            }
        }
        return raw
    }

    private fun recentSale() = lastSaleAt?.let { java.time.Duration.between(it, now()).toHours() < 18 } == true

    /** The single day a line is about ("innaikku", "nethu", "Oct 10", "10th") — a day already gone, never one to come. */
    private fun dayOf(text: String, people: List<String>, today: LocalDate): LocalDate? {
        val p = KaiChatUnderstanding.understand(text, today, people).period ?: return null
        if (p.from != p.to) return null
        var d = p.from
        if (d.isAfter(today)) {
            d = if (p.kind == ChatPeriod.Kind.DATE && d.minusMonths(1).dayOfMonth == d.dayOfMonth && !d.minusMonths(1).isAfter(today)) d.minusMonths(1)
                else if (!d.minusYears(1).isAfter(today)) d.minusYears(1) else return null
        }
        return d
    }

    private val otherReport = Regex("""(?i)(?<![\p{L}])(stock|statement|kadan|credit|debit|baaki|baki|balance|purchase|selavu|expense|expenses|pending|customer|supplier|gst|bill)(?![\p{L}])|ஸ்டாக்|ஸ்டேட்மென்ட்|கடன்|பாக்கி""")
    private val topWord = Regex("""(?i)(?<![\p{L}])(adhigama|athigama|adhigam|athigam|top|best|most|niraya|neraya)(?![\p{L}])|அதிகமா|அதிகம்|நிறைய""")
    private val serviceWord = Regex("""(?i)(?<![\p{L}])(service|services|servis|item|items)(?![\p{L}])|சர்வீஸ்|ஐட்டம்""")
    private val beforeYesterday = Regex("""(?i)(?<![\p{L}])(munnaal|munnal|munthanaal|munthaanaal|mundhanaal|mundhaanaal|munthaa\s*naal|mundhaa\s*naal|munna\s*naal|nethukku\s*munnaal|nethu\s*munnaal|nethuku\s*munnadi|nethukku\s*munnadi|day\s+before\s+yesterday)(?![\p{L}])|முந்தா\s*நாள்|முந்தாநாள்|முன்னாள்|நேத்துக்கு\s*முன்னாடி""")
    private val lastWord = Regex("""(?i)(?<![\p{L}])(pona|poona|last|previous|kadandha|kadantha)(?![\p{L}])|போன|கடந்த""")
    private val weekdays: List<Pair<java.time.DayOfWeek, Regex>> = listOf(
        java.time.DayOfWeek.MONDAY to """monday|thingal\p{L}*|thingak\p{L}*|thingat\p{L}*|திங்க\p{L}*|மண்டே""",
        java.time.DayOfWeek.TUESDAY to """tuesday|sevvai\p{L}*|chevvai\p{L}*|செவ்வா\p{L}*|ட்யூஸ்டே|டியூஸ்டே""",
        java.time.DayOfWeek.WEDNESDAY to """wednesday|budhan\p{L}*|bhudhan\p{L}*|puthan\p{L}*|budhankizhamai|புதன்\p{L}*|வெட்னஸ்டே|வென்ஸ்டே""",
        java.time.DayOfWeek.THURSDAY to """thursday|vyazhan\p{L}*|viyazhan\p{L}*|vyaazhan\p{L}*|வியாழ\p{L}*|தர்ஸ்டே""",
        java.time.DayOfWeek.FRIDAY to """friday|vellikizhamai|velli\s*kizhamai|வெள்ளிக்கிழமை|ஃப்ரைடே|ப்ரைடே|பிரைடே""",
        java.time.DayOfWeek.SATURDAY to """saturday|sani|sanikizhamai|sani\s*kizhamai|சனிக்கிழமை|சாட்டர்டே|சேட்டர்டே""",
        java.time.DayOfWeek.SUNDAY to """sunday|nyayiru|nyaayiru|gnayiru|nyayitru\p{L}*|gnayitru\p{L}*|ஞாயிறு|ஞாயிற்று\p{L}*|சண்டே""",
    ).map { (d, alt) -> d to Regex("""(?i)(?<![\p{L}\p{M}])(?:$alt)(?![\p{L}\p{M}])""") }
    /** "anjaam thedhi", "5aam thethi", "5 தேதி", "அஞ்சாம் தேதி": a day of the month said the Tamil way. */
    private val ordinalWords = mapOf("onnaam" to 1, "onaam" to 1, "rendaam" to 2, "irandaam" to 2, "moonaam" to 3, "moondraam" to 3, "naalaam" to 4,
        "anjaam" to 5, "aindhaam" to 5, "aaraam" to 6, "ezhaam" to 7, "ettaam" to 8, "onbadhaam" to 9, "ombadhaam" to 9, "pathaam" to 10, "patthaam" to 10,
        "ஒன்னாம்" to 1, "ஒண்ணாம்" to 1, "ஒன்றாம்" to 1, "ரெண்டாம்" to 2, "இரண்டாம்" to 2, "மூணாம்" to 3, "மூன்றாம்" to 3, "நாலாம்" to 4, "நான்காம்" to 4,
        "அஞ்சாம்" to 5, "ஐந்தாம்" to 5, "ஆறாம்" to 6, "ஏழாம்" to 7, "எட்டாம்" to 8, "ஒன்பதாம்" to 9, "பத்தாம்" to 10)
    private val dateWord = """(?:thethi|thedhi|theti|thedi|thethiyila|thethila|thedhila|tharikh|thaarikh|தேதி\p{L}*)"""
    private val ordinalDay = Regex("""(?i)(?<![\p{L}\p{M}\d])(\d{1,2}|${ordinalWords.keys.joinToString("|")})\s*(?:-?\s*(?:aam|am|vadhu|ஆம்))?\s*$dateWord(?![\p{L}\p{M}])""")

    /**
     * The day the owner names in a way the books' reading doesn't (a day already gone): "munnaal" / "முந்தா நாள்" (the day before
     * yesterday), a weekday ("Monday", "pona Sunday", "திங்கக்கிழமை" — its last one, today when it is today; "pona …" a week back
     * then), "anjaam thedhi" / "5 தேதி" (this month's, or last month's when it hasn't come yet). Null: none said.
     */
    private fun ownDay(text: String, raw: String, today: LocalDate): LocalDate? {
        val heard = "$text $raw"
        if (beforeYesterday.containsMatchIn(heard)) return today.minusDays(2)
        weekdays.firstOrNull { (_, r) -> r.containsMatchIn(heard) }?.let { (dow, r) ->
            var d = today
            while (d.dayOfWeek != dow) d = d.minusDays(1)
            // ("pona Monday" said on a Monday: last week's)
            val at = r.find(heard)!!.range.first
            if (d == today && lastWord.findAll(heard).any { it.range.last < at && at - it.range.last <= 3 }) d = d.minusWeeks(1)
            return d
        }
        ordinalDay.find(heard)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: ordinalWords[m.groupValues[1].lowercase(Locale.ROOT)] ?: return null
            val here = runCatching { today.withDayOfMonth(n) }.getOrNull()
            return if (here != null && !here.isAfter(today)) here else runCatching { today.minusMonths(1).withDayOfMonth(n) }.getOrNull()
        }
        return null
    }

    /** A span of days already gone the line asks about — this / last week, this / last month, the last N days — up to today, at most 62 days. */
    private fun spanOf(text: String, people: List<String>, today: LocalDate): Triple<LocalDate, LocalDate, ChatPeriod.Kind>? {
        val p = KaiChatUnderstanding.understand(text, today, people).period ?: return null
        if (p.from == p.to || p.from.isAfter(today)) return null
        if (p.kind !in setOf(ChatPeriod.Kind.THIS_WEEK, ChatPeriod.Kind.LAST_WEEK, ChatPeriod.Kind.THIS_MONTH, ChatPeriod.Kind.LAST_MONTH, ChatPeriod.Kind.LAST_DAYS)) return null
        val to = if (p.to.isAfter(today)) today else p.to
        val from = if (java.time.temporal.ChronoUnit.DAYS.between(p.from, to) > 61) to.minusDays(61) else p.from
        return Triple(from, to, p.kind)
    }

    // ------------------------------------------------------------ a sale: prices → opening → draft → Confirm

    private suspend fun startSale(sale: KaiServices.SaleLine, said: String, prices: Map<String, BigDecimal>, lang: KaiLang): KaiTurn {
        val today = now().toLocalDate()
        if (runCatching { books.cashNote(today) }.getOrNull()?.dayClosed == true) {
            waiting = null
            return turn(lang, KaiMood.CLARIFY, ta = "ஓனர், இன்னைக்கு Daily Sales Note ஏற்கனவே close ஆகிடுச்சு — இனி sale போட முடியாது. எதுவும் சேமிக்கல.",
                tl = "Owner, innaikku Daily Sales Note already close aagiduchu — ini sale poda mudiyaadhu. Edhuvum save pannala.",
                en = "Owner, today's Daily Sales Note is already closed — no more sales can go in. Nothing was saved.")
        }
        // "mudi vettu onnu" with "Gents Haircut" and "Kids Haircut" on the Menu: which of theirs — never a new "Hair cutting" asked
        // its price, never one of theirs by itself (owner, 19 Oct 2026).
        // "hand wax 300" (owner, 19 Oct 2026: "hand wax correct-ah work aagudhaa"): full arms or half arms — asked, never guessed, the
        // amount said kept for the one chosen; the owner's own Menu items for it first, then the master list's two.
        for ((i, item) in sale.items.withIndex()) if (priceOf(prices, item.name) == null) {
            val group = KaiServices.askFor(item.name)
            if (item.amount != null && group.isEmpty()) continue
            val own = KaiServices.ownChoices(item.name, prices.keys)
            val choices = own.ifEmpty { group.filter { priceOf(prices, it) != null }.ifEmpty { group } }
            if (choices.isNotEmpty()) return askChoice(sale, i, choices, said, prices, lang, onMenu = own.isNotEmpty() || choices.any { priceOf(prices, it) != null })
        }
        val amounts = sale.items.map { i -> i.amount ?: priceOf(prices, i.name)?.multiply(BigDecimal(i.qty)) }
        // A price said with the sale ("oru cutting 150") for a service with none kept: kept with this Confirm.
        val learned = sale.items.filter { it.amount != null && it.qty == 1 && priceOf(prices, it.name) == null }.associate { it.name to it.amount!! }
        val needsOpening = runCatching { tools.cashOpening(today) }.getOrNull() == null
        val d = Draft(java.util.UUID.randomUUID().toString().take(8), sale.items, amounts, sale.upi == true, learned, null, needsOpening, said, lang)
        tools.log("daily_sales", "chat", "sale said: ${sale.items.joinToString { "${it.name} ×${it.qty}" }}", ActionStatus.ANSWERED, null, said)
        return next(d, lang)
    }

    private fun askChoice(sale: KaiServices.SaleLine, index: Int, choices: List<String>, said: String, prices: Map<String, BigDecimal>, lang: KaiLang,
        onMenu: Boolean = true): KaiTurn {
        waiting = ChoiceAsked(sale, index, choices, said, lang)
        val name = sale.items[index].name
        fun shown(c: String) = "“$c”" + (priceOf(prices, c)?.let { " (${rupees(it)})" } ?: "")
        val card = KaiCard(choices.map { c -> c + (priceOf(prices, c)?.let { " — ${rupees(it)}" } ?: "") }, emptyList())
        // A word that is one of the master list's services ("hand wax"): which one — none of them on the Menu yet.
        if (!onMenu) {
            val list = choices.joinToString(" / ") { "“$it”" }
            val heard = sale.items[index].amount?.let { " (${rupees(it)})" }.orEmpty()
            return turn(lang, KaiMood.CLARIFY,
                ta = "ஓனர், எது$heard — $list? பேர் சொல்லுங்க (உதா: “${choices.first()}”). இன்னும் எதுவும் போடல.",
                tl = "Owner, edhu$heard — $list? Per sollunga (eg: “${choices.first()}”). Innum edhuvum podala.",
                en = "Owner, which one$heard — $list? Say its name (e.g. “${choices.first()}”). Nothing is added yet.", card = card)
        }
        if (choices.size == 1) {
            val c = choices.single()
            return turn(lang, KaiMood.CLARIFY,
                ta = "ஓனர், $name உங்க மெனுவுல இல்ல. மெனுவுல ${shown(c)} இருக்கு — அதுவா? “ஆமா”ன்னா அதையே போடுறேன், இல்லனா “வேணாம்”. இன்னும் எதுவும் போடல.",
                tl = "Owner, $name unga menu-la illa. Menu-la ${shown(c)} irukku — adhuvaa? “Aama” sonna adhaye podren, illana “venam”. Innum edhuvum podala.",
                en = "Owner, $name isn't on your Menu. ${shown(c)} is — that one? Say “yes” and I'll use it, or “no”. Nothing is added yet.", card = card)
        }
        val list = choices.joinToString(", ") { shown(it) }
        return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், $name-ஆ? மெனுவுல $list இருக்கு — எது? பேர் சொல்லுங்க (உதா: “${choices.first()}”). இன்னும் எதுவும் போடல.",
            tl = "Owner, $name-aa? Menu-la $list irukku — edhu? Per sollunga (eg: “${choices.first()}”). Innum edhuvum podala.",
            en = "Owner, $name? Your Menu has $list — which one? Say its name (e.g. “${choices.first()}”). Nothing is added yet.", card = card)
    }

    /** "muzhusa", "முழு" = full; "arai", "அரை" = half — the answer to "full arms-ah, half arms-ah?" as the owner says it. */
    private fun plainChoice(text: String): String = text
        .replace(Regex("""(?i)(?<![\p{L}\p{M}])(muzhusa|muzhusaa|muzhu|mulusa|mulu|full\s*ah|fulla|fullaa)(?![\p{L}\p{M}])"""), "full")
        .replace(Regex("""(?i)(?<![\p{L}\p{M}])(arai|half\s*ah|halfa|haafa|paadhi|paathi)(?![\p{L}\p{M}])"""), "half")
        .replace(Regex("""முழுசா|முழுசு|முழு|ஃபுல்லா|ஃபுல்|புல்"""), "full")
        .replace(Regex("""அரை|ஹாஃப்|ஹாப்|பாதி"""), "half")

    /** The one of [choices] the owner's answer names — by its whole name, typed or by sound, or by the word only it has ("gents", "kids"). */
    private fun choiceIn(text: String, raw: String, choices: List<String>): String? {
        val heard = listOf(text, raw, menuBySound(raw, choices, emptyList())).distinct()
        choices.filter { c -> heard.any { h -> " ${h.lowercase(Locale.ROOT)} ".contains(" ${c.lowercase(Locale.ROOT)} ") || menuByPart(h, listOf(c)) != h } }
            .singleOrNull()?.let { return it }
        val split = choices.associateWith { it.lowercase(Locale.ROOT).split(Regex("""\s+""")) }
        val common = split.values.reduce { a, b -> a.intersect(b.toSet()).toList() }.toSet()
        val said = heard.flatMap { it.trim().split(Regex("""\s+""")) }.filter { it.length >= 3 }
        return choices.filter { c -> split.getValue(c).any { w -> w !in common && w.length >= 3 && said.any { sameSound(it, w) } } }.singleOrNull()
    }

    private fun next(d: Draft, lang: KaiLang): KaiTurn = when {
        d.missing >= 0 -> askPrice(d, d.missing, lang)
        d.needsOpening && d.opening == null -> askOpening(d, lang)
        else -> draftTurn(d, lang)
    }

    private fun askPrice(d: Draft, index: Int, lang: KaiLang, again: Boolean = false): KaiTurn {
        waiting = PriceAsked(d, index)
        val name = d.items[index].name
        val per = when (d.items[index].per) { "hour" -> pick(lang, " ஒரு மணி நேரத்துக்கு", " oru manineram-ku", " an hour"); "day" -> pick(lang, " ஒரு நாளுக்கு", " oru naal-ku", " a day"); else -> "" }
        return if (again) turn(lang, KaiMood.CLARIFY, ta = "ஓனர், $name$per விலை மட்டும் சொல்லுங்க — உதா: “150”.",
            tl = "Owner, $name$per vilai mattum sollunga — eg: “150”.", en = "Owner, just the price of $name$per — e.g. “150”.")
        else turn(lang, KaiMood.CLARIFY, ta = "ஓனர், $name உங்க மெனுவுல இல்ல — $name$per எவ்வளவு? சொன்னா மெனுவுல சேர்த்துடுறேன்.",
            tl = "Owner, $name unga menu-la illa — $name$per evlo? Sonna menu-la serthudren.",
            en = "Owner, $name isn't on your Menu — how much is $name$per? Tell me and I'll add it.")
    }

    private fun askOpening(d: Draft, lang: KaiLang, again: Boolean = false): KaiTurn {
        waiting = OpeningAsked(d)
        return if (again) turn(lang, KaiMood.CLARIFY, ta = "ஓனர், கல்லாப்பெட்டி opening தொகை மட்டும் சொல்லுங்க — உதா: “500”, இல்லனா “0”.",
            tl = "Owner, Kallapetti opening amount mattum sollunga — eg: “500”, illana “0”.", en = "Owner, just the opening Kallapetti amount — e.g. “500”, or “0”.")
        else turn(lang, KaiMood.CLARIFY, ta = "ஓனர், இன்னைக்கு Daily Sales Note-க்கு முதல்ல கல்லாப்பெட்டி opening வேணும் — காலைல கல்லாவுல எவ்வளவு cash இருந்துச்சு? (இல்லனா “0”)",
            tl = "Owner, innaikku Daily Sales Note-ku mudhalla Kallapetti opening venum — kaalaila kallaa-la evlo cash irundhuchu? (illana “0”)",
            en = "Owner, today's Daily Sales Note needs the opening Kallapetti first — how much cash was in it this morning? (or “0”)")
    }

    private fun lines(d: Draft): List<String> = d.items.mapIndexed { i, it ->
        val per = it.per?.let { p -> if (p == "hour") " hr" else " day" }.orEmpty()
        "${it.name} ×${it.qty}$per — ${rupees(d.amounts[i]!!)}"
    }

    private fun draftTurn(d: Draft, lang: KaiLang): KaiTurn {
        waiting = Shown(d)
        val mode = if (d.upi) "UPI" else "Cash"
        val learned = d.newPrices.entries.joinToString(", ") { "${it.key} ${rupees(it.value)}" }
        val card = KaiCard(
            lines(d) + listOfNotNull(
                pick(lang, "மொத்தம் ${rupees(d.total)} · $mode", "Mothama ${rupees(d.total)} · $mode", "Total ${rupees(d.total)} · $mode"),
                d.opening?.let { pick(lang, "கல்லாப்பெட்டி opening ${rupees(it)}", "Kallapetti opening ${rupees(it)}", "Opening Kallapetti ${rupees(it)}") },
                learned.takeIf { it.isNotEmpty() }?.let { pick(lang, "விலை நினைவு: $it", "Vilai nyabagam: $it", "Price kept: $it") },
            ),
            listOf(
                KaiButton(pick(lang, "உறுதி செய்", "Confirm", "Confirm"), KaiAction.ConfirmSale(d.key), primary = true),
                KaiButton(if (d.upi) "Cash" else "UPI", KaiAction.SaleMode(d.key, !d.upi)),
                KaiButton(pick(lang, "ரத்து", "Cancel", "Cancel"), KaiAction.CancelSale(d.key)),
            ),
        )
        val names = d.items.joinToString(" + ") { if (it.qty > 1) "${it.qty} ${it.name}" else it.name }
        return turn(lang, KaiMood.CLARIFY,
            ta = "$names — ${rupees(d.total)} ($mode) Daily Sales Note-ல போடவா? உறுதி செய்யுங்க.",
            tl = "$names — ${rupees(d.total)} ($mode) Daily Sales Note-la podava? Confirm pannunga.",
            en = "$names — ${rupees(d.total)} ($mode) to the Daily Sales Note? Confirm it.", card = card)
    }

    private fun dropped(lang: KaiLang) = turn(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், அந்த sale-ஐ விட்டுட்டேன். எதுவும் சேமிக்கல.",
        tl = "Seri Owner, andha sale-a vittuten. Edhuvum save pannala.", en = "Okay Owner, I dropped that sale. Nothing was saved.")

    suspend fun act(action: KaiAction, lang: KaiLang): KaiTurn? = when (action) {
        is KaiAction.ConfirmSale -> confirmSale(action.key, lang)
        is KaiAction.CancelSale -> cancelSale(action.key, lang)
        is KaiAction.SaleMode -> (waiting as? Shown)?.draft?.takeIf { it.key == action.key }?.let { draftTurn(it.copy(upi = action.upi), lang) }
        is KaiAction.ConfirmDayClose -> closeDay(action.day, lang)
        is KaiAction.CancelDayClose -> { waiting = null; turn(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், day close பண்ணல.", tl = "Seri Owner, day close pannala.", en = "Okay Owner, the day stays open.") }
        is KaiAction.ConfirmServicePrice -> confirmPrice(action.key, lang)
        else -> null
    }

    private suspend fun cancelSale(key: String, lang: KaiLang): KaiTurn {
        if ((waiting as? MenuToKeep)?.key == key || (waiting as? PriceToKeep)?.key == key) {
            waiting = null
            return turn(lang, KaiMood.NEUTRAL, ta = "சரி ஓனர், மெனுவுல எதுவும் மாத்தல.", tl = "Seri Owner, menu-la edhuvum maathala.", en = "Okay Owner, the Menu stays as it was.")
        }
        if ((waiting as? Shown)?.draft?.key == key) waiting = null
        tools.log("daily_sales", "chat", "sale draft cancelled", ActionStatus.CANCELLED, null, null)
        return dropped(lang)
    }

    private suspend fun confirmSale(key: String, lang: KaiLang): KaiTurn {
        val d = (waiting as? Shown)?.draft?.takeIf { it.key == key } ?: return turn(lang, KaiMood.CLARIFY,
            ta = "ஓனர், அந்த sale draft இப்போ இல்ல — திரும்ப சொல்லுங்க.", tl = "Owner, andha sale draft ippo illa — thirumba sollunga.", en = "Owner, that sale draft is gone — please say it again.")
        waiting = null
        val today = now().toLocalDate()
        if (d.needsOpening && d.opening != null && runCatching { tools.cashOpening(today) }.getOrNull() == null) {
            val o = tools.setCashOpening(today, d.opening)
            if (o is ActionOutcome.Failed) return notSaved(o.reason, lang)
        }
        val note = KaiServices.note(d.items.mapIndexed { i, it -> it to d.amounts[i]!! })
        return when (val r = tools.addCashSale(today, d.total, d.upi, note)) {
            is ActionOutcome.Done -> {
                for ((n, p) in d.newPrices) keepPrice(n, p)
                lastSaleAt = now()
                tools.log("daily_sales", "daily cash note", "sale saved: $note", ActionStatus.CONFIRMED, r.reference, d.said)
                val soFar = runCatching { books.cashNote(today) }.getOrNull()?.lines.orEmpty().filter { it.incoming }.sumOf { it.amount }
                val mode = if (d.upi) "UPI" else "Cash"
                val kept = if (d.newPrices.isEmpty()) "" else pick(lang, " விலை நினைவுல வெச்சுக்கிட்டேன் — அடுத்த தடவை நேரா போடுறேன்.",
                    " Vilai nyabagam vechukitten — adutha thadava nera podren.", " I've kept the price — next time it goes straight in.")
                turn(lang, KaiMood.HAPPY,
                    ta = "Save ஆகிடுச்சு ✅ ${rupees(d.total)} ($mode) Daily Sales Note-ல. இன்னைக்கு இதுவரைக்கும் ${KaiFormat.rupees(soFar)}.$kept",
                    tl = "Save aagiduchu ✅ ${rupees(d.total)} ($mode) Daily Sales Note-la. Innaikku ithuvaraikum ${KaiFormat.rupees(soFar)}.$kept",
                    en = "Saved ✅ ${rupees(d.total)} ($mode) in the Daily Sales Note. Today so far: ${KaiFormat.rupees(soFar)}.$kept")
            }
            is ActionOutcome.Failed -> notSaved(r.reason, lang)
        }
    }

    private fun notSaved(reason: String, lang: KaiLang): KaiTurn {
        tools.log("daily_sales", "daily cash note", "not saved: $reason", ActionStatus.FAILED, null, null)
        val closed = Regex("""(?i)submitted|closed""").containsMatchIn(reason)
        return if (closed) turn(lang, KaiMood.ERROR, ta = "ஓனர், இன்னைக்கு note close ஆகிடுச்சு — sale save ஆகல.", tl = "Owner, innaikku note close aagiduchu — sale save aagala.",
            en = "Owner, today's note is closed — the sale was not saved.")
        else turn(lang, KaiMood.ERROR, ta = "ஓனர், sale save ஆகல ($reason). திரும்ப முயற்சி பண்ணுங்க.", tl = "Owner, sale save aagala ($reason). Thirumba try pannunga.",
            en = "Owner, the sale was not saved ($reason). Please try again.")
    }

    // ------------------------------------------------------------ prices

    private fun priceTurn(name: String, price: BigDecimal?, lang: KaiLang): KaiTurn =
        if (price != null) turn(lang, KaiMood.EXPLAINING, ta = "ஓனர், $name ${rupees(price)} (நீங்க சொன்ன விலை). மாத்தணும்னா “$name rate ${price.add(BigDecimal.TEN).stripTrailingZeros().toPlainString()}”-னு சொல்லுங்க.",
            tl = "Owner, $name ${rupees(price)} (neenga sonna vilai). Maathanum-na “$name rate ${price.add(BigDecimal.TEN).stripTrailingZeros().toPlainString()}”-nu sollunga.",
            en = "Owner, $name is ${rupees(price)} (your price). To change it say “$name rate ${price.add(BigDecimal.TEN).stripTrailingZeros().toPlainString()}”.")
        else turn(lang, KaiMood.CLARIFY, ta = "ஓனர், $name-க்கு இன்னும் விலை சொல்லல. “$name rate 150”-னு சொல்லுங்க, இல்ல அடுத்த sale-ல கேக்குறேன்.",
            tl = "Owner, $name-ku innum vilai sollala. “$name rate 150”-nu sollunga, illa adutha sale-la kekkuren.",
            en = "Owner, no price for $name yet. Say “$name rate 150”, or I'll ask with the next sale.")

    private fun priceToKeep(name: String, price: BigDecimal, old: BigDecimal?, lang: KaiLang): KaiTurn {
        if (old != null && old.compareTo(price) == 0) return turn(lang, KaiMood.NEUTRAL, ta = "ஓனர், $name ஏற்கனவே ${rupees(price)} தான்.",
            tl = "Owner, $name already ${rupees(price)} dhaan.", en = "Owner, $name is already ${rupees(price)}.")
        val key = java.util.UUID.randomUUID().toString().take(8)
        waiting = PriceToKeep(key, name, price, old)
        val card = KaiCard(listOf(if (old != null) "$name: ${rupees(old)} → ${rupees(price)}" else "$name: ${rupees(price)}"), listOf(
            KaiButton(pick(lang, "உறுதி செய்", "Confirm", "Confirm"), KaiAction.ConfirmServicePrice(key), primary = true),
            KaiButton(pick(lang, "ரத்து", "Cancel", "Cancel"), KaiAction.CancelSale(key)),
        ))
        return if (old != null) turn(lang, KaiMood.CLARIFY, ta = "ஓனர், $name விலை ${rupees(old)}-ல இருந்து ${rupees(price)}-க்கு மாத்தவா? உறுதி செய்யுங்க.",
            tl = "Owner, $name vilai ${rupees(old)}-la irundhu ${rupees(price)}-ku maathava? Confirm pannunga.",
            en = "Owner, change $name from ${rupees(old)} to ${rupees(price)}? Confirm it.", card = card)
        else turn(lang, KaiMood.CLARIFY, ta = "ஓனர், $name — ${rupees(price)}-னு நினைவுல வெச்சுக்கவா? உறுதி செய்யுங்க.",
            tl = "Owner, $name — ${rupees(price)}-nu nyabagam vechukkava? Confirm pannunga.", en = "Owner, keep $name at ${rupees(price)}? Confirm it.", card = card)
    }

    /** "menu: cutting 100, shaving 80": the items (and any price changed) shown for Confirm — nothing kept before it. */
    private fun menuToKeep(items: List<Pair<String, BigDecimal>>, prices: Map<String, BigDecimal>, lang: KaiLang): KaiTurn {
        val key = java.util.UUID.randomUUID().toString().take(8)
        waiting = MenuToKeep(key, items)
        val lines = items.map { (n, p) -> priceOf(prices, n)?.takeIf { it.compareTo(p) != 0 }?.let { "$n: ${rupees(it)} → ${rupees(p)}" } ?: "$n — ${rupees(p)}" }
        val card = KaiCard(lines, listOf(
            KaiButton(pick(lang, "உறுதி செய்", "Confirm", "Confirm"), KaiAction.ConfirmServicePrice(key), primary = true),
            KaiButton(pick(lang, "ரத்து", "Cancel", "Cancel"), KaiAction.CancelSale(key)),
        ))
        return turn(lang, KaiMood.CLARIFY, ta = "ஓனர், இதை உங்க மெனுவுல சேர்க்கவா? உறுதி செய்யுங்க.", tl = "Owner, idha unga menu-la serkava? Confirm pannunga.",
            en = "Owner, add these to your Menu? Confirm it.", card = card)
    }

    private suspend fun confirmPrice(key: String, lang: KaiLang): KaiTurn? {
        (waiting as? MenuToKeep)?.takeIf { it.key == key }?.let { m ->
            waiting = null
            m.items.forEach { (n, p) -> keepPrice(n, p) }
            tools.log("daily_sales", "kai memory", "menu: ${m.items.joinToString { "${it.first} ${it.second.toPlainString()}" }}", ActionStatus.CONFIRMED, null, null)
            val list = m.items.joinToString(", ") { "${it.first} ${rupees(it.second)}" }
            return turn(lang, KaiMood.HAPPY, ta = "சரி ஓனர் ✅ மெனுவுல: $list. இனி “ஒரு ${m.items.first().first}”-னு சொன்னா நேரா draft காட்டுறேன்.",
                tl = "Seri Owner ✅ Menu-la: $list. Ini “oru ${m.items.first().first}”-nu sonna nera draft kaatren.",
                en = "Done Owner ✅ On your Menu: $list. Say “one ${m.items.first().first}” and I'll show the draft straight away.")
        }
        val w = (waiting as? PriceToKeep)?.takeIf { it.key == key } ?: return null
        waiting = null
        keepPrice(w.name, w.price)
        tools.log("daily_sales", "kai memory", "service price kept: ${w.name} ${w.price.toPlainString()}", ActionStatus.CONFIRMED, null, null)
        return turn(lang, KaiMood.HAPPY, ta = "சரி ஓனர் ✅ ${w.name} ${rupees(w.price)}. இனி “ஒரு ${w.name}”-னு சொன்னா நேரா draft காட்டுறேன்.",
            tl = "Seri Owner ✅ ${w.name} ${rupees(w.price)}. Ini “oru ${w.name}”-nu sonna nera draft kaatren.",
            en = "Done Owner ✅ ${w.name} at ${rupees(w.price)}. Say “one ${w.name}” and I'll show the draft straight away.")
    }

    private fun rateListTurn(prices: Map<String, BigDecimal>, lang: KaiLang): KaiTurn =
        if (prices.isEmpty()) turn(lang, KaiMood.NEUTRAL, ta = "ஓனர், உங்க மெனுவுல இன்னும் எதுவும் இல்ல. “மெனு: cutting 100, shaving 80”-னு சொல்லுங்க, இல்ல Daily Sales Note → Menu-ல சேருங்க.",
            tl = "Owner, unga menu-la innum edhuvum illa. “menu: cutting 100, shaving 80”-nu sollunga, illa Daily Sales Note → Menu-la serunga.",
            en = "Owner, your Menu is empty. Say “menu: cutting 100, shaving 80”, or add items in Daily Sales Note → Menu.")
        else {
            val list = prices.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${rupees(it.value)}" }
            turn(lang, KaiMood.EXPLAINING, ta = "ஓனர், உங்க மெனு: $list.", tl = "Owner, unga menu: $list.", en = "Owner, your Menu: $list.",
                card = KaiCard(prices.entries.sortedBy { it.key }.map { "${it.key} — ${rupees(it.value)}" }, emptyList()))
        }

    // ------------------------------------------------------------ a day's sales, the day close, the PDF

    /** A day's sales by service, from its Daily Cash Note entries: (service → count, amount), other money in, money out. */
    private data class DaySales(val services: Map<String, Pair<Int, Double>>, val other: Double, val totalIn: Double, val cashIn: Double, val upiIn: Double,
                                val out: Double, val kallapetti: Double?, val closed: Boolean, val entries: Int)

    private suspend fun daySales(day: LocalDate): DaySales? {
        val note = runCatching { books.cashNote(day) }.getOrNull() ?: return null
        val services = LinkedHashMap<String, Pair<Int, Double>>()
        var other = 0.0
        for (l in note.lines.filter { it.incoming }) {
            val parts = KaiServices.fromNote(l.note)
            if (parts.isEmpty()) other += l.amount
            for ((n, q, a) in parts) services[n] = (services[n]?.first ?: 0) + q to (services[n]?.second ?: 0.0) + a.toDouble()
        }
        val ins = note.lines.filter { it.incoming }
        return DaySales(services, other, if (note.lines.isEmpty()) note.totals.totalIn else ins.sumOf { it.amount }, ins.filter { !it.upi }.sumOf { it.amount },
            ins.filter { it.upi }.sumOf { it.amount }, note.lines.filter { !it.incoming }.sumOf { it.amount }.takeIf { note.lines.isNotEmpty() } ?: note.totals.totalOut,
            note.kallapetti, note.dayClosed, note.entries)
    }

    private fun dayLabel(day: LocalDate, lang: KaiLang): String {
        val today = now().toLocalDate()
        return when (day) {
            today -> pick(lang, "இன்னைக்கு", "Innaikku", "Today")
            today.minusDays(1) -> pick(lang, "நேத்து", "Nethu", "Yesterday")
            // (a day of this past week with its weekday: "October 12 (Monday)" — the day the owner named, plainly)
            else -> day.format(DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)) + if (!day.isAfter(today) && day.isAfter(today.minusDays(7)))
                " (" + pick(lang, listOf("திங்கள்", "செவ்வாய்", "புதன்", "வியாழன்", "வெள்ளி", "சனி", "ஞாயிறு")[day.dayOfWeek.value - 1],
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH), day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)) + ")" else ""
        }
    }

    private fun serviceLines(s: DaySales, lang: KaiLang): List<String> =
        s.services.map { (n, v) -> "$n ×${v.first} — ${KaiFormat.rupees(v.second)}" } +
            listOfNotNull(s.other.takeIf { it > 0 }?.let { pick(lang, "மற்ற வரவு — ${KaiFormat.rupees(it)}", "Matra varavu — ${KaiFormat.rupees(it)}", "Other money in — ${KaiFormat.rupees(it)}") })

    private fun summary(s: DaySales, lang: KaiLang): String {
        val parts = s.services.entries.joinToString(", ") { (n, v) -> "$n ×${v.first} ${KaiFormat.rupees(v.second)}" }
        val split = "Cash ${KaiFormat.rupees(s.cashIn)}, UPI ${KaiFormat.rupees(s.upiIn)}"
        return if (parts.isEmpty()) "($split)" else "($split): $parts"
    }

    private suspend fun salesTurn(day: LocalDate, lang: KaiLang, forceAnswer: Boolean): KaiTurn? {
        val s = daySales(day) ?: return null
        // A day with no Kai sale in it is the Business Brain's to answer (as before) — unless asked "ithuvaraikum".
        if (s.services.isEmpty() && !forceAnswer) return null
        val label = dayLabel(day, lang)
        val isToday = day == now().toLocalDate()
        if (s.totalIn <= 0.0) return turn(lang, KaiMood.NEUTRAL,
            ta = "$label Daily Sales Note-ல sale எதுவும் இல்ல ஓனர்.", tl = "$label Daily Sales Note-la sale edhuvum illa owner.", en = "$label: no sales in the Daily Sales Note, Owner.")
        val upTo = if (isToday && !s.closed) pick(lang, " இதுவரைக்கும்", " ithuvaraikum", " so far") else ""
        val card = KaiCard(serviceLines(s, lang) + listOfNotNull(s.out.takeIf { it > 0 }?.let { pick(lang, "செலவு — ${KaiFormat.rupees(it)}", "Selavu — ${KaiFormat.rupees(it)}", "Money out — ${KaiFormat.rupees(it)}") }),
            listOf(KaiButton(pick(lang, "PDF 📄", "PDF 📄", "PDF 📄"), KaiAction.ShareCashNote(day))))
        return KaiTurn(ChatReply(pick(lang,
            ta = "$label$upTo sales ${KaiFormat.rupees(s.totalIn)} ஓனர் ${summary(s, lang)}.",
            tl = "$label$upTo sales ${KaiFormat.rupees(s.totalIn)} owner ${summary(s, lang)}.",
            en = "$label$upTo sales: ${KaiFormat.rupees(s.totalIn)}, Owner ${summary(s, lang)}."), KaiMood.HAPPY, ChatIntent.MONTHLY_SALES), card)
    }

    private suspend fun closeAsk(day: LocalDate, lang: KaiLang): KaiTurn? {
        val s = daySales(day) ?: return null
        if (s.closed) return turn(lang, KaiMood.NEUTRAL, ta = "ஓனர், இன்னைக்கு ஏற்கனவே close ஆகிடுச்சு — மொத்தம் ${KaiFormat.rupees(s.totalIn)} ${summary(s, lang)}.",
            tl = "Owner, innaikku already close aagiduchu — mothama ${KaiFormat.rupees(s.totalIn)} ${summary(s, lang)}.",
            en = "Owner, today is already closed — total ${KaiFormat.rupees(s.totalIn)} ${summary(s, lang)}.",
            card = KaiCard(serviceLines(s, lang), listOf(KaiButton("PDF 📄", KaiAction.ShareCashNote(day)))))
        if (s.entries == 0) return null
        waiting = CloseAsked(day)
        val card = KaiCard(serviceLines(s, lang) + listOfNotNull(
            pick(lang, "மொத்த sales ${KaiFormat.rupees(s.totalIn)} · Cash ${KaiFormat.rupees(s.cashIn)} · UPI ${KaiFormat.rupees(s.upiIn)}",
                "Mothama sales ${KaiFormat.rupees(s.totalIn)} · Cash ${KaiFormat.rupees(s.cashIn)} · UPI ${KaiFormat.rupees(s.upiIn)}",
                "Total sales ${KaiFormat.rupees(s.totalIn)} · Cash ${KaiFormat.rupees(s.cashIn)} · UPI ${KaiFormat.rupees(s.upiIn)}"),
            s.out.takeIf { it > 0 }?.let { pick(lang, "செலவு ${KaiFormat.rupees(it)}", "Selavu ${KaiFormat.rupees(it)}", "Money out ${KaiFormat.rupees(it)}") },
            s.kallapetti?.let { pick(lang, "கல்லாப்பெட்டி ${KaiFormat.rupees(it)}", "Kallapetti ${KaiFormat.rupees(it)}", "Kallapetti ${KaiFormat.rupees(it)}") },
        ), listOf(
            KaiButton(pick(lang, "Day close ✅", "Day close ✅", "Close day ✅"), KaiAction.ConfirmDayClose(day), primary = true),
            KaiButton(pick(lang, "இன்னும் இல்ல", "Innum illa", "Not yet"), KaiAction.CancelDayClose(day)),
        ))
        return turn(lang, KaiMood.CLARIFY,
            ta = "இன்னைக்கு மொத்தம் ${KaiFormat.rupees(s.totalIn)} sales ஓனர் ${summary(s, lang)}. Day close பண்ணவா?",
            tl = "Innaikku mothama ${KaiFormat.rupees(s.totalIn)} sales owner ${summary(s, lang)}. Day close pannava?",
            en = "Today's sales: ${KaiFormat.rupees(s.totalIn)}, Owner ${summary(s, lang)}. Close the day?", card = card)
    }

    private suspend fun closeDay(day: LocalDate, lang: KaiLang): KaiTurn {
        waiting = null
        return when (val r = tools.closeCashDay(day)) {
            is ActionOutcome.Done -> {
                tools.log("daily_sales", "daily cash note", "day closed: $day", ActionStatus.CONFIRMED, r.reference, null)
                val s = daySales(day)
                val total = KaiFormat.rupees(s?.totalIn ?: 0.0)
                turn(lang, KaiMood.HAPPY,
                    ta = "இன்னைக்கு close ஆகிடுச்சு ✅ மொத்தம் $total sales. PDF வேணும்னா கீழ அழுத்துங்க.",
                    tl = "Innaikku close aagiduchu ✅ Mothama $total sales. PDF venumna keezha press pannunga.",
                    en = "The day is closed ✅ Total sales $total. Tap below for the PDF.",
                    card = KaiCard(s?.let { serviceLines(it, lang) }.orEmpty(), listOf(KaiButton("PDF 📄", KaiAction.ShareCashNote(day), primary = true))))
            }
            is ActionOutcome.Failed -> {
                tools.log("daily_sales", "daily cash note", "day not closed: ${r.reason}", ActionStatus.FAILED, null, null)
                turn(lang, KaiMood.ERROR, ta = "ஓனர், day close ஆகல (${r.reason}). திரும்ப முயற்சி பண்ணுங்க.",
                    tl = "Owner, day close aagala (${r.reason}). Thirumba try pannunga.", en = "Owner, the day was not closed (${r.reason}). Please try again.")
            }
        }
    }

    /** Days' sales added up: by service, other money in, cash / UPI, money out, and the days that had entries. */
    private data class SpanSales(val services: Map<String, Pair<Int, Double>>, val other: Double, val totalIn: Double, val cashIn: Double, val upiIn: Double,
                                 val out: Double, val days: Int, val entries: Int)

    private suspend fun spanSales(from: LocalDate, to: LocalDate): SpanSales {
        val services = LinkedHashMap<String, Pair<Int, Double>>()
        var other = 0.0; var totalIn = 0.0; var cash = 0.0; var upi = 0.0; var out = 0.0; var days = 0; var entries = 0
        var d = from
        while (!d.isAfter(to)) {
            daySales(d)?.let { s ->
                for ((n, v) in s.services) services[n] = (services[n]?.first ?: 0) + v.first to (services[n]?.second ?: 0.0) + v.second
                other += s.other; totalIn += s.totalIn; cash += s.cashIn; upi += s.upiIn; out += s.out; entries += s.entries
                if (s.entries > 0) days++
            }
            d = d.plusDays(1)
        }
        val sorted = services.entries.sortedByDescending { it.value.second }.associate { it.key to it.value }
        return SpanSales(sorted, other, totalIn, cash, upi, out, days, entries)
    }

    private fun spanLabel(from: LocalDate, to: LocalDate, kind: ChatPeriod.Kind, lang: KaiLang): String {
        val f = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        val dates = if (from == to) from.format(f) else "${from.format(f)} – ${to.format(f)}"
        val n = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1
        val name = when (kind) {
            ChatPeriod.Kind.THIS_WEEK -> pick(lang, "இந்த வாரம்", "Indha vaaram", "This week")
            ChatPeriod.Kind.LAST_WEEK -> pick(lang, "போன வாரம்", "Pona vaaram", "Last week")
            ChatPeriod.Kind.THIS_MONTH -> pick(lang, "இந்த மாசம்", "Indha maasam", "This month")
            ChatPeriod.Kind.LAST_MONTH -> pick(lang, "போன மாசம்", "Pona maasam", "Last month")
            else -> pick(lang, "கடந்த $n நாள்", "Kadandha $n naal", "The last $n days")
        }
        return "$name ($dates)"
    }

    private fun spanLines(s: SpanSales, lang: KaiLang): List<String> =
        s.services.map { (n, v) -> "$n ×${v.first} — ${KaiFormat.rupees(v.second)}" } +
            listOfNotNull(s.other.takeIf { it > 0 }?.let { pick(lang, "மற்ற வரவு — ${KaiFormat.rupees(it)}", "Matra varavu — ${KaiFormat.rupees(it)}", "Other money in — ${KaiFormat.rupees(it)}") },
                s.out.takeIf { it > 0 }?.let { pick(lang, "செலவு — ${KaiFormat.rupees(it)}", "Selavu — ${KaiFormat.rupees(it)}", "Money out — ${KaiFormat.rupees(it)}") })

    /**
     * A span's sales ("indha maasam sales evlo", "pona vaaram", "kadandha 7 naal"), service by service with the top one, and its PDF
     * button — or [top]: which service sold the most. Null when the span has no Kai sale in it: the Business Brain answers, as before.
     */
    private suspend fun rangeTurn(from: LocalDate, to: LocalDate, kind: ChatPeriod.Kind, lang: KaiLang, top: Boolean): KaiTurn? {
        val s = spanSales(from, to)
        if (s.services.isEmpty()) return null
        val label = spanLabel(from, to, kind, lang)
        val (topName, topV) = s.services.entries.first().let { it.key to it.value }
        val topShown = "$topName ×${topV.first} ${KaiFormat.rupees(topV.second)}"
        val total = KaiFormat.rupees(s.totalIn)
        val split = "Cash ${KaiFormat.rupees(s.cashIn)}, UPI ${KaiFormat.rupees(s.upiIn)}"
        val card = KaiCard(spanLines(s, lang), listOf(KaiButton("PDF 📄", KaiAction.ShareCashNote(from, to))))
        val reply = if (top) pick(lang,
            ta = "$label அதிகமா போனது: $topShown ஓனர். மொத்த sales $total.",
            tl = "$label adhigama pochu: $topShown owner. Mothama sales $total.",
            en = "$label, the top service: $topShown, Owner. Total sales $total.")
        else pick(lang,
            ta = "$label sales $total ஓனர் ($split) — ${s.days} நாள். அதிகமா: $topShown.",
            tl = "$label sales $total owner ($split) — ${s.days} naal. Adhigama: $topShown.",
            en = "$label sales: $total, Owner ($split) over ${s.days} days. Top: $topShown.")
        return KaiTurn(ChatReply(reply, KaiMood.HAPPY, ChatIntent.MONTHLY_SALES), card)
    }

    private suspend fun rangePdfTurn(from: LocalDate, to: LocalDate, kind: ChatPeriod.Kind, lang: KaiLang): KaiTurn {
        val s = spanSales(from, to)
        val label = spanLabel(from, to, kind, lang)
        if (s.entries == 0) return turn(lang, KaiMood.NEUTRAL, ta = "$label Daily Sales Note-ல entry எதுவும் இல்ல ஓனர் — PDF-க்கு எதுவும் இல்ல.",
            tl = "$label Daily Sales Note-la entry edhuvum illa owner — PDF-ku edhuvum illa.", en = "$label: nothing in the Daily Sales Note, Owner — no PDF to make.")
        return turn(lang, KaiMood.NEUTRAL, ta = "$label Daily Sales Note PDF ரெடி — மொத்தம் ${KaiFormat.rupees(s.totalIn)}. கீழ அழுத்தி share பண்ணுங்க.",
            tl = "$label Daily Sales Note PDF ready — mothama ${KaiFormat.rupees(s.totalIn)}. Keezha press panni share pannunga.",
            en = "$label: the Daily Sales Note PDF is ready — total ${KaiFormat.rupees(s.totalIn)}. Tap below to share it.",
            card = KaiCard(spanLines(s, lang), listOf(KaiButton("PDF 📄", KaiAction.ShareCashNote(from, to), primary = true))))
    }

    private suspend fun pdfTurn(day: LocalDate, lang: KaiLang): KaiTurn {
        val s = daySales(day)
        val label = dayLabel(day, lang)
        if (s == null || s.entries == 0) return turn(lang, KaiMood.NEUTRAL, ta = "$label Daily Sales Note-ல entry எதுவும் இல்ல ஓனர் — PDF-க்கு எதுவும் இல்ல.",
            tl = "$label Daily Sales Note-la entry edhuvum illa owner — PDF-ku edhuvum illa.", en = "$label: nothing in the Daily Sales Note, Owner — no PDF to make.")
        return turn(lang, KaiMood.NEUTRAL, ta = "$label Daily Sales Note PDF ரெடி — மொத்தம் ${KaiFormat.rupees(s.totalIn)}. கீழ அழுத்தி share பண்ணுங்க.",
            tl = "$label Daily Sales Note PDF ready — mothama ${KaiFormat.rupees(s.totalIn)}. Keezha press panni share pannunga.",
            en = "$label's Daily Sales Note PDF is ready — total ${KaiFormat.rupees(s.totalIn)}. Tap below to share it.",
            card = KaiCard(serviceLines(s, lang), listOf(KaiButton("PDF 📄", KaiAction.ShareCashNote(day), primary = true))))
    }

    companion object {
        /** The owner's service prices in their private memory (PREFERENCE, "Hair cutting|150"). */
        const val PRICE_KIND = "SERVICE_PRICE"
    }
}
