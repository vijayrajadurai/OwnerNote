package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import com.shopai.app.data.model.PartySummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Stock by chat, checked the way shop owners actually use it (owner's request, 9 Oct 2026: "100 real owner check,
 * 150 combinations"). Each dialogue is played to the end: Kai's question is read, the owner's answer for THAT
 * question is given, and after Confirm the inventory itself is checked — product, stock unit, quantity, pack size,
 * purchase / selling price per stock unit, category, one opening movement. Nothing is checked on Kai's words alone.
 */
class KaiInventoryOwnersTest {
    private val now = LocalDateTime.of(2026, 10, 9, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(
            customers = listOf(PartySummary("c1", "Ramesh", "9000000001", 1000.0, null)),
            suppliers = listOf(PartySummary("s1", "ABC Traders", null, 0.0, null)),
        )
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** What Kai is asking, read from its reply (Tanglish, Tamil or English). */
    enum class Q { QTY, UNIT, PER_PACK, SIZE, PURCHASE, SELLING, SUMMARY }

    private fun asked(text: String): Q? = when {
        Regex("""Stock add pannattuma|Stock add பண்ணட்டுமா|Shall I add it to stock""").containsMatchIn(text) -> Q.SUMMARY
        Regex("""(?i)purchase rate""").containsMatchIn(text) && text.trim().endsWith("?") -> Q.PURCHASE
        Regex("""(?i)selling rate""").containsMatchIn(text) && text.trim().endsWith("?") -> Q.SELLING
        Regex("""(?i)gram\?|ml\?|size|model|litre / ml|grams is|litres / ml""").containsMatchIn(text) -> Q.SIZE
        Regex("""-aa Owner\?|-ஆ Owner\?| or .+, Owner\?""").containsMatchIn(text) && !Regex("""(?i)credit""").containsMatchIn(text) -> Q.UNIT
        Regex("""👍 1 .+(evlo|எத்தனை)|How many .+ in 1""").containsMatchIn(text) -> Q.PER_PACK
        Regex("""evlo vandhirukku|எவ்வளவு வந்திருக்கு|came in\?""").containsMatchIn(text) -> Q.QTY
        else -> null
    }

    /** The product as it must be in the inventory after Confirm (prices per stock unit). */
    data class Exp(
        val name: String, val unit: String, val stock: String, val pack: Pair<String, String>? = null,
        val purchase: String? = null, val selling: String? = null, val category: String,
    )

    /** One later message about stock already there: optional follow-ups, Confirm tapped, the stock it must leave. */
    data class St(val text: String, val follow: List<String> = emptyList(), val tap: Boolean = true, val item: String? = null,
                  val stock: String? = null, val has: String? = null, val balance: Pair<String, String>? = null)

    data class Owner(
        val shop: String, val first: String, val answers: Map<Q, String> = emptyMap(), val exp: Exp?,
        val pre: List<String> = emptyList(), val confirm: List<String> = listOf("aama"), val stocked: Boolean = false,
        val failCreate: Boolean = false, val then: List<St> = emptyList(),
    )

    private fun stock(shop: Inventory) {
        shop.items += Inventory.Item("p1", "Colgate", "PCS", BigDecimal("240"), mapOf("BOX" to BigDecimal("48")), BigDecimal("28"), BigDecimal("35"), "FMCG", "200 g")
        shop.items += Inventory.Item("p2", "Rice", "KG", BigDecimal("250"), mapOf("BAG" to BigDecimal("25")), BigDecimal("56"), BigDecimal("65"), "Grocery", null)
        shop.items += Inventory.Item("p3", "Oil", "BOTTLE", BigDecimal("240"), mapOf("BOX" to BigDecimal("12")), null, null, "Liquids", "1 litre")
    }

    private fun near(a: BigDecimal?, b: String) = a != null && a.compareTo(BigDecimal(b)) == 0

    /** Plays one owner's dialogue; returns what went wrong (with the conversation), or null when everything is right. */
    private fun play(o: Owner): String? {
        val shop = Inventory()
        if (o.stocked) stock(shop)
        shop.failCreate = o.failCreate
        val before = shop.items.map { it.name }.toSet()
        val k = KaiAgent(KaiBusinessBrain(Books()), Books(), shop, { now })
        val log = StringBuilder()
        // The language the owner speaks in (Kai's buttons answer in it).
        val lang = when (o.confirm.lastOrNull()) { "ஆமா", "சரி" -> KaiLang.TAMIL; "yes" -> KaiLang.ENGLISH; else -> KaiLang.TANGLISH }
        fun say(t: String): KaiTurn = runBlocking { k.ask(t) }.also { log.append("\n   » $t\n     ${it.reply.text}") }
        fun fail(why: String) = "[${o.shop}] ${o.first} — $why$log"

        var t = say(o.first)
        if (t.direct != null) return fail("opened ${t.direct} instead of asking")
        o.pre.forEach { t = say(it) }
        val confirms = o.confirm.toMutableList()
        var turns = 0
        while (turns++ < 12) {
            val q = asked(t.reply.text) ?: break
            if (q != Q.SUMMARY && (shop.items.map { it.name }.toSet() != before || shop.moves.isNotEmpty())) return fail("saved before Confirm")
            t = when (q) {
                Q.SUMMARY -> if (confirms.isEmpty()) break else say(confirms.removeAt(0))
                else -> say(o.answers[q] ?: if (q == Q.SIZE) "skip" else return fail("asked $q, owner has no answer"))
            }
        }
        // A movement on stock already there is a draft with a Confirm button: the owner taps it (or says yes).
        if (o.stocked && confirms.isNotEmpty() && asked(t.reply.text) == null) {
            val b = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }
            t = if (b != null) runBlocking { k.act(b.action, lang) }!!.also { log.append("\n   [Confirm] ${it.reply.text}") } else say(confirms.removeAt(0))
        }
        val done = t
        val added = shop.items.filter { it.name !in before }
        if (o.exp == null) {
            if (added.isNotEmpty() || shop.moves.isNotEmpty()) return fail("saved although it must not: ${added.map { it.name }} ${shop.moves}")
            if (done.reply.text.contains("✅")) return fail("claimed a save that did not happen")
        } else {
            val e = o.exp
            val i = shop.items.firstOrNull { it.name.equals(e.name, ignoreCase = true) } ?: return fail("'${e.name}' not saved; items ${shop.items.map { it.name }}")
            if (i.unit != e.unit) return fail("unit ${i.unit} ≠ ${e.unit}")
            if (!near(i.stock, e.stock)) return fail("stock ${i.stock} ≠ ${e.stock}")
            if (e.pack != null && !near(i.conversions[e.pack.first], e.pack.second)) return fail("pack ${i.conversions} ≠ ${e.pack}")
            if (e.purchase != null && !near(i.purchase, e.purchase)) return fail("purchase ${i.purchase} ≠ ${e.purchase}")
            if (e.selling != null && !near(i.selling, e.selling)) return fail("selling ${i.selling} ≠ ${e.selling}")
            if (i.category != e.category) return fail("category ${i.category} ≠ ${e.category}")
            if (i.name !in before) {
                val opening = shop.moves.filter { it.itemId == i.id && it.type == "OPENING" }
                if (opening.size != 1) return fail("opening movements $opening")
                if (!done.reply.text.contains("✅")) return fail("no saved reply")
            }
            // Saying yes again must not save twice.
            val moves = shop.moves.size
            say(o.confirm.last())
            if (shop.moves.size != moves || shop.items.count { it.name.equals(e.name, true) } != 1) return fail("a second yes saved again")
        }
        for (s in o.then) {
            var st = say(s.text)
            s.follow.forEach { st = say(it) }
            if (s.tap) {
                val b = st.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }
                    ?: return fail("'${s.text}': no Confirm")
                runBlocking { k.act(b.action, lang) }.also { log.append("\n   [Confirm] ${it?.reply?.text}") }
            }
            if (s.has != null && !st.reply.text.contains(s.has)) return fail("'${s.text}': reply lacks '${s.has}'")
            if (s.stock != null) {
                val name = s.item ?: o.exp?.name ?: return fail("no item")
                val i = shop.items.firstOrNull { it.name.equals(name, true) } ?: return fail("'$name' missing")
                if (!near(i.stock, s.stock)) return fail("'${s.text}': stock ${i.stock} ≠ ${s.stock}")
            }
            if (s.balance != null && !near(shop.parties.single { it.name == s.balance.first }.balance, s.balance.second))
                return fail("'${s.text}': ${s.balance.first} balance ${shop.parties.single { it.name == s.balance.first }.balance}")
        }
        return null
    }

    private fun a(vararg p: Pair<Q, String>) = mapOf(*p)
    private val G = "Grocery"; private val F = "FMCG"; private val B = "Beverages"; private val L = "Liquids"; private val W = "Garments"
    private val FW = "Footwear"; private val H = "Hardware"; private val E = "Electronics"; private val GN = "General"

    // ------------------------------------------------------------------ 100 owners

    private val owners: List<Owner> = listOf(
        // Kirana / provision stores — grocery by the bag and by the kg
        Owner("kirana", "arisi moota 50 add pannu", a(Q.PER_PACK to "25 kg", Q.PURCHASE to "1400", Q.SELLING to "65"),
            Exp("Arisi", "KG", "1250", "BAG" to "25", "56", "65", G),
            then = listOf(St("arisi 2 moota vandhuchu", stock = "1300"), St("arisi evlo irukku?", tap = false, has = "1300"))),
        Owner("kirana", "Ponni arisi 10 moota vandhuchu", a(Q.PER_PACK to "26", Q.PURCHASE to "1560", Q.SELLING to "68"),
            Exp("Ponni Arisi", "KG", "260", "BAG" to "26", "60", "68", G)),
        Owner("kirana", "Sugar 5 bag add pannu", a(Q.PER_PACK to "50 kg", Q.PURCHASE to "2000", Q.SELLING to "45"),
            Exp("Sugar", "KG", "250", "BAG" to "50", "40", "45", G), then = listOf(St("Sugar 3 kg sale", stock = "247"))),
        Owner("kirana", "Toor dal 20 kg vandhirukku", a(Q.PURCHASE to "120", Q.SELLING to "140"), Exp("Toor Dal", "KG", "20", null, "120", "140", G)),
        Owner("kirana", "Uppu 10 bag add pannu", a(Q.PER_PACK to "25", Q.PURCHASE to "250", Q.SELLING to "12"), Exp("Uppu", "KG", "250", "BAG" to "25", "10", "12", G)),
        Owner("kirana", "அரிசி 10 மூட்டை சேர்த்துடு", a(Q.PER_PACK to "25", Q.PURCHASE to "1400", Q.SELLING to "65"),
            Exp("அரிசி", "KG", "250", "BAG" to "25", "56", "65", G), confirm = listOf("ஆமா")),
        Owner("kirana", "Wheat flour 30 kg add pannu", a(Q.PURCHASE to "38", Q.SELLING to "45"), Exp("Wheat Flour", "KG", "30", null, "38", "45", G)),
        Owner("kirana", "Rava 4 bag, 25 kg each, purchase 1000 per bag, selling 48", exp = Exp("Rava", "KG", "100", "BAG" to "25", "40", "48", G)),
        Owner("kirana", "Maida 2 moota vandhuchu", a(Q.PER_PACK to "50", Q.PURCHASE to "1800", Q.SELLING to "42"), Exp("Maida", "KG", "100", "BAG" to "50", "36", "42", G)),
        Owner("kirana", "Kadalai paruppu 15 kg vandhuchu", a(Q.PURCHASE to "95", Q.SELLING to "110"), Exp("Kadalai Paruppu", "KG", "15", null, "95", "110", G)),
        Owner("kirana", "Atta 10 bag add pannu", a(Q.PER_PACK to "10 kg", Q.PURCHASE to "420", Q.SELLING to "48"), Exp("Atta", "KG", "100", "BAG" to "10", "42", "48", G)),
        Owner("kirana", "Ragi 25 kg add pannu", a(Q.PURCHASE to "40", Q.SELLING to "50"), Exp("Ragi", "KG", "25", null, "40", "50", G)),
        Owner("kirana", "Rice 10 bag vandhiruku", a(Q.PER_PACK to "25 kg", Q.PURCHASE to "₹1,400", Q.SELLING to "65 rs"), Exp("Rice", "KG", "250", "BAG" to "25", "56", "65", G)),
        Owner("kirana", "Rice 10 bag add pannu", a(Q.PER_PACK to "25", Q.PURCHASE to "1400", Q.SELLING to "65"), Exp("Rice", "KG", "300", "BAG" to "25", "56", "65", G),
            pre = listOf("illa 12 bag dhaan")),
        Owner("kirana", "Jaggery 20 kg vandhuchu", a(Q.PURCHASE to "55", Q.SELLING to "70"), Exp("Jaggery", "KG", "20", null, "55", "70", G)),
        Owner("kirana", "Moong dal 3 moota", a(Q.PER_PACK to "30 kg", Q.PURCHASE to "3300", Q.SELLING to "130"),
            Exp("Moong Dal", "KG", "90", "BAG" to "30", "110", "130", G)),

        // General stores — packed goods in boxes, by the piece
        Owner("general", "Colgate 5 box add pannu", a(Q.PER_PACK to "48", Q.SIZE to "200 gram", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F),
            then = listOf(St("Colgate 10 piece sale", stock = "230"), St("Colgate 2 box add pannu", stock = "326"))),
        Owner("general", "Lux soap 6 box vandhuchu", a(Q.PER_PACK to "24 pieces", Q.SIZE to "100 g", Q.PURCHASE to "30", Q.SELLING to "38"),
            Exp("Lux Soap", "PCS", "144", "BOX" to "24", "30", "38", F)),
        Owner("general", "Parle G 10 box add pannu", a(Q.PER_PACK to "60", Q.PURCHASE to "4.5", Q.SELLING to "5"), Exp("Parle G", "PCS", "600", "BOX" to "60", "4.5", "5", F)),
        Owner("general", "Maggi 3 carton vandhirukku", a(Q.PER_PACK to "96", Q.SIZE to "70 gram", Q.PURCHASE to "12", Q.SELLING to "14"),
            Exp("Maggi", "PCS", "288", "CARTON" to "96", "12", "14", F)),
        Owner("general", "Dettol soap 4 dozen add pannu", a(Q.SIZE to "75 g", Q.PURCHASE to "35", Q.SELLING to "42"), Exp("Dettol Soap", "PCS", "48", null, "35", "42", F)),
        Owner("general", "Hamam 50 pieces add pannu", a(Q.PURCHASE to "32", Q.SELLING to "40"), Exp("Hamam", "PCS", "50", null, "32", "40", F)),
        Owner("general", "Colgate 5 add pannu", a(Q.UNIT to "box", Q.PER_PACK to "48", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F)),
        Owner("general", "Pepsodent 3 box, boxku 36 pieces, 150 gram, purchase 40, selling 50", exp = Exp("Pepsodent", "PCS", "108", "BOX" to "36", "40", "50", F)),
        Owner("general", "Clinic plus shampoo 5 strip add pannu", a(Q.PER_PACK to "16", Q.PURCHASE to "1.5", Q.SELLING to "2"),
            Exp("Clinic Plus Shampoo", "PCS", "80", "STRIP" to "16", "1.5", "2", F)),
        Owner("general", "Bru coffee 20 packet add pannu", a(Q.SIZE to "50 g", Q.PURCHASE to "45", Q.SELLING to "55"), Exp("Bru Coffee", "PACK", "20", null, "45", "55", F)),
        Owner("general", "Colgate 5 box சேர்த்துடு", a(Q.PER_PACK to "48", Q.SIZE to "200 கிராம்", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F), confirm = listOf("ஆமா")),
        Owner("general", "Add 5 boxes of Colgate", a(Q.PER_PACK to "48 pieces", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F), confirm = listOf("yes")),
        Owner("general", "Lifebuoy 2 box add pannu", a(Q.PER_PACK to "72", Q.PURCHASE to "25", Q.SELLING to "30"), exp = null, confirm = listOf("venam")),
        Owner("general", "Britannia marie 5 box add pannu", a(Q.PER_PACK to "30", Q.PURCHASE to "18", Q.SELLING to "20"),
            Exp("Britannia Marie", "PCS", "150", "BOX" to "30", "18", "20", F), then = listOf(St("Britannia marie 10 pieces sale", stock = "140"))),
        Owner("general", "Colgate anju box add pannu", a(Q.PER_PACK to "naarpathettu", Q.PURCHASE to "28 rupees", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F)),
        Owner("general", "Vim bar 4 box vandhuchu", a(Q.PER_PACK to "50", Q.SIZE to "skip", Q.PURCHASE to "9", Q.SELLING to "10"), Exp("Vim Bar", "PCS", "200", "BOX" to "50", "9", "10", F)),
        Owner("general", "Horlicks 12 bottle add pannu", a(Q.SIZE to "500 g", Q.PURCHASE to "240", Q.SELLING to "275"), Exp("Horlicks", "BOTTLE", "12", null, "240", "275", F)),
        Owner("general", "Agarbatti 6 box add pannu", a(Q.PER_PACK to "12", Q.PURCHASE to "20", Q.SELLING to "25"), Exp("Agarbatti", "PCS", "72", "BOX" to "12", "20", "25", F)),

        // Cool drinks shops — cases of bottles
        Owner("drinks", "Coke 3 case vandhuchu", a(Q.PER_PACK to "24 bottles", Q.SIZE to "300 ml", Q.PURCHASE to "30", Q.SELLING to "40"),
            Exp("Coke", "BOTTLE", "72", "CASE" to "24", "30", "40", B), then = listOf(St("Coke 5 bottle sale", stock = "67"))),
        Owner("drinks", "Pepsi 5 case add pannu", a(Q.PER_PACK to "24", Q.SIZE to "250", Q.PURCHASE to "18", Q.SELLING to "20"), Exp("Pepsi", "BOTTLE", "120", "CASE" to "24", "18", "20", B)),
        Owner("drinks", "Sprite 2 case, 12 bottles each, 1 litre bottle, purchase 55, selling 65", exp = Exp("Sprite", "BOTTLE", "24", "CASE" to "12", "55", "65", B)),
        Owner("drinks", "Bisleri 10 case add pannu", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "15", Q.SELLING to "20"), Exp("Bisleri", "BOTTLE", "120", "CASE" to "12", "15", "20", B)),
        Owner("drinks", "Maaza 20 bottle add pannu", a(Q.SIZE to "600 ml", Q.PURCHASE to "35", Q.SELLING to "40"), Exp("Maaza", "BOTTLE", "20", null, "35", "40", B)),
        Owner("drinks", "Frooti 4 box vandhirukku", a(Q.PER_PACK to "30", Q.SIZE to "160ml", Q.PURCHASE to "9", Q.SELLING to "10"), Exp("Frooti", "BOTTLE", "120", "BOX" to "30", "9", "10", B)),
        Owner("drinks", "Coke 3 case வந்திருக்கு", a(Q.PER_PACK to "24", Q.SIZE to "300 ml", Q.PURCHASE to "30", Q.SELLING to "40"),
            Exp("Coke", "BOTTLE", "72", "CASE" to "24", "30", "40", B), confirm = listOf("சரி")),
        Owner("drinks", "Received 5 cases of Pepsi", a(Q.PER_PACK to "24", Q.PURCHASE to "18", Q.SELLING to "20"),
            Exp("Pepsi", "BOTTLE", "120", "CASE" to "24", "18", "20", B), confirm = listOf("yes")),
        Owner("drinks", "Thums up 6 case add pannu", a(Q.PER_PACK to "24", Q.SIZE to "250 ml", Q.PURCHASE to "18", Q.SELLING to "20"), Exp("Thums Up", "BOTTLE", "144", "CASE" to "24", "18", "20", B)),
        Owner("drinks", "Redbull 2 box add pannu", a(Q.PER_PACK to "24", Q.SIZE to "250 ml", Q.PURCHASE to "95", Q.SELLING to "125"), Exp("Redbull", "BOTTLE", "48", "BOX" to "24", "95", "125", B)),
        Owner("drinks", "Soda 4 case vandhuchu", a(Q.PER_PACK to "24", Q.SIZE to "skip", Q.PURCHASE to "8", Q.SELLING to "12"), Exp("Soda", "BOTTLE", "96", "CASE" to "24", "8", "12", B)),

        // Oil marts / dairy — bottles, cans, litres
        Owner("oil", "Oil 2 box add pannu", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "150", Q.SELLING to "180"), Exp("Oil", "BOTTLE", "24", "BOX" to "12", "150", "180", L)),
        Owner("oil", "Sunflower oil 10 litre vandhuchu", a(Q.PURCHASE to "140", Q.SELLING to "160"), Exp("Sunflower Oil", "LITRE", "10", null, "140", "160", L)),
        Owner("oil", "Gingelly oil 5 can add pannu", a(Q.PER_PACK to "15 litre", Q.PURCHASE to "250", Q.SELLING to "280"), Exp("Gingelly Oil", "LITRE", "75", "CAN" to "15", "250", "280", L)),
        Owner("oil", "Nallennai 20 bottle add pannu", a(Q.SIZE to "500 ml", Q.PURCHASE to "190", Q.SELLING to "220"), Exp("Nallennai", "BOTTLE", "20", null, "190", "220", L)),
        Owner("dairy", "Aavin milk 30 packet vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "24"), Exp("Aavin Milk", "PACK", "30", null, "22", "24", L)),
        Owner("dairy", "Ghee 12 bottle add pannu", a(Q.SIZE to "200ml", Q.PURCHASE to "120", Q.SELLING to "140"), Exp("Ghee", "BOTTLE", "12", null, "120", "140", L)),
        Owner("oil", "Phenyl 3 box add pannu", a(Q.PER_PACK to "20", Q.SIZE to "1 litre", Q.PURCHASE to "40", Q.SELLING to "55"), Exp("Phenyl", "BOTTLE", "60", "BOX" to "20", "40", "55", L)),
        Owner("oil", "எண்ணெய் 2 box வந்திருக்கு", a(Q.PER_PACK to "12", Q.SIZE to "1 லிட்டர்", Q.PURCHASE to "150", Q.SELLING to "180"),
            Exp("எண்ணெய்", "BOTTLE", "24", "BOX" to "12", "150", "180", L), confirm = listOf("ஆமா")),
        Owner("oil", "Coconut oil 6 box, 24 bottles each, 200 ml, purchase 45, selling 55", exp = Exp("Coconut Oil", "BOTTLE", "144", "BOX" to "24", "45", "55", L)),
        Owner("oil", "Add 10 litres of groundnut oil", a(Q.PURCHASE to "180", Q.SELLING to "200"), Exp("Groundnut Oil", "LITRE", "10", null, "180", "200", L), confirm = listOf("yes")),

        // Textile shops — sizes, never grams
        Owner("textile", "Shirt 20 pieces add pannu", a(Q.SIZE to "M 8 L 7 XL 5", Q.PURCHASE to "250", Q.SELLING to "400"),
            Exp("Shirt", "PCS", "20", null, "250", "400", W), then = listOf(St("Shirt 2 pieces damage", stock = "18"))),
        Owner("textile", "Saree 10 add pannu", a(Q.UNIT to "pieces", Q.PURCHASE to "800", Q.SELLING to "1200"), Exp("Saree", "PCS", "10", null, "800", "1200", W)),
        Owner("textile", "Jeans 15 pcs vandhuchu", a(Q.SIZE to "30, 32, 34", Q.PURCHASE to "600", Q.SELLING to "999"), Exp("Jeans", "PCS", "15", null, "600", "999", W)),
        Owner("textile", "Lungi 2 bundle add pannu", a(Q.PER_PACK to "20", Q.PURCHASE to "150", Q.SELLING to "220"), Exp("Lungi", "PCS", "40", "BUNDLE" to "20", "150", "220", W)),
        Owner("textile", "Kids frock 12 pieces vandhuchu", a(Q.PURCHASE to "200", Q.SELLING to "350"), Exp("Kids Frock", "PCS", "12", null, "200", "350", W)),
        Owner("textile", "Veshti 3 box add pannu", a(Q.PER_PACK to "10", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Veshti", "PCS", "30", "BOX" to "10", "300", "450", W)),
        Owner("textile", "சட்டை 20 பீஸ் வந்திருக்கு", a(Q.SIZE to "M 10 L 10", Q.PURCHASE to "250", Q.SELLING to "400"), Exp("சட்டை", "PCS", "20", null, "250", "400", W), confirm = listOf("ஆமா")),
        Owner("textile", "Add 25 t-shirts", a(Q.UNIT to "pieces", Q.PURCHASE to "150", Q.SELLING to "250"), Exp("T-shirts", "PCS", "25", null, "150", "250", W), confirm = listOf("yes")),
        Owner("textile", "Nighty 30 pieces, M 10 L 10 XL 10, purchase 180, selling 299", exp = Exp("Nighty", "PCS", "30", null, "180", "299", W)),
        Owner("textile", "Towel 5 dozen add pannu", a(Q.PURCHASE to "60", Q.SELLING to "90"), Exp("Towel", "PCS", "60", null, "60", "90", W)),

        // Footwear shops — pairs
        Owner("footwear", "Slippers 10 pairs add pannu", a(Q.SIZE to "7 8 9", Q.PURCHASE to "120", Q.SELLING to "199"), Exp("Slippers", "PAIR", "10", null, "120", "199", FW)),
        Owner("footwear", "Bata shoe 6 jodi vandhuchu", a(Q.SIZE to "8, 9, 10", Q.PURCHASE to "900", Q.SELLING to "1299"), Exp("Bata Shoe", "PAIR", "6", null, "900", "1299", FW)),
        Owner("footwear", "Chappal 5 box add pannu", a(Q.PER_PACK to "12 pairs", Q.PURCHASE to "90", Q.SELLING to "150"), Exp("Chappal", "PAIR", "60", "BOX" to "12", "90", "150", FW)),
        Owner("footwear", "செருப்பு 10 ஜோடி வந்திருக்கு", a(Q.PURCHASE to "120", Q.SELLING to "199"), Exp("செருப்பு", "PAIR", "10", null, "120", "199", FW), confirm = listOf("ஆமா")),
        Owner("footwear", "Add 8 pairs of sandals", a(Q.SIZE to "6, 7, 8", Q.PURCHASE to "250", Q.SELLING to "399"), Exp("Sandals", "PAIR", "8", null, "250", "399", FW), confirm = listOf("yes")),

        // Hardware shops
        Owner("hardware", "Screws 4 box add pannu", a(Q.PER_PACK to "100", Q.SIZE to "2 inch", Q.PURCHASE to "1", Q.SELLING to "2"), Exp("Screws", "PCS", "400", "BOX" to "100", "1", "2", H)),
        Owner("hardware", "Aani 5 kg vandhuchu", a(Q.SIZE to "3 inch", Q.PURCHASE to "90", Q.SELLING to "120"), Exp("Aani", "KG", "5", null, "90", "120", H)),
        Owner("hardware", "Pipe 20 pieces add pannu", a(Q.SIZE to "1 inch PVC", Q.PURCHASE to "150", Q.SELLING to "200"), Exp("Pipe", "PCS", "20", null, "150", "200", H)),
        Owner("hardware", "Switch 3 box add pannu", a(Q.PER_PACK to "50", Q.SIZE to "6A", Q.PURCHASE to "25", Q.SELLING to "40"), Exp("Switch", "PCS", "150", "BOX" to "50", "25", "40", H)),
        Owner("hardware", "Cement 10 bag vandhuchu", a(Q.SIZE to "50 kg", Q.PURCHASE to "400", Q.SELLING to "430"), Exp("Cement", "BAG", "10", null, "400", "430", H)),
        Owner("hardware", "Bolts 2 box add pannu", a(Q.PER_PACK to "200", Q.SIZE to "M8", Q.PURCHASE to "3", Q.SELLING to "5"), Exp("Bolts", "PCS", "400", "BOX" to "200", "3", "5", H)),
        Owner("hardware", "ஆணி 10 kg வந்திருக்கு", a(Q.PURCHASE to "90", Q.SELLING to "120"), Exp("ஆணி", "KG", "10", null, "90", "120", H), confirm = listOf("ஆமா")),
        Owner("hardware", "Add 3 boxes of nails", a(Q.PER_PACK to "500", Q.SIZE to "2 inch", Q.PURCHASE to "0.5", Q.SELLING to "1"),
            Exp("Nails", "PCS", "1500", "BOX" to "500", "0.5", "1", H), confirm = listOf("yes")),

        // Mobile / electrical accessories
        Owner("mobile", "Charger 2 box add pannu", a(Q.PER_PACK to "10", Q.SIZE to "Samsung 25W", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Charger", "PCS", "20", "BOX" to "10", "300", "450", E)),
        Owner("mobile", "Earphones 30 pieces vandhuchu", a(Q.SIZE to "boAt", Q.PURCHASE to "150", Q.SELLING to "299"), Exp("Earphones", "PCS", "30", null, "150", "299", E)),
        Owner("mobile", "USB cable 5 box add pannu", a(Q.PER_PACK to "20", Q.PURCHASE to "60", Q.SELLING to "120"), Exp("Usb Cable", "PCS", "100", "BOX" to "20", "60", "120", E)),
        Owner("electrical", "LED bulb 4 box, box ku 10 pieces, model 9W, purchase 70, selling 110", exp = Exp("Led Bulb", "PCS", "40", "BOX" to "10", "70", "110", E)),
        Owner("electrical", "Battery 10 strip add pannu", a(Q.PER_PACK to "4", Q.SIZE to "AA", Q.PURCHASE to "10", Q.SELLING to "15"), Exp("Battery", "PCS", "40", "STRIP" to "4", "10", "15", E)),
        Owner("mobile", "Received 12 power banks", a(Q.UNIT to "pieces", Q.SIZE to "10000 mAh", Q.PURCHASE to "700", Q.SELLING to "999"),
            Exp("Power Banks", "PCS", "12", null, "700", "999", E), confirm = listOf("yes")),
        Owner("mobile", "Charger 2 box சேர்த்துடு", a(Q.PER_PACK to "10", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Charger", "PCS", "20", "BOX" to "10", "300", "450", E), confirm = listOf("ஆமா")),

        // Stationery / bakery / others
        Owner("stationery", "Notebook 5 bundle add pannu", a(Q.PER_PACK to "12", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Notebook", "PCS", "60", "BUNDLE" to "12", "30", "40", GN)),
        Owner("stationery", "Pen 10 dozen vandhuchu", a(Q.PURCHASE to "4", Q.SELLING to "5"), Exp("Pen", "PCS", "120", null, "4", "5", GN)),
        Owner("general", "Candle 3 box add pannu", a(Q.PER_PACK to "50", Q.PURCHASE to "2", Q.SELLING to "3"), Exp("Candle", "PCS", "150", "BOX" to "50", "2", "3", GN)),
        Owner("general", "Matchbox 10 bundle add pannu", a(Q.PER_PACK to "10", Q.PURCHASE to "1", Q.SELLING to "2"), Exp("Matchbox", "PCS", "100", "BUNDLE" to "10", "1", "2", GN)),
        Owner("bakery", "Bread 20 packet vandhuchu", a(Q.PURCHASE to "35", Q.SELLING to "40"), Exp("Bread", "PACK", "20", null, "35", "40", GN)),

        // Safety: corrections, cancel, rejected save, an existing product
        Owner("general", "Colgate 5 box add pannu", a(Q.PER_PACK to "48", Q.PURCHASE to "28", Q.SELLING to "35"), Exp("Colgate", "PCS", "192", "BOX" to "48", "28", "35", F),
            confirm = listOf("illa 4 box dhaan", "aama")),
        Owner("drinks", "Coke 3 case add pannu", a(Q.PER_PACK to "cancel"), exp = null),
        Owner("general", "Lux 4 box add pannu", a(Q.PER_PACK to "24", Q.PURCHASE to "30", Q.SELLING to "38"), exp = null, failCreate = true),
        Owner("general", "Colgate 2 box add pannu", exp = Exp("Colgate", "PCS", "336", "BOX" to "48", "28", "35", F), stocked = true),

        // Shops already stocked: daily stock movements by chat
        Owner("kirana", "Rice 2 moota vandhuchu", exp = Exp("Rice", "KG", "300", "BAG" to "25", "56", "65", G), stocked = true),
        Owner("general", "Colgate 10 piece damage", exp = Exp("Colgate", "PCS", "230", "BOX" to "48", "28", "35", F), stocked = true),
        Owner("oil", "Oil 2 box sale", exp = Exp("Oil", "BOTTLE", "216", "BOX" to "12", category = L), stocked = true),
        Owner("kirana", "Rice evlo irukku?", exp = null, stocked = true, then = listOf(St("Rice evlo irukku?", tap = false, has = "250"))),
        Owner("general", "Colgate selling price 38 aakku", exp = Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "38", F), stocked = true),
        Owner("kirana", "ABC Traders kitta 2 moota Rice vaanginen", exp = null, stocked = true, confirm = emptyList(),
            then = listOf(St("credit", item = "Rice", stock = "300", balance = "ABC Traders" to "2800"))),
    )

    @Test
    fun hundredOwners() {
        val failures = owners.mapNotNull(::play)
        println("MATRIX inventory-owners ${owners.size - failures.size}/${owners.size}")
        failures.forEach { println("FAIL $it") }
        assertTrue("${owners.size} owners", owners.size == 100)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------ 150 combinations: 10 products × 5 phrasings × 3 languages

    private data class Good(val name: String, val qty: String, val answers: Map<Q, String>, val exp: Exp)

    private val goods = listOf(
        Good("Colgate", "5 box", a(Q.PER_PACK to "48", Q.SIZE to "200 gram", Q.PURCHASE to "28", Q.SELLING to "35"), Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F)),
        Good("Rice", "10 bag", a(Q.PER_PACK to "25", Q.PURCHASE to "1400", Q.SELLING to "65"), Exp("Rice", "KG", "250", "BAG" to "25", "56", "65", G)),
        Good("Coke", "3 case", a(Q.PER_PACK to "24", Q.SIZE to "300 ml", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Coke", "BOTTLE", "72", "CASE" to "24", "30", "40", B)),
        Good("Oil", "2 box", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "150", Q.SELLING to "180"), Exp("Oil", "BOTTLE", "24", "BOX" to "12", "150", "180", L)),
        Good("Shirt", "20 pieces", a(Q.SIZE to "M 10 L 10", Q.PURCHASE to "250", Q.SELLING to "400"), Exp("Shirt", "PCS", "20", null, "250", "400", W)),
        Good("Slippers", "10 pairs", a(Q.SIZE to "7 8 9", Q.PURCHASE to "120", Q.SELLING to "199"), Exp("Slippers", "PAIR", "10", null, "120", "199", FW)),
        Good("Screws", "4 box", a(Q.PER_PACK to "100", Q.SIZE to "2 inch", Q.PURCHASE to "1", Q.SELLING to "2"), Exp("Screws", "PCS", "400", "BOX" to "100", "1", "2", H)),
        Good("Charger", "2 box", a(Q.PER_PACK to "10", Q.SIZE to "25W", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Charger", "PCS", "20", "BOX" to "10", "300", "450", E)),
        Good("Sugar", "5 bag", a(Q.PER_PACK to "50", Q.PURCHASE to "2000", Q.SELLING to "45"), Exp("Sugar", "KG", "250", "BAG" to "50", "40", "45", G)),
        Good("Lux soap", "6 box", a(Q.PER_PACK to "24", Q.SIZE to "100 g", Q.PURCHASE to "30", Q.SELLING to "38"), Exp("Lux Soap", "PCS", "144", "BOX" to "24", "30", "38", F)),
    )

    private val phrasings = listOf(
        "aama" to listOf("{p} {q} add pannu", "{p} {q} vandhirukku", "pudhu stock {p} {q} vandhuchu", "{q} {p} vandhuchu", "{p} {q} stock-la podu"),
        "ஆமா" to listOf("{p} {q} சேர்த்துடு", "{p} {q} வந்திருக்கு", "{q} {p} வந்துச்சு", "{p} {q} ஸ்டாக்ல சேர்", "{p} {q} ஆட் பண்ணு"),
        "yes" to listOf("Add {q} {p}", "{p} {q} arrived", "Received {q} of {p}", "Add {q} of {p} to stock", "{q} {p} came in today"),
    )

    @Test
    fun hundredFiftyCombinations() {
        val cases = goods.flatMap { g -> phrasings.flatMap { (yes, list) ->
            list.map { ph -> Owner("combo", ph.replace("{p}", g.name).replace("{q}", g.qty), g.answers, g.exp, confirm = listOf(yes)) } } }
        val failures = cases.mapNotNull(::play)
        println("MATRIX inventory-combinations ${cases.size - failures.size}/${cases.size}")
        failures.forEach { println("FAIL $it") }
        assertTrue("${cases.size} combinations", cases.size == 150)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
