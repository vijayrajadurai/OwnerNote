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
 * Many owners, each with their own customers, suppliers and products, each saying 100 lines the way shop owners talk
 * (Tanglish, Tamil-script names, English; "innaiku / ippo / anna / pa / da"; 2000, 2,000, ₹2000, 2k, "rendu aayiram").
 * Every line is played on a fresh copy of that owner's shop and checked in the books / stock — never on Kai's words alone:
 * no wrong entry, no "puriyala", nothing saved before Confirm. A same-name record or "1 piece-aa, 1 box-aa?" is a correct
 * question. 100 owners (10,000 lines) by default; KAI_OWNERS=1000 plays 1,00,000 lines (owner's request, 9 Oct 2026).
 */
class KaiThousandOwnersTest {
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

    private fun linesFor(user: Int, customers: List<Party>, suppliers: List<Party>, prods: List<Prod>, dup: String?, r: Random): List<Line> {
        val out = mutableListOf<Line>()
        val style = r.nextInt(10) // 0..6 Tanglish, 7 Tamil-name, 8 English, 9 mixed fillers
        fun pre() = if (style == 9) listOf("", "innaiku ", "ippo ", "owner ", "anna ").random(r) else if (r.nextInt(6) == 0) listOf("innaiku ", "ippo ").random(r) else ""
        fun suf() = if (r.nextInt(5) == 0) listOf(" pa", " da", " sir", ".", " cash", " ok").random(r) else ""
        repeat(100) {
            val c = customers.random(r)
            val s = suppliers.random(r)
            val amt = listOf(500, 1000, 1500, 2000, 2500, 3000, 5000, 750).random(r)
            val (af, a) = amountForms(amt, r)
            val cname = if (style == 7 && c.name in tamilNames) tamilNames[c.name]!! else c.name
            when (r.nextInt(12)) {
                0, 1 -> { // payment in
                    val v = inVerbs.random(r)
                    val order = r.nextInt(3)
                    val t = when (order) { 0 -> "$cname $a $v"; 1 -> "$cname $v $a"; else -> "$cname kitta irundhu $a vandhuchu" }
                    val text = if (style == 8) listOf("$cname paid $a", "Received $a from $cname", "$cname gave me $a", "Got $a from $cname").random(r) else pre() + t + suf()
                    out += Line("pay-in", "verb=${if (order == 2) "kitta irundhu" else v} amt=$af", text, Expect.Entry(PlanKind.PAYMENT_IN, c.name, amt))
                }
                2 -> { // payment out
                    val v = outVerbs.random(r)
                    val sep = listOf(" ku ", "-ku ", " ku naan ", " kku ").random(r)
                    val text = if (style == 8) listOf("Paid $a to ${s.name}", "I paid ${s.name} $a", "Sent $a to ${s.name}").random(r) else pre() + "${s.name}$sep$a $v" + suf()
                    out += Line("pay-out", "verb=$v amt=$af", text, Expect.Entry(PlanKind.PAYMENT_OUT, s.name, amt))
                }
                3 -> { // credit to customer
                    val t = listOf("$cname ku $a credit kuduthen", "$cname-ku $a kadan kuduthen", "$cname $a credit", "$cname enakku $a tharanum",
                        "$cname enaku $a tharanum", "$cname $a baaki", "$cname ku $a udhaar", "$cname ku $a ku saamaan kuduthen credit la").random(r)
                    out += Line("credit", "form=${t.replace(cname, "N").replace(a, "A")}", pre() + t, Expect.Entry(PlanKind.CREDIT_GIVEN, c.name, amt))
                }
                4 -> { // owner owes supplier
                    val t = listOf("${s.name} ku $a kudukkanum", "naan ${s.name} ku $a tharanum", "${s.name} kitta $a ku credit la maal vaanginen",
                        "${s.name} ku $a baaki kudukkanum").random(r)
                    out += Line("debit", "form=${t.replace(s.name, "S").replace(a, "A")}", pre() + t, Expect.Entry(PlanKind.DEBIT_TAKEN, s.name, amt))
                }
                5 -> { // new customer credit
                    val n = newPool.random(r)
                    val t = listOf("$n ku $a credit kuduthen", "$n enakku $a tharanum", "$n ku $a kadan").random(r)
                    out += Line("new-credit", "form=${t.replace(n, "N").replace(a, "A")}", t, Expect.Entry(PlanKind.CREDIT_GIVEN, n, amt))
                }
                6 -> { // stock in
                    val p = prods.random(r)
                    val v = stockInVerbs.random(r)
                    val useBox = p.box != null && r.nextBoolean()
                    val q = if (useBox) r.nextInt(1, 5) else r.nextInt(2, 30)
                    val unitWord = when { useBox -> p.box!!.first; p.unit == "KG" -> "kg"; p.unit == "BOTTLE" -> "bottle"; p.unit == "BUCKET" -> "bucket"; else -> listOf("pcs", "pieces", "").random(r) }
                    val add = if (useBox) q * p.box!!.second else q
                    val t = if (r.nextInt(4) == 0) "$q $unitWord ${p.name} $v" else "${p.name} $q $unitWord $v"
                    out += Line("stock-in", "verb=$v unit=${if (useBox) "box" else unitWord.ifEmpty { "none" }}", t.replace(Regex("\\s+"), " "), Expect.Stock(p.name, p.stock + add))
                }
                7 -> { // stock out
                    val p = prods.random(r)
                    val v = stockOutVerbs.random(r)
                    val q = r.nextInt(1, minOf(10, p.stock))
                    val unitWord = when (p.unit) { "KG" -> "kg"; "BOTTLE" -> "bottle"; "BUCKET" -> "bucket"; else -> listOf("pcs", "").random(r) }
                    out += Line("stock-out", "verb=$v", "${p.name} $q $unitWord $v".replace(Regex("\\s+"), " "), Expect.Stock(p.name, p.stock - q))
                }
                8 -> { // balance question
                    val q = balanceQ.random(r)
                    out += Line("ask-balance", "q=$q", "$cname $q" + (if (r.nextBoolean()) "?" else ""), Expect.Ask(listOf(KaiFmt.r(c.pending))))
                }
                9 -> { // stock question
                    val p = prods.random(r)
                    val q = stockQ.random(r)
                    out += Line("ask-stock", "q=$q", "${p.name} $q", Expect.Ask(listOf("${p.stock}")))
                }
                10 -> { // list questions
                    val (q, has) = listOf(
                        "Innaiku yar yar tharanum" to listOf(customers.first().name), "innaiku yaar payment tharanum" to listOf(customers.first().name),
                        "innaiku collection evlo" to listOf(customers.first().name), "today who has to pay" to listOf(customers.first().name),
                        "innaiku yaarukku kudukkanum" to listOf(suppliers.first().name), "Pending list sollu" to listOf(customers.last().name),
                        "Overdue list" to listOf(customers[2].name), "mothama evlo varanum" to listOf(KaiFmt.r(customers.fold(BigDecimal.ZERO) { t, p -> t + p.pending } + if (dup != null) BigDecimal(700) else BigDecimal.ZERO)),
                        "low stock enna" to listOf(prods.last().name), "yentha stock fast move aguthu" to listOf(prods.first().name),
                    ).random(r)
                    out += Line("ask-list", "q=$q", q, Expect.Ask(has))
                }
                else -> { // same name
                    if (dup != null) out += Line("same-name", "dup", "$dup $a kuduthan", Expect.Which(dup))
                    else out += Line("ask-balance", "q=evlo tharanum", "$cname evlo tharanum", Expect.Ask(listOf(KaiFmt.r(c.pending))))
                }
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
                        Regex("(?i)due date|eppo tharuvaanga|eppa tharuvaanga").containsMatchIn(txt) && txt.trim().endsWith("?") -> say("due venam")
                        txt.contains("illa pudhu") || txt.contains("ஏற்கனவே") -> say("pudhu")
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
                        Regex("1 piece-aa, 1 (box|bag)-aa").containsMatchIn(txt) -> return Out.CLARIFY_OK to ""
                        txt.contains("pieces-aa") || txt.contains("pieces-ஆ") -> say("pieces")
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
            is Expect.Which -> {
                if (tools.saved.isNotEmpty()) return Out.WRONG to "saved without asking which | $log"
                return if (Regex("(?i)endha|which|எந்த").containsMatchIn(t.reply.text)) Out.OK to "" else Out.MISSED to log.toString()
            }
        }
    }

    @Test fun ownersTalkTheirWay() {
        val users = System.getenv("KAI_OWNERS")?.toIntOrNull() ?: 100
        val stats = linkedMapOf<String, IntArray>()       // cat -> [ok, wrong, asked, missed]
        val variants = linkedMapOf<String, IntArray>()
        val samples = linkedMapOf<String, MutableList<String>>()
        var total = 0
        val start = System.currentTimeMillis()
        for (u in 0 until users) {
            val r = Random(u * 7919L + 13)
            val cn = customerPool.shuffled(r).take(5)
            val dup = if (r.nextInt(5) == 0) cn[4] else null
            val customers = cn.mapIndexed { i, n -> Party("c$i", n, true, if (dup == n) "Chennai" else null, BigDecimal(listOf(4000, 2500, 6000, 1000, 1800)[i]),
                listOf(today, today, today.minusDays(18), today.plusDays(4), today.plusDays(9))[i]) }.toMutableList()
            if (dup != null) customers += Party("c9", dup, true, "Madurai", BigDecimal(700), today.plusDays(7))
            val suppliers = supplierPool.shuffled(r).take(2).mapIndexed { i, n -> Party("s$i", n, false, null, BigDecimal(listOf(10000, 4000)[i]), listOf(today, today.plusDays(7))[i]) }
            val prods = prodPool.shuffled(r).take(5)
            val all = customers + suppliers
            for (line in linesFor(u, customers.filter { it.id != "c9" }, suppliers, prods, dup, r)) {
                total++
                val (out, why) = runCatching { play(line, all, prods) }.getOrElse { Out.WRONG to "CRASH ${it::class.simpleName}: ${it.message}" }
                stats.getOrPut(line.cat) { IntArray(5) }[out.ordinal]++
                variants.getOrPut("${line.cat} | ${line.variant}") { IntArray(5) }[out.ordinal]++
                if (out != Out.OK && out != Out.CLARIFY_OK) samples.getOrPut("${line.cat} ${out}") { mutableListOf() }.let { if (it.size < 12) it += "[${line.text}] $why" }
            }
        }
        val sb = StringBuilder("users=$users lines=$total time=${(System.currentTimeMillis() - start) / 1000}s\n\nCATEGORY  ok / wrong / asked / missed / clarify-ok\n")
        stats.forEach { (c, a) -> sb.append("%-12s %6d %6d %6d %6d %6d  (%.1f%% ok)\n".format(c, a[0], a[1], a[2], a[3], a[4], 100.0 * (a[0] + a[4]) / a.sum())) }
        val t = stats.values.fold(IntArray(5)) { acc, a -> IntArray(5) { acc[it] + a[it] } }
        sb.append("%-12s %6d %6d %6d %6d %6d  (%.1f%% ok)\n".format("ALL", t[0], t[1], t[2], t[3], t[4], 100.0 * (t[0] + t[4]) / t.sum()))
        sb.append("\nWORST VARIANTS (min 20 lines)\n")
        variants.filter { it.value.sum() >= 20 }.entries.sortedBy { (it.value[0] + it.value[4]).toDouble() / it.value.sum() }.take(60)
            .forEach { (v, a) -> sb.append("%5.1f%% ok  n=%-5d w=%-4d a=%-4d m=%-5d %s\n".format(100.0 * (a[0] + a[4]) / a.sum(), a.sum(), a[1], a[2], a[3], v)) }
        sb.append("\nSAMPLES\n")
        samples.forEach { (k, v) -> sb.append("\n== $k\n"); v.forEach { sb.append("  $it\n") } }
        val bad = t[Out.WRONG.ordinal] + t[Out.ASKED.ordinal] + t[Out.MISSED.ordinal]
        assertTrue("$bad of $total owner lines not understood:\n$sb", bad == 0)
    }
}
