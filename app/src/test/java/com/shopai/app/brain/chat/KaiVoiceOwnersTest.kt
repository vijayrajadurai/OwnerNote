package com.shopai.app.brain.chat

import com.shopai.app.books.model.PaymentMode
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.brain.tools.ActionOutcome
import com.shopai.app.brain.tools.ActionPlan
import com.shopai.app.brain.tools.KaiReminder
import com.shopai.app.brain.tools.KaiTools
import com.shopai.app.brain.tools.PartyMatch
import com.shopai.app.brain.tools.PlanKind
import com.shopai.app.brain.tools.ProductSalesFact
import com.shopai.app.brain.tools.ReminderSaved
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Kai Chat's mic: the phone's ta-IN speech-to-text writes what the owner says in Tamil script — Tamil words and English
 * loan words alike ("குமார் 2000 ஜிபே பண்ணிட்டான்", "ஏபிசி டிரேடர்ஸுக்கு 3000 குடுத்தேன்", "ரைஸ் 2 மூட்டை வந்துச்சு"), names
 * in Tamil letters while the shop keeps them in English. Spoken, every line must do what the same line typed in Tanglish
 * does: checked in the books / stock / reminders, never on Kai's words alone. Before this test voice-style lines were
 * right 46% of the time while typed ones were 100% (9 Oct 2026). 100 owners (10,000 lines) by default;
 * KAI_OWNERS=1000 plays 1,00,000.
 */
class KaiVoiceOwnersTest {
    private val now = LocalDateTime.of(2026, 10, 8, 11, 0)
    private val today = now.toLocalDate()

    class Party(val id: String, val name: String, val customer: Boolean, val city: String?, var pending: BigDecimal, val due: LocalDate?)
    class Prod(val name: String, val unit: String, val box: Pair<String, Int>?, val stock: Int)

    private class Books(val parties: List<Party>) : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = parties.filter { it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), it.due?.let { d -> "${d}T00:00:00Z" }) },
            suppliers = parties.filter { !it.customer }.map { PartySummary(it.id, it.name, null, it.pending.toDouble(), it.due?.let { d -> "${d}T00:00:00Z" }) })
        override suspend fun history(party: PartyFacts): PartyHistory? = parties.firstOrNull { it.id == party.id }?.let {
            PartyHistory(party, listOf(PartyHistory.LedgerEntry(it.pending.toDouble(), 0.0, it.due, it.due?.minusDays(10), emptyList())))
        }
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private class Tools(val inv: Inventory, val parties: MutableList<Party>) : KaiTools by inv {
        val saved = mutableListOf<ActionPlan>()
        var n = 0
        val rems = mutableListOf<KaiReminder>()
        override suspend fun parties(name: String) = parties.filter { it.name.contains(name, true) }
            .map { PartyMatch(it.id, it.name, it.customer, null, it.pending, city = it.city) }
        override suspend fun prepare(kind: PlanKind, partyName: String, partyId: String?, amount: BigDecimal, mode: PaymentMode, said: String) =
            ActionPlan("p${n++}", kind, partyName, partyId, amount, mode, balanceBefore = parties.firstOrNull { it.id == partyId }?.pending, said = said)
        override suspend fun confirm(plan: ActionPlan): ActionOutcome { saved += plan; return ActionOutcome.Done("T${saved.size}", BigDecimal.ZERO) }
        override fun createReminder(reminder: KaiReminder): ReminderSaved { rems += reminder; return ReminderSaved(reminder, false, ScheduleResult.EXACT) }
        override fun zone() = "Asia/Kolkata"
        override suspend fun lowStock() = inv.items.filter { it.stock.signum() <= 0 || it.minimum?.let { m -> it.stock <= m } == true }.map { com.shopai.app.brain.tools.StockFact(it.name, it.stock, it.unit, it.minimum) }
        override suspend fun topProducts(from: LocalDate, to: LocalDate, limit: Int) =
            inv.items.take(2).map { ProductSalesFact(it.name, BigDecimal(10), BigDecimal(500)) }
    }

    // ------------------------------------------------------------------ the pools

    private val customerPool = listOf("Kumar", "Ramesh", "Selvam", "Lakshmi", "Senthil", "Karthik", "Priya", "Divya", "Anbu", "Bala", "Saravanan",
        "Mani", "Raja", "Vijay", "Arun", "Prakash", "Suresh", "Gopal", "Meena", "Kavitha", "Rajesh", "Dinesh", "Muthu", "Velu", "Ganesh")
    private val supplierPool = listOf("ABC Traders", "Sri Balaji Agencies", "KPN Distributors", "Velan Stores", "Annai Traders", "SKM Agencies", "Royal Distributors")
    private val newPool = listOf("Ravi", "Siva", "Mohan", "Jaya", "Kannan", "Pandi", "Sundar", "Revathi")
    private val prodPool = listOf(
        Prod("Colgate", "PCS", "box" to 48, 240), Prod("Rice", "KG", "bag" to 25, 250), Prod("Oil", "BOTTLE", "box" to 12, 120),
        Prod("Lux Soap", "PCS", "box" to 72, 144), Prod("Sugar", "KG", "bag" to 50, 200), Prod("Biscuit", "PCS", "box" to 24, 96),
        Prod("Shampoo", "PCS", null, 40), Prod("Dal", "KG", null, 60), Prod("Tea Powder", "PCS", null, 30), Prod("Horlicks", "PCS", null, 20),
        Prod("Pipe", "PCS", null, 50), Prod("Paint", "BUCKET", null, 15), Prod("Shirt", "PCS", null, 80), Prod("Bulb", "PCS", "box" to 10, 100))
    private val tamilNames = mapOf("Kumar" to "குமார்", "Ramesh" to "ரமேஷ்", "Selvam" to "செல்வம்", "Lakshmi" to "லட்சுமி", "Murugan" to "முருகன்")

    data class Line(val cat: String, val variant: String, val text: String, val expect: Expect)
    sealed interface Expect {
        data class Entry(val kind: PlanKind, val party: String, val amount: Int) : Expect
        data class Stock(val product: String, val after: Int) : Expect
        data class Ask(val has: List<String>) : Expect
        data class Which(val name: String) : Expect
        data class Remind(val at: LocalDateTime, val has: String) : Expect
        data class AmountAsk(val party: String) : Expect
    }

    private fun amountForms(a: Int, r: Random): Pair<String, String> {
        val forms = mutableListOf("plain" to "$a", "rs" to "$a rs", "rupee" to "₹$a", "rupees" to "$a rupees", "ruba" to "$a ruba")
        if (a >= 1000) forms += "comma" to "%,d".format(a)
        if (a % 1000 == 0) forms += "k" to "${a / 1000}k"
        val words = mapOf(500 to "ainooru", 1000 to "aayiram", 2000 to "rendu aayiram", 3000 to "moonu aayiram", 5000 to "anju aayiram")
        words[a]?.let { forms += "tamilnum" to it }
        return forms[r.nextInt(forms.size)]
    }

    private val inVerbs = listOf("kuduthan", "kuduthaan", "kuduthutan", "kuduthuttaan", "kuduthaaru", "kuduthanga", "thandhaan", "thanthan", "anuppinan",
        "anuppitaan", "gpay pannitan", "gpay la anuppinan", "pay pannitaan", "katti tan", "kattinan", "settle pannitaan", "vandhuchu", "kuduthar", "koduthan")
    private val outVerbs = listOf("kuduthen", "koduthen", "kuduthuten", "anuppinen", "anuppiten", "pay panniten", "gpay pannen", "kattinen", "settle panniten",
        "kuduthachu", "pay pannen")
    private val stockInVerbs = listOf("vandhuchu", "vanthuchu", "vandhirukku", "vanthiruku", "add pannu", "add panniten", "stock la podu", "vandhadhu", "irakkinen", "serthu")
    private val stockOutVerbs = listOf("vithuchu", "sale aachu", "pochu", "poiduchu", "vithuten", "sold", "out pannu", "damage", "udanjiduchu")
    private val balanceQ = listOf("evlo tharanum", "balance enna", "evlo baaki", "pending evlo", "kitta evlo varanum", "evlo kudukkanum enakku", "baaki evlo irukku", "account evlo")
    private val stockQ = listOf("stock evlo", "evlo irukku", "stock evlo irukku", "iruppu evlo", "kaila evlo irukku")


    // What the phone's ta-IN speech-to-text writes: Tamil words AND English loan words in Tamil script, numbers mostly as digits.
    private val ta = mapOf("Kumar" to "குமார்", "Ramesh" to "ரமேஷ்", "Selvam" to "செல்வம்", "Lakshmi" to "லட்சுமி", "Senthil" to "செந்தில்",
        "Karthik" to "கார்த்திக்", "Priya" to "பிரியா", "Divya" to "திவ்யா", "Anbu" to "அன்பு", "Bala" to "பாலா", "Saravanan" to "சரவணன்",
        "Murugan" to "முருகன்", "Ganesh" to "கணேஷ்", "Vijay" to "விஜய்", "Arun" to "அருண்", "Mani" to "மணி", "Raja" to "ராஜா", "Suresh" to "சுரேஷ்")
    private val taSup = mapOf("Velan Stores" to "வேலன் ஸ்டோர்ஸ்", "Annai Traders" to "அன்னை டிரேடர்ஸ்", "ABC Traders" to "ஏபிசி டிரேடர்ஸ்",
        "Sri Balaji Agencies" to "ஸ்ரீ பாலாஜி ஏஜென்சீஸ்", "KPN Distributors" to "கேபிஎன் டிஸ்ட்ரிபியூட்டர்ஸ்")
    private val taProd = mapOf("Colgate" to listOf("கோல்கேட்"), "Rice" to listOf("ரைஸ்", "அரிசி"), "Oil" to listOf("ஆயில்", "எண்ணெய்"),
        "Lux Soap" to listOf("லக்ஸ் சோப்", "லக்ஸ் சோப்பு"), "Sugar" to listOf("சுகர்", "சர்க்கரை", "சீனி"), "Biscuit" to listOf("பிஸ்கட்"),
        "Shampoo" to listOf("ஷாம்பு"), "Dal" to listOf("பருப்பு", "டால்"), "Tea Powder" to listOf("டீ பவுடர்", "டீ தூள்"), "Horlicks" to listOf("ஹார்லிக்ஸ்"),
        "Pipe" to listOf("பைப்"), "Paint" to listOf("பெயிண்ட்"), "Shirt" to listOf("ஷர்ட்", "சட்டை"), "Bulb" to listOf("பல்பு", "பல்ப்"))
    private fun taDative(n: String) = when { n.endsWith("்") -> n.dropLast(1) + "ுக்கு"; n.endsWith("ா") || n.endsWith("ி") || n.endsWith("ு") || n.endsWith("ை") -> n + "க்கு"; else -> n + "க்கு" }
    private fun taAmt(a: Int, r: Random): Pair<String, String> {
        val forms = mutableListOf("digits" to "$a", "rubai" to "$a ரூபாய்", "ruba" to "$a ரூபா", "symbol" to "₹$a")
        mapOf(500 to "ஐநூறு", 1000 to "ஆயிரம்", 2000 to "ரெண்டாயிரம்", 3000 to "மூவாயிரம்", 5000 to "அஞ்சாயிரம்", 1500 to "ஆயிரத்து ஐநூறு")[a]?.let { forms += "words" to it }
        return forms[r.nextInt(forms.size)]
    }

    private fun linesFor(user: Int, customers: List<Party>, suppliers: List<Party>, prods: List<Prod>, dup: String?, r: Random): List<Line> {
        val out = mutableListOf<Line>()
        repeat(100) {
            val c = customers.random(r)
            val s = suppliers.random(r)
            val cn = ta[c.name] ?: c.name
            val sn = taSup[s.name] ?: s.name
            val amt = listOf(500, 1000, 1500, 2000, 2500, 3000, 5000, 750).random(r)
            val (af, a) = taAmt(amt, r)
            fun pre() = if (r.nextInt(5) == 0) listOf("இன்னைக்கு ", "இப்போ ", "அண்ணா ", "ஓனர் ").random(r) else ""
            when (r.nextInt(14)) {
                0, 1 -> { val v = listOf("குடுத்தான்", "கொடுத்தான்", "குடுத்தாரு", "கொடுத்தார்", "குடுத்தாங்க", "அனுப்பினான்", "அனுப்பிட்டான்", "ஜிபே பண்ணிட்டான்",
                        "பே பண்ணிட்டான்", "கட்டிட்டான்", "கட்டினான்", "செட்டில் பண்ணிட்டான்", "தந்தான்", "குடுத்துட்டான்").random(r)
                    val t = listOf("$cn $a $v", "$cn $v $a", "$cn கிட்ட இருந்து $a வந்துச்சு", "$cn கிட்ட $a வாங்கிட்டேன்").random(r)
                    out += Line("v-pay-in", "form=${t.replace(cn, "N").replace(a, "A")} amt=$af", pre() + t, Expect.Entry(PlanKind.PAYMENT_IN, c.name, amt)) }
                2 -> { val v = listOf("குடுத்தேன்", "கொடுத்தேன்", "அனுப்பினேன்", "அனுப்பிட்டேன்", "பே பண்ணேன்", "ஜிபே பண்ணேன்", "கட்டினேன்", "செட்டில் பண்ணிட்டேன்", "குடுத்துட்டேன்").random(r)
                    val t = listOf("${taDative(sn)} $a $v", "$sn க்கு $a $v").random(r)
                    out += Line("v-pay-out", "form=${t.replace(sn, "S").replace(taDative(sn), "S-ku").replace(a, "A")}", pre() + t, Expect.Entry(PlanKind.PAYMENT_OUT, s.name, amt)) }
                3 -> { val t = listOf("$cn எனக்கு $a தரணும்", "$cn $a தரணும்", "$cn $a கடன்", "${taDative(cn)} $a கடன் குடுத்தேன்", "$cn $a கிரெடிட்",
                        "${taDative(cn)} $a கிரெடிட் குடுத்தேன்", "$cn $a பாக்கி").random(r)
                    out += Line("v-credit", "form=${t.replace(taDative(cn), "N-ku").replace(cn, "N").replace(a, "A")}", pre() + t, Expect.Entry(PlanKind.CREDIT_GIVEN, c.name, amt)) }
                4 -> { val t = listOf("நான் ${taDative(sn)} $a தரணும்", "${taDative(sn)} $a குடுக்கணும்", "${taDative(sn)} $a கொடுக்கணும்").random(r)
                    out += Line("v-debit", "form=${t.replace(taDative(sn), "S-ku").replace(a, "A")}", t, Expect.Entry(PlanKind.DEBIT_TAKEN, s.name, amt)) }
                5 -> { val p = prods.random(r); val pn = taProd[p.name]!!.random(r)
                    val v = listOf("வந்துச்சு", "வந்திருக்கு", "ஆட் பண்ணு", "சேர்த்துக்கோ", "ஸ்டாக்ல சேர்").random(r)
                    val useBox = p.box != null && r.nextBoolean(); val q = if (useBox) r.nextInt(1, 5) else r.nextInt(2, 30)
                    val unit = when { useBox -> mapOf("box" to "பாக்ஸ்", "bag" to "மூட்டை")[p.box!!.first]!!; p.unit == "KG" -> "கிலோ"; p.unit == "BOTTLE" -> "பாட்டில்"; else -> "" }
                    val t = "$pn $q $unit $v".replace(Regex("\\s+"), " ")
                    out += Line("v-stock-in", "form=${t.replace(pn, "P").replace(Regex("\\d+"), "Q")}", t, Expect.Stock(p.name, p.stock + if (useBox) q * p.box!!.second else q)) }
                6 -> { val p = prods.random(r); val pn = taProd[p.name]!!.random(r); val q = r.nextInt(1, minOf(10, p.stock))
                    val v = listOf("வித்துச்சு", "சேல் ஆச்சு", "போச்சு", "வித்துட்டேன்", "டேமேஜ் ஆயிடுச்சு", "உடைஞ்சிடுச்சு").random(r)
                    val unit = when (p.unit) { "KG" -> "கிலோ"; "BOTTLE" -> "பாட்டில்"; else -> "" }
                    val t = "$pn $q $unit $v".replace(Regex("\\s+"), " ")
                    out += Line("v-stock-out", "verb=$v", t, Expect.Stock(p.name, p.stock - q)) }
                7 -> { val q = listOf("எவ்வளவு தரணும்", "பாக்கி எவ்வளவு", "பேலன்ஸ் என்ன", "கிட்ட எவ்வளவு வரணும்", "எவ்ளோ தரணும்", "பெண்டிங் எவ்வளவு").random(r)
                    out += Line("v-ask-balance", "q=$q", "$cn $q", Expect.Ask(listOf(KaiFmt.r(c.pending)))) }
                8 -> { val p = prods.random(r); val pn = taProd[p.name]!!.random(r)
                    val q = listOf("ஸ்டாக் எவ்வளவு", "எவ்வளவு இருக்கு", "ஸ்டாக் எவ்வளவு இருக்கு", "எவ்ளோ இருக்கு").random(r)
                    out += Line("v-ask-stock", "q=$q", "$pn $q", Expect.Ask(listOf("${p.stock}"))) }
                9 -> { val (q, has) = listOf("இன்னைக்கு யார் யார் தரணும்" to listOf(customers.first().name), "இன்னைக்கு யாரு பேமெண்ட் தரணும்" to listOf(customers.first().name),
                        "இன்னைக்கு யாருக்கு குடுக்கணும்" to listOf(suppliers.first().name), "பெண்டிங் லிஸ்ட் சொல்லு" to listOf(customers.last().name),
                        "மொத்தம் எவ்வளவு வரணும்" to listOf(KaiFmt.r(customers.fold(BigDecimal.ZERO) { t, p -> t + p.pending } + if (dup != null) BigDecimal(700) else BigDecimal.ZERO)),
                        "லோ ஸ்டாக் என்ன" to listOf(prods.last().name)).random(r)
                    out += Line("v-ask-list", "q=$q", q, Expect.Ask(has)) }
                10 -> { val (t, at) = listOf(
                        "நாளைக்கு 10 மணிக்கு ${taDative(cn)} கால் பண்ண ரிமைண்ட் பண்ணு" to LocalDateTime.of(2026, 10, 9, 10, 0),
                        "$cn கிட்ட பேமெண்ட் வாங்க நாளைக்கு காலையில 9 மணிக்கு ரிமைண்ட் பண்ணு" to LocalDateTime.of(2026, 10, 9, 9, 0),
                        "10 நிமிஷம் கழிச்சு ${taDative(cn)} போன் பண்ண ஞாபகப்படுத்து" to now.plusMinutes(10),
                        "சாயங்காலம் 6 மணிக்கு ${taDative(cn)} கால் பண்ணனும் ரிமைண்டர் வை" to LocalDateTime.of(2026, 10, 8, 18, 0)).random(r)
                    out += Line("v-reminder", "form=${t.replace(taDative(cn), "N-ku").replace(cn, "N")}", t, Expect.Remind(at, c.name)) }
                11 -> { val t = listOf("$cn கொஞ்சம் குடுத்தான்", "$cn குடுத்தான்", "$cn பேமெண்ட் பண்ணிட்டான்").random(r)
                    out += Line("v-no-amount", "form=${t.replace(cn, "N")}", t, Expect.AmountAsk(c.name)) }
                12 -> { val (t, kind) = listOf("$cn $a ரூபாய் பே பண்ணாரு" to PlanKind.PAYMENT_IN, "$cn $a ஜிபேல அனுப்பினாரு" to PlanKind.PAYMENT_IN,
                        "${taDative(cn)} $a கடனா குடுத்தேன்" to PlanKind.CREDIT_GIVEN, "$cn $a கிரெடிட்ல வாங்கிட்டு போனான்" to PlanKind.CREDIT_GIVEN).random(r)
                    out += Line("v-mixed", "form=${t.replace(taDative(cn), "N-ku").replace(cn, "N").replace(a, "A")}", t, Expect.Entry(kind, c.name, amt)) }
                else -> { if (dup != null) out += Line("v-same-name", "dup", "${ta[dup] ?: dup} $a குடுத்தான்", Expect.Which(dup))
                    else out += Line("v-ask-balance", "q=எவ்வளவு தரணும்", "$cn எவ்வளவு தரணும்", Expect.Ask(listOf(KaiFmt.r(c.pending)))) }
            }
        }
        return out
    }

    object KaiFmt { fun r(v: BigDecimal) = com.shopai.app.brain.KaiFormat.rupees(v.toDouble()) }

    enum class Out { OK, WRONG, ASKED, MISSED, CLARIFY_OK }

    private fun play(line: Line, parties: List<Party>, prods: List<Prod>): Pair<Out, String> {
        val inv = Inventory()
        prods.forEachIndexed { i, p ->
            inv.items += Inventory.Item("p$i", p.name, p.unit, BigDecimal(p.stock), p.box?.let { mapOf(it.first.uppercase() to BigDecimal(it.second)) } ?: emptyMap(),
                BigDecimal(10), BigDecimal(12), "General", null, if (i == prods.lastIndex) BigDecimal(p.stock + 1) else null)
        }
        val ps = parties.map { Party(it.id, it.name, it.customer, it.city, it.pending, it.due) }.toMutableList()
        val books = Books(ps)
        val tools = Tools(inv, ps)
        val k = KaiAgent(KaiBusinessBrain(books, today = { today }, random = Random(1)), books, tools, now = { now })
        val log = StringBuilder()
        fun say(t: String) = runBlocking { k.ask(t) }.also { log.append(" » $t ⇒ ${it.reply.text.replace("\n", " / ").take(160)}") }
        fun tap(t: KaiTurn, pred: (KaiAction) -> Boolean): KaiTurn? = t.card?.buttons?.firstOrNull { pred(it.action) }?.let { b -> runBlocking { k.act(b.action, KaiLang.TANGLISH) } }
        var t = say(line.text)
        when (val e = line.expect) {
            is Expect.Entry -> {
                repeat(4) {
                    if (tools.saved.isNotEmpty()) return@repeat
                    tap(t) { it is KaiAction.ConfirmPlan }?.let { t = it; return@repeat }
                    val txt = t.reply.text
                    t = when {
                        Regex("(?i)due date|eppo tharuvaanga|eppa tharuvaanga").containsMatchIn(txt) && txt.trim().endsWith("?") -> say("டியூ டேட் வேண்டாம்")
                        txt.contains("illa pudhu") || txt.contains("ஏற்கனவே") -> say("புது")
                        txt.contains("or a new") -> say("new")
                        txt.contains("dhaan-aa") -> tap(t) { true } ?: return Out.ASKED to log.toString()
                        Regex("(?i)endha .+ owner\\?|which .+, owner\\?|எந்த .+ ஓனர்\\?|rendu records|2 records").containsMatchIn(txt) && parties.count { it.name.equals(e.party, true) } > 1 -> return Out.CLARIFY_OK to ""
                        else -> return (if (t.reply.text.trim().endsWith("?") || t.card?.buttons?.isNotEmpty() == true) Out.ASKED else Out.MISSED) to log.toString()
                    }
                }
                val p = tools.saved.singleOrNull() ?: return Out.MISSED to log.toString()
                return if (p.kind == e.kind && p.partyName.equals(e.party, true) && p.amount.compareTo(BigDecimal(e.amount)) == 0) Out.OK to ""
                else Out.WRONG to "saved ${p.kind} ${p.partyName} ${p.amount} | $log"
            }
            is Expect.Stock -> {
                repeat(6) {
                    tap(t) { it is KaiAction.ConfirmStock || it is KaiAction.ConfirmPlan }?.let {
                        val i = inv.items.first { x -> x.name == e.product }
                        return if (i.stock.compareTo(BigDecimal(e.after)) == 0 && inv.items.size == prods.size) Out.OK to ""
                        else Out.WRONG to "${e.product}=${i.stock} items=${inv.items.map { x -> x.name }} | $log"
                    }
                    val txt = t.reply.text
                    t = when {
                        Regex("1 piece-(aa|ஆ), 1 (box|bag)-(aa|ஆ)|\\d+ kg-aa, \\d+ bags-aa").containsMatchIn(txt) -> return Out.CLARIFY_OK to ""
                        txt.contains("pieces-aa") || txt.contains("pieces-ஆ") -> say("பீசஸ்")
                        else -> return (if (txt.trim().endsWith("?") || txt.contains("'skip'")) Out.ASKED else Out.MISSED) to log.toString()
                    }
                }
                return Out.MISSED to log.toString()
            }
            is Expect.Ask -> {
                if (tools.saved.isNotEmpty() || inv.moves.isNotEmpty()) return Out.WRONG to "wrote on a question | $log"
                val all = t.reply.text + " " + t.card?.lines.orEmpty().joinToString(" ")
                return if (e.has.all { all.contains(it, true) }) Out.OK to "" else Out.MISSED to log.toString()
            }
            is Expect.Remind -> {
                if (tools.rems.isNotEmpty()) return Out.WRONG to "set before Confirm | $log"
                val b = tap(t) { it is KaiAction.ConfirmReminder } ?: return Out.MISSED to log.toString()
                val rem = tools.rems.singleOrNull() ?: return Out.MISSED to "not stored | $log"
                val time = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(rem.triggerAt), java.time.ZoneId.of("Asia/Kolkata"))
                return if (time == e.at && (rem.title + rem.task + (rem.person ?: "")).contains(e.has, true)) Out.OK to "" else Out.WRONG to "at $time '${rem.title}' | $log"
            }
            is Expect.AmountAsk -> {
                if (tools.saved.isNotEmpty()) return Out.WRONG to "saved without an amount | $log"
                return if (Regex("(?i)evlo|how much|full|எவ்வளவு|amount").containsMatchIn(t.reply.text)) Out.OK to "" else Out.MISSED to log.toString()
            }
            is Expect.Which -> {
                if (tools.saved.isNotEmpty()) return Out.WRONG to "saved without asking which | $log"
                return if (Regex("(?i)endha|which|எந்த").containsMatchIn(t.reply.text)) Out.OK to "" else Out.MISSED to log.toString()
            }
        }
    }

    @Test fun probe() {
        val users = System.getenv("KAI_OWNERS")?.toIntOrNull() ?: 100
        val stats = linkedMapOf<String, IntArray>()       // cat -> [ok, wrong, asked, missed]
        val variants = linkedMapOf<String, IntArray>()
        val samples = linkedMapOf<String, MutableList<String>>()
        var total = 0
        val start = System.currentTimeMillis()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val lock = Any()
        val from = System.getenv("KAI_FROM")?.toIntOrNull() ?: 0
        val futures = (from until from + users).map { u -> pool.submit {
            val localStats = linkedMapOf<String, IntArray>(); val localVar = linkedMapOf<String, IntArray>(); val localSamples = mutableListOf<Pair<String, String>>(); var localTotal = 0
            run {
            val r = Random(u * 7919L + 13)
            val cn = ta.keys.toList().shuffled(r).take(5)
            val dup = if (r.nextInt(5) == 0) cn[4] else null
            val customers = cn.mapIndexed { i, n -> Party("c$i", n, true, if (dup == n) "Chennai" else null, BigDecimal(listOf(4000, 2500, 6000, 1000, 1800)[i]),
                listOf(today, today, today.minusDays(18), today.plusDays(4), today.plusDays(9))[i]) }.toMutableList()
            if (dup != null) customers += Party("c9", dup, true, "Madurai", BigDecimal(700), today.plusDays(7))
            val suppliers = taSup.keys.toList().shuffled(r).take(2).mapIndexed { i, n -> Party("s$i", n, false, null, BigDecimal(listOf(10000, 4000)[i]), listOf(today, today.plusDays(7))[i]) }
            val prods = prodPool.shuffled(r).take(5)
            val all = customers + suppliers
            for (line in linesFor(u, customers.filter { it.id != "c9" }, suppliers, prods, dup, r)) {
                localTotal++
                val (out, why) = runCatching { play(line, all, prods) }.getOrElse { Out.WRONG to "CRASH ${it::class.simpleName}: ${it.message}" }
                localStats.getOrPut(line.cat) { IntArray(5) }[out.ordinal]++
                localVar.getOrPut("${line.cat} | ${line.variant}") { IntArray(5) }[out.ordinal]++
                if (out != Out.OK && out != Out.CLARIFY_OK) localSamples += "${line.cat} ${out}" to "[${line.text}] $why"
            }
            }
            synchronized(lock) {
                total += localTotal
                localStats.forEach { (k, v) -> val a = stats.getOrPut(k) { IntArray(5) }; for (i in 0..4) a[i] += v[i] }
                localVar.forEach { (k, v) -> val a = variants.getOrPut(k) { IntArray(5) }; for (i in 0..4) a[i] += v[i] }
                localSamples.forEach { (k, v) -> samples.getOrPut(k) { mutableListOf() }.let { if (it.size < 15) it += v } }
            }
        } }
        futures.forEach { it.get() }
        pool.shutdown()
        val sb = StringBuilder("users=$users lines=$total time=${(System.currentTimeMillis() - start) / 1000}s\n\nCATEGORY  ok / wrong / asked / missed / clarify-ok\n")
        stats.forEach { (c, a) -> sb.append("%-12s %6d %6d %6d %6d %6d  (%.1f%% ok)\n".format(c, a[0], a[1], a[2], a[3], a[4], 100.0 * (a[0] + a[4]) / a.sum())) }
        val t = stats.values.fold(IntArray(5)) { acc, a -> IntArray(5) { acc[it] + a[it] } }
        sb.append("%-12s %6d %6d %6d %6d %6d  (%.1f%% ok)\n".format("ALL", t[0], t[1], t[2], t[3], t[4], 100.0 * (t[0] + t[4]) / t.sum()))
        sb.append("\nWORST VARIANTS (min 20 lines)\n")
        variants.filter { it.value.sum() >= 20 }.entries.sortedBy { (it.value[0] + it.value[4]).toDouble() / it.value.sum() }.take(60)
            .forEach { (v, a) -> sb.append("%5.1f%% ok  n=%-5d w=%-4d a=%-4d m=%-5d %s\n".format(100.0 * (a[0] + a[4]) / a.sum(), a.sum(), a[1], a[2], a[3], v)) }
        sb.append("\nSAMPLES\n")
        samples.forEach { (k, v) -> sb.append("\n== $k\n"); v.forEach { sb.append("  $it\n") } }
        System.getenv("KAI_OUT")?.let { java.io.File(it).writeText(sb.toString()) }
        assertTrue(sb.toString(), t[1] + t[2] + t[3] == 0)
    }
}
