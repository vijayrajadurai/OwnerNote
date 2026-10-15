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

    private var waiting: Waiting? = null
    /** Prices confirmed in this conversation, for a phone with no business memory open. */
    private val sessionPrices = LinkedHashMap<String, BigDecimal>()
    private var lastSaleAt: LocalDateTime? = null

    fun busy() = waiting != null
    fun reset() { waiting = null }

    // ------------------------------------------------------------ prices (the owner's own memory)

    private suspend fun prices(): Map<String, BigDecimal> {
        val kept = runCatching { memory?.current()?.usable() }.getOrNull().orEmpty()
            .filter { it.memoryType == MemoryType.PREFERENCE && it.meaningType == PRICE_KIND }
            .mapNotNull { m -> m.meaningValue.split('|').takeIf { it.size == 2 }?.let { (n, p) -> p.toBigDecimalOrNull()?.let { n to it } } }
            .toMap()
        return kept + sessionPrices
    }

    private suspend fun keepPrice(name: String, price: BigDecimal) {
        sessionPrices[name] = price
        runCatching { memory?.current()?.savePreference("service price ${name.lowercase(Locale.ROOT)}", PRICE_KIND, "$name|${price.stripTrailingZeros().toPlainString()}") }
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
        // As the mic writes the name of a service the owner added ("ஹேர் போட்டாக்ஸ்" = their Hair botox): that name, by its sound.
        val sounded = ownBySound(raw, prices.keys)
        val namesPerson = people.any { p -> p.isNotBlank() && Regex("""(?i)(?<![\p{L}])${Regex.escape(p)}(?![\p{L}])""").containsMatchIn(text) }

        // The rate list: the prices the owner gave.
        if (rateList.containsMatchIn(text) && !namesPerson) return rateListTurn(prices, lang)

        // The day's PDF: "innaikku sales pdf kudu", "nethu report", "Oct 10 daily note pdf".
        if (pdfWord.containsMatchIn(text) && noteWord.containsMatchIn(text) && !namesPerson && Regex("""(?i)pdf|report|ரிப்போர்ட்|பிடிஎஃப்""").containsMatchIn(text))
            return pdfTurn(dayOf(text, people, today) ?: today, lang)

        // The day close: "innaikku avlothan", "day close pannu", "கடை க்ளோஸ்".
        if (!busy && !namesPerson && (dayClose.containsMatchIn(text) || thatsAll.containsMatchIn(text) && (todayWord.containsMatchIn(text) || recentSale())))
            closeAsk(today, lang)?.let { return it }

        // Sales so far / on a day: "ithuvaraikum evlo sales", "innaikku sales evlo", "nethu evlo vyabaram", "Oct 10 sales".
        if (!namesPerson && (soFar.containsMatchIn(text) && howMuch.containsMatchIn(text) || salesWord.containsMatchIn(text) && howMuch.containsMatchIn(text))) {
            val day = dayOf(text, people, today)
            val span = KaiChatUnderstanding.understand(text, today, people).period
            if (day != null || soFar.containsMatchIn(text) || span == null) {
                salesTurn(day ?: today, lang, forceAnswer = soFar.containsMatchIn(text) || day != null && span?.kind == ChatPeriod.Kind.TODAY)?.let { return it }
            }
        }

        // A price for one service: "cutting evlo", "cutting rate 200", "pudhu service hair botox 1500".
        (KaiServices.newServiceName(text) ?: KaiServices.newServiceName(sounded))?.takeIf { KaiServices.serviceNamed(text, prices.keys) == null || Regex("""(?i)pudhu|puthu|new|புது""").containsMatchIn(text) }?.let { name ->
            amountsIn(text).lastOrNull()?.takeIf { it.signum() > 0 }?.let { return priceToKeep(name, it, priceOf(prices, name), lang) }
        }
        // (never the shop's own product — "Horlicks stock evlo" is its stock — nor a stock question: only a service's price)
        val one = (KaiServices.serviceNamed(text, prices.keys) ?: KaiServices.serviceNamed(raw, prices.keys) ?: KaiServices.serviceNamed(sounded, prices.keys))
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
        val serviceShop = runCatching { tools.sellsServices() }.getOrNull() == true
        val sale = KaiServices.saleIn(text, prices, names, serviceShop) ?: KaiServices.saleIn(raw, prices, names, serviceShop)
            ?: KaiServices.saleIn(sounded, prices, names, serviceShop) ?: return null
        return startSale(sale, said, prices, lang)
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

    // ------------------------------------------------------------ a sale: prices → opening → draft → Confirm

    private suspend fun startSale(sale: KaiServices.SaleLine, said: String, prices: Map<String, BigDecimal>, lang: KaiLang): KaiTurn {
        val today = now().toLocalDate()
        if (runCatching { books.cashNote(today) }.getOrNull()?.dayClosed == true) {
            waiting = null
            return turn(lang, KaiMood.CLARIFY, ta = "ஓனர், இன்னைக்கு Daily Sales Note ஏற்கனவே close ஆகிடுச்சு — இனி sale போட முடியாது. எதுவும் சேமிக்கல.",
                tl = "Owner, innaikku Daily Sales Note already close aagiduchu — ini sale poda mudiyaadhu. Edhuvum save pannala.",
                en = "Owner, today's Daily Sales Note is already closed — no more sales can go in. Nothing was saved.")
        }
        val amounts = sale.items.map { i -> i.amount ?: priceOf(prices, i.name)?.multiply(BigDecimal(i.qty)) }
        // A price said with the sale ("oru cutting 150") for a service with none kept: kept with this Confirm.
        val learned = sale.items.filter { it.amount != null && it.qty == 1 && priceOf(prices, it.name) == null }.associate { it.name to it.amount!! }
        val needsOpening = runCatching { tools.cashOpening(today) }.getOrNull() == null
        val d = Draft(java.util.UUID.randomUUID().toString().take(8), sale.items, amounts, sale.upi == true, learned, null, needsOpening, said, lang)
        tools.log("daily_sales", "chat", "sale said: ${sale.items.joinToString { "${it.name} ×${it.qty}" }}", ActionStatus.ANSWERED, null, said)
        return next(d, lang)
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
        else turn(lang, KaiMood.CLARIFY, ta = "ஓனர், $name$per எவ்வளவு? ஒரு தடவை சொன்னா போதும் — அடுத்த தடவை நானே போடுறேன்.",
            tl = "Owner, $name$per evlo? Oru thadava sonna podhum — adutha thadava naane podren.",
            en = "Owner, how much is $name$per? Tell me once — next time I'll fill it in.")
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

    private suspend fun confirmPrice(key: String, lang: KaiLang): KaiTurn? {
        val w = (waiting as? PriceToKeep)?.takeIf { it.key == key } ?: return null
        waiting = null
        keepPrice(w.name, w.price)
        tools.log("daily_sales", "kai memory", "service price kept: ${w.name} ${w.price.toPlainString()}", ActionStatus.CONFIRMED, null, null)
        return turn(lang, KaiMood.HAPPY, ta = "சரி ஓனர் ✅ ${w.name} ${rupees(w.price)}. இனி “ஒரு ${w.name}”-னு சொன்னா நேரா draft காட்டுறேன்.",
            tl = "Seri Owner ✅ ${w.name} ${rupees(w.price)}. Ini “oru ${w.name}”-nu sonna nera draft kaatren.",
            en = "Done Owner ✅ ${w.name} at ${rupees(w.price)}. Say “one ${w.name}” and I'll show the draft straight away.")
    }

    private fun rateListTurn(prices: Map<String, BigDecimal>, lang: KaiLang): KaiTurn =
        if (prices.isEmpty()) turn(lang, KaiMood.NEUTRAL, ta = "ஓனர், இன்னும் எந்த service விலையும் சொல்லல. “ஒரு hair cutting”-னு sale சொல்லுங்க — விலை கேட்டுக்கிறேன்.",
            tl = "Owner, innum endha service vilaiyum sollala. “Oru hair cutting”-nu sale sollunga — vilai kettukkiren.",
            en = "Owner, no service prices yet. Tell me a sale like “one hair cutting” — I'll ask the price.")
        else {
            val list = prices.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${rupees(it.value)}" }
            turn(lang, KaiMood.EXPLAINING, ta = "ஓனர், உங்க விலை: $list.", tl = "Owner, unga vilai: $list.", en = "Owner, your prices: $list.",
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
            else -> day.format(DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH))
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
