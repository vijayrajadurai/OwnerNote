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
    enum class Q { QTY, UNIT, PER_PACK, SIZE, BATCH, EXPIRY, PURCHASE, SELLING, SUMMARY }

    private fun asked(text: String): Q? = when {
        Regex("""Stock add pannattuma|Stock add பண்ணட்டுமா|Shall I add it to stock""").containsMatchIn(text) -> Q.SUMMARY
        Regex("""(?i)purchase rate""").containsMatchIn(text) && text.trim().endsWith("?") -> Q.PURCHASE
        Regex("""(?i)selling rate""").containsMatchIn(text) && text.trim().endsWith("?") -> Q.SELLING
        Regex("""(?i)batch number""").containsMatchIn(text) -> Q.BATCH
        Regex("""(?i)expiry date""").containsMatchIn(text) -> Q.EXPIRY
        Regex("""(?i)gram\?|ml\?|size|model|vehicle|litre / ml|grams is|litres / ml""").containsMatchIn(text) -> Q.SIZE
        Regex("""-aa Owner\?|-ஆ Owner\?| or .+, Owner\?""").containsMatchIn(text) && !Regex("""(?i)credit""").containsMatchIn(text) -> Q.UNIT
        Regex("""👍 1 .+(evlo|எத்தனை)|How many .+ in 1""").containsMatchIn(text) -> Q.PER_PACK
        Regex("""evlo vandhirukku|எவ்வளவு வந்திருக்கு|came in\?""").containsMatchIn(text) -> Q.QTY
        else -> null
    }

    /** The product as it must be in the inventory after Confirm (prices per stock unit). */
    data class Exp(
        val name: String, val unit: String, val stock: String, val pack: Pair<String, String>? = null,
        val purchase: String? = null, val selling: String? = null, val category: String,
        /** What was saved with the product: size / colour / model / vehicle, batch, expiry ("03/2027"). */
        val size: String? = null, val batch: String? = null, val expiry: String? = null,
    )

    /** One later message about stock already there: optional follow-ups, Confirm tapped, the stock it must leave. */
    data class St(val text: String, val follow: List<String> = emptyList(), val tap: Boolean = true, val item: String? = null,
                  val stock: String? = null, val has: String? = null, val balance: Pair<String, String>? = null)

    data class Owner(
        val shop: String, val first: String, val answers: Map<Q, String> = emptyMap(), val exp: Exp?,
        val pre: List<String> = emptyList(), val confirm: List<String> = listOf("aama"), val stocked: Boolean = false,
        val failCreate: Boolean = false, val then: List<St> = emptyList(),
        /** On stock already there: tap Confirm (false = answer with [confirm] instead); tap it twice; how many movements in all. */
        val tap: Boolean = true, val tapTwice: Boolean = false, val moves: Int? = null,
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
        val lang = when (o.confirm.lastOrNull()) { "ஆமா", "சரி", "வேண்டாம்" -> KaiLang.TAMIL; "yes", "ok" -> KaiLang.ENGLISH; else -> KaiLang.TANGLISH }
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
                else -> say(o.answers[q] ?: if (q in setOf(Q.SIZE, Q.BATCH, Q.EXPIRY)) "skip" else return fail("asked $q, owner has no answer"))
            }
        }
        // A movement on stock already there is a draft with a Confirm button: the owner taps it (or says yes).
        if (o.stocked && confirms.isNotEmpty() && asked(t.reply.text) == null) {
            val b = t.card?.buttons?.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }
            t = if (o.tap && b != null) runBlocking { k.act(b.action, lang) }!!.also { log.append("\n   [Confirm] ${it.reply.text}") } else say(confirms.removeAt(0))
            if (o.tapTwice && b != null) runBlocking { k.act(b.action, lang) }.also { log.append("\n   [Confirm again] ${it?.reply?.text}") }
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
            if (e.size != null && i.size != e.size) return fail("size '${i.size}' ≠ '${e.size}'")
            val made = shop.created.firstOrNull { it.name.equals(e.name, true) }
            if (e.batch != null && made?.batchNo != e.batch) return fail("batch ${made?.batchNo} ≠ ${e.batch}")
            if (e.expiry != null && made?.expiry?.let(com.shopai.app.brain.tools.KaiInventory::expiryShown) != e.expiry)
                return fail("expiry ${made?.expiry} ≠ ${e.expiry}")
            if (i.name !in before) {
                val opening = shop.moves.filter { it.itemId == i.id && it.type == "OPENING" }
                if (opening.size != 1) return fail("opening movements $opening")
                if (!done.reply.text.contains("✅")) return fail("no saved reply")
            }
            // Saying yes again must not save twice.
            val moves = shop.moves.size
            o.confirm.lastOrNull()?.let(::say)
            if (shop.moves.size != moves || shop.items.count { it.name.equals(e.name, true) } != 1) return fail("a second yes saved again")
        }
        if (o.moves != null && shop.moves.size != o.moves) return fail("movements ${shop.moves.size} ≠ ${o.moves}: ${shop.moves}")
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
    // The owner's 20 shop categories (labels as the owner named them).
    private val G = "Grocery & Staples"; private val F = "FMCG & Personal Care"; private val SN = "Snacks & Confectionery"
    private val B = "Beverages & Dairy"; private val HC = "Home Care & Cleaning"; private val W = "Garments & Textiles"
    private val H = "Hardware & Plumbing"; private val EL = "Electricals & Lighting"; private val E = "Mobile & Electronics"
    private val ST = "Stationery & Office Supplies"; private val FW = "Footwear & Accessories"; private val KW = "Kitchenware & Household"
    private val PH = "Pharmacy & Medical"; private val AU = "Automobile Spare Parts"; private val BK = "Bakery & Fresh Foods"
    private val TY = "Toys, Gifts & Party"; private val BY = "Baby Care & Hygiene"; private val GN = "General"
    private val VG = "Fruits & Vegetables"; private val MT = "Meat & Fish"
    private val PJ = "Pooja & Religious Items"; private val AG = "Agriculture & Gardening"

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
        Owner("general", "Parle G 10 box add pannu", a(Q.PER_PACK to "60", Q.PURCHASE to "4.5", Q.SELLING to "5"), Exp("Parle G", "PCS", "600", "BOX" to "60", "4.5", "5", SN)),
        Owner("general", "Maggi 3 carton vandhirukku", a(Q.PER_PACK to "96", Q.SIZE to "70 gram", Q.PURCHASE to "12", Q.SELLING to "14"),
            Exp("Maggi", "PCS", "288", "CARTON" to "96", "12", "14", G)),
        Owner("general", "Dettol soap 4 dozen add pannu", a(Q.SIZE to "75 g", Q.PURCHASE to "35", Q.SELLING to "42"), Exp("Dettol Soap", "PCS", "48", null, "35", "42", F)),
        Owner("general", "Hamam 50 pieces add pannu", a(Q.PURCHASE to "32", Q.SELLING to "40"), Exp("Hamam", "PCS", "50", null, "32", "40", F)),
        Owner("general", "Colgate 5 add pannu", a(Q.UNIT to "box", Q.PER_PACK to "48", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F)),
        Owner("general", "Pepsodent 3 box, boxku 36 pieces, 150 gram, purchase 40, selling 50", exp = Exp("Pepsodent", "PCS", "108", "BOX" to "36", "40", "50", F)),
        Owner("general", "Clinic plus shampoo 5 strip add pannu", a(Q.PER_PACK to "16", Q.PURCHASE to "1.5", Q.SELLING to "2"),
            Exp("Clinic Plus Shampoo", "PCS", "80", "STRIP" to "16", "1.5", "2", F)),
        Owner("general", "Bru coffee 20 packet add pannu", a(Q.SIZE to "50 g", Q.PURCHASE to "45", Q.SELLING to "55"), Exp("Bru Coffee", "PACK", "20", null, "45", "55", G)),
        Owner("general", "Colgate 5 box சேர்த்துடு", a(Q.PER_PACK to "48", Q.SIZE to "200 கிராம்", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F), confirm = listOf("ஆமா")),
        Owner("general", "Add 5 boxes of Colgate", a(Q.PER_PACK to "48 pieces", Q.PURCHASE to "28", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F), confirm = listOf("yes")),
        Owner("general", "Lifebuoy 2 box add pannu", a(Q.PER_PACK to "72", Q.PURCHASE to "25", Q.SELLING to "30"), exp = null, confirm = listOf("venam")),
        Owner("general", "Britannia marie 5 box add pannu", a(Q.PER_PACK to "30", Q.PURCHASE to "18", Q.SELLING to "20"),
            Exp("Britannia Marie", "PCS", "150", "BOX" to "30", "18", "20", SN), then = listOf(St("Britannia marie 10 pieces sale", stock = "140"))),
        Owner("general", "Colgate anju box add pannu", a(Q.PER_PACK to "naarpathettu", Q.PURCHASE to "28 rupees", Q.SELLING to "35"),
            Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "35", F)),
        Owner("general", "Vim bar 4 box vandhuchu", a(Q.PER_PACK to "50", Q.SIZE to "skip", Q.PURCHASE to "9", Q.SELLING to "10"), Exp("Vim Bar", "PCS", "200", "BOX" to "50", "9", "10", HC)),
        Owner("general", "Horlicks 12 bottle add pannu", a(Q.SIZE to "500 g", Q.PURCHASE to "240", Q.SELLING to "275"), Exp("Horlicks", "BOTTLE", "12", null, "240", "275", G)),
        Owner("general", "Agarbatti 6 box add pannu", a(Q.PER_PACK to "12", Q.PURCHASE to "20", Q.SELLING to "25"), Exp("Agarbatti", "PCS", "72", "BOX" to "12", "20", "25", PJ)),

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
        Owner("oil", "Oil 2 box add pannu", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "150", Q.SELLING to "180"), Exp("Oil", "BOTTLE", "24", "BOX" to "12", "150", "180", G)),
        Owner("oil", "Sunflower oil 10 litre vandhuchu", a(Q.PURCHASE to "140", Q.SELLING to "160"), Exp("Sunflower Oil", "LITRE", "10", null, "140", "160", G)),
        Owner("oil", "Gingelly oil 5 can add pannu", a(Q.PER_PACK to "15 litre", Q.PURCHASE to "250", Q.SELLING to "280"), Exp("Gingelly Oil", "LITRE", "75", "CAN" to "15", "250", "280", G)),
        Owner("oil", "Nallennai 20 bottle add pannu", a(Q.SIZE to "500 ml", Q.PURCHASE to "190", Q.SELLING to "220"), Exp("Nallennai", "BOTTLE", "20", null, "190", "220", G)),
        Owner("dairy", "Aavin milk 30 packet vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "24"), Exp("Aavin Milk", "PACK", "30", null, "22", "24", B)),
        Owner("dairy", "Ghee 12 bottle add pannu", a(Q.SIZE to "200ml", Q.PURCHASE to "120", Q.SELLING to "140"), Exp("Ghee", "BOTTLE", "12", null, "120", "140", G)),
        Owner("oil", "Phenyl 3 box add pannu", a(Q.PER_PACK to "20", Q.SIZE to "1 litre", Q.PURCHASE to "40", Q.SELLING to "55"), Exp("Phenyl", "BOTTLE", "60", "BOX" to "20", "40", "55", HC)),
        Owner("oil", "எண்ணெய் 2 box வந்திருக்கு", a(Q.PER_PACK to "12", Q.SIZE to "1 லிட்டர்", Q.PURCHASE to "150", Q.SELLING to "180"),
            Exp("எண்ணெய்", "BOTTLE", "24", "BOX" to "12", "150", "180", G), confirm = listOf("ஆமா")),
        Owner("oil", "Coconut oil 6 box, 24 bottles each, 200 ml, purchase 45, selling 55", exp = Exp("Coconut Oil", "BOTTLE", "144", "BOX" to "24", "45", "55", G)),
        Owner("oil", "Add 10 litres of groundnut oil", a(Q.PURCHASE to "180", Q.SELLING to "200"), Exp("Groundnut Oil", "LITRE", "10", null, "180", "200", G), confirm = listOf("yes")),

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
        Owner("hardware", "Switch 3 box add pannu", a(Q.PER_PACK to "50", Q.SIZE to "6A", Q.PURCHASE to "25", Q.SELLING to "40"), Exp("Switch", "PCS", "150", "BOX" to "50", "25", "40", EL)),
        Owner("hardware", "Cement 10 bag vandhuchu", a(Q.SIZE to "50 kg", Q.PURCHASE to "400", Q.SELLING to "430"), Exp("Cement", "BAG", "10", null, "400", "430", H)),
        Owner("hardware", "Bolts 2 box add pannu", a(Q.PER_PACK to "200", Q.SIZE to "M8", Q.PURCHASE to "3", Q.SELLING to "5"), Exp("Bolts", "PCS", "400", "BOX" to "200", "3", "5", H)),
        Owner("hardware", "ஆணி 10 kg வந்திருக்கு", a(Q.PURCHASE to "90", Q.SELLING to "120"), Exp("ஆணி", "KG", "10", null, "90", "120", H), confirm = listOf("ஆமா")),
        Owner("hardware", "Add 3 boxes of nails", a(Q.PER_PACK to "500", Q.SIZE to "2 inch", Q.PURCHASE to "0.5", Q.SELLING to "1"),
            Exp("Nails", "PCS", "1500", "BOX" to "500", "0.5", "1", H), confirm = listOf("yes")),

        // Mobile / electrical accessories
        Owner("mobile", "Charger 2 box add pannu", a(Q.PER_PACK to "10", Q.SIZE to "Samsung 25W", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Charger", "PCS", "20", "BOX" to "10", "300", "450", E)),
        Owner("mobile", "Earphones 30 pieces vandhuchu", a(Q.SIZE to "boAt", Q.PURCHASE to "150", Q.SELLING to "299"), Exp("Earphones", "PCS", "30", null, "150", "299", E)),
        Owner("mobile", "USB cable 5 box add pannu", a(Q.PER_PACK to "20", Q.PURCHASE to "60", Q.SELLING to "120"), Exp("Usb Cable", "PCS", "100", "BOX" to "20", "60", "120", E)),
        Owner("electrical", "LED bulb 4 box, box ku 10 pieces, model 9W, purchase 70, selling 110", exp = Exp("Led Bulb", "PCS", "40", "BOX" to "10", "70", "110", EL)),
        Owner("electrical", "Battery 10 strip add pannu", a(Q.PER_PACK to "4", Q.SIZE to "AA", Q.PURCHASE to "10", Q.SELLING to "15"), Exp("Battery", "PCS", "40", "STRIP" to "4", "10", "15", EL)),
        Owner("mobile", "Received 12 power banks", a(Q.UNIT to "pieces", Q.SIZE to "10000 mAh", Q.PURCHASE to "700", Q.SELLING to "999"),
            Exp("Powerbanks", "PCS", "12", null, "700", "999", E), confirm = listOf("yes")),
        Owner("mobile", "Charger 2 box சேர்த்துடு", a(Q.PER_PACK to "10", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Charger", "PCS", "20", "BOX" to "10", "300", "450", E), confirm = listOf("ஆமா")),

        // Stationery / bakery / others
        Owner("stationery", "Notebook 5 bundle add pannu", a(Q.PER_PACK to "12", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Notebook", "PCS", "60", "BUNDLE" to "12", "30", "40", ST)),
        Owner("stationery", "Pen 10 dozen vandhuchu", a(Q.PURCHASE to "4", Q.SELLING to "5"), Exp("Pen", "PCS", "120", null, "4", "5", ST)),
        Owner("general", "Candle 3 box add pannu", a(Q.PER_PACK to "50", Q.PURCHASE to "2", Q.SELLING to "3"), Exp("Candle", "PCS", "150", "BOX" to "50", "2", "3", GN)),
        Owner("general", "Matchbox 10 bundle add pannu", a(Q.PER_PACK to "10", Q.PURCHASE to "1", Q.SELLING to "2"), Exp("Matchbox", "PCS", "100", "BUNDLE" to "10", "1", "2", GN)),
        Owner("bakery", "Bread 20 packet vandhuchu", a(Q.PURCHASE to "35", Q.SELLING to "40"), Exp("Bread", "PACK", "20", null, "35", "40", BK)),

        // Safety: corrections, cancel, rejected save, an existing product
        Owner("general", "Colgate 5 box add pannu", a(Q.PER_PACK to "48", Q.PURCHASE to "28", Q.SELLING to "35"), Exp("Colgate", "PCS", "192", "BOX" to "48", "28", "35", F),
            confirm = listOf("illa 4 box dhaan", "aama")),
        Owner("drinks", "Coke 3 case add pannu", a(Q.PER_PACK to "cancel"), exp = null),
        Owner("general", "Lux 4 box add pannu", a(Q.PER_PACK to "24", Q.PURCHASE to "30", Q.SELLING to "38"), exp = null, failCreate = true),
        Owner("general", "Colgate 2 box add pannu", exp = Exp("Colgate", "PCS", "336", "BOX" to "48", "28", "35", "FMCG"), stocked = true),

        // Shops already stocked: daily stock movements by chat
        Owner("kirana", "Rice 2 moota vandhuchu", exp = Exp("Rice", "KG", "300", "BAG" to "25", "56", "65", "Grocery"), stocked = true),
        Owner("general", "Colgate 10 piece damage", exp = Exp("Colgate", "PCS", "230", "BOX" to "48", "28", "35", "FMCG"), stocked = true),
        Owner("oil", "Oil 2 box sale", exp = Exp("Oil", "BOTTLE", "216", "BOX" to "12", category = "Liquids"), stocked = true),
        Owner("kirana", "Rice evlo irukku?", exp = null, stocked = true, then = listOf(St("Rice evlo irukku?", tap = false, has = "250"))),
        Owner("general", "Colgate selling price 38 aakku", exp = Exp("Colgate", "PCS", "240", "BOX" to "48", "28", "38", "FMCG"), stocked = true),
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
        Good("Oil", "2 box", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "150", Q.SELLING to "180"), Exp("Oil", "BOTTLE", "24", "BOX" to "12", "150", "180", G)),
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

    // ------------------------------------------------------------------ 200 more (owner's second check): 120 new products + 80 on stock already there

    private val newGoods = listOf(
        Good("Dove soap", "4 box", a(Q.PER_PACK to "36", Q.SIZE to "100 g", Q.PURCHASE to "45", Q.SELLING to "55"), Exp("Dove Soap", "PCS", "144", "BOX" to "36", "45", "55", F)),
        Good("Basmati rice", "8 bag", a(Q.PER_PACK to "25", Q.PURCHASE to "2500", Q.SELLING to "120"), Exp("Basmati Rice", "KG", "200", "BAG" to "25", "100", "120", G)),
        Good("Urad dal", "6 moota", a(Q.PER_PACK to "30", Q.PURCHASE to "3600", Q.SELLING to "140"), Exp("Urad Dal", "KG", "180", "BAG" to "30", "120", "140", G)),
        Good("Fanta", "4 case", a(Q.PER_PACK to "24", Q.SIZE to "250 ml", Q.PURCHASE to "18", Q.SELLING to "20"), Exp("Fanta", "BOTTLE", "96", "CASE" to "24", "18", "20", B)),
        Good("Kinley water", "6 case", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "15", Q.SELLING to "20"), Exp("Kinley Water", "BOTTLE", "72", "CASE" to "12", "15", "20", B)),
        Good("Gold winner oil", "3 box", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "160", Q.SELLING to "185"), Exp("Gold Winner Oil", "BOTTLE", "36", "BOX" to "12", "160", "185", G)),
        Good("Milk", "40 packet", a(Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "25"), Exp("Milk", "PACK", "40", null, "22", "25", B)),
        Good("Kurti", "15 pieces", a(Q.SIZE to "M 5 L 5 XL 5", Q.PURCHASE to "300", Q.SELLING to "499"), Exp("Kurti", "PCS", "15", null, "300", "499", W)),
        Good("Saree", "3 bundle", a(Q.PER_PACK to "10", Q.PURCHASE to "700", Q.SELLING to "1100"), Exp("Saree", "PCS", "30", "BUNDLE" to "10", "700", "1100", W)),
        Good("Sandals", "6 box", a(Q.PER_PACK to "10 pairs", Q.PURCHASE to "150", Q.SELLING to "249"), Exp("Sandals", "PAIR", "60", "BOX" to "10", "150", "249", FW)),
        Good("Nails", "2 box", a(Q.PER_PACK to "1000", Q.SIZE to "1 inch", Q.PURCHASE to "0.2", Q.SELLING to "0.5"), Exp("Nails", "PCS", "2000", "BOX" to "1000", "0.2", "0.5", H)),
        Good("Hinges", "3 box", a(Q.PER_PACK to "50", Q.SIZE to "4 inch", Q.PURCHASE to "12", Q.SELLING to "20"), Exp("Hinges", "PCS", "150", "BOX" to "50", "12", "20", H)),
        Good("Earphone", "4 box", a(Q.PER_PACK to "25", Q.SIZE to "boAt", Q.PURCHASE to "120", Q.SELLING to "250"), Exp("Earphone", "PCS", "100", "BOX" to "25", "120", "250", E)),
        Good("Bulb", "6 box", a(Q.PER_PACK to "10", Q.SIZE to "9W", Q.PURCHASE to "60", Q.SELLING to "100"), Exp("Bulb", "PCS", "60", "BOX" to "10", "60", "100", EL)),
        Good("Biscuit", "8 box", a(Q.PER_PACK to "48", Q.PURCHASE to "4", Q.SELLING to "5"), Exp("Biscuit", "PCS", "384", "BOX" to "48", "4", "5", SN)),
        Good("Lays chips", "10 box", a(Q.PER_PACK to "40", Q.SIZE to "50 g", Q.PURCHASE to "8", Q.SELLING to "10"), Exp("Lays Chips", "PCS", "400", "BOX" to "40", "8", "10", SN)),
        Good("Sugar", "4 moota", a(Q.PER_PACK to "50", Q.PURCHASE to "2000", Q.SELLING to "45"), Exp("Sugar", "KG", "200", "BAG" to "50", "40", "45", G)),
        Good("Shampoo", "3 box", a(Q.PER_PACK to "60", Q.SIZE to "8", Q.PURCHASE to "1.5", Q.SELLING to "2"), Exp("Shampoo", "PCS", "180", "BOX" to "60", "1.5", "2", F)),
        Good("Candle", "5 box", a(Q.PER_PACK to "20", Q.PURCHASE to "5", Q.SELLING to "7"), Exp("Candle", "PCS", "100", "BOX" to "20", "5", "7", GN)),
        Good("Surf", "5 box", a(Q.PER_PACK to "24", Q.SIZE to "500 g", Q.PURCHASE to "55", Q.SELLING to "65"), Exp("Surf", "PCS", "120", "BOX" to "24", "55", "65", HC)),
    )

    /** How each owner says it: opening, the way numbers are answered, the yes. */
    private data class Style(val opening: String, val count: (String) -> String, val rate: (String) -> String, val yes: String)

    private val styles = listOf(
        Style("{p} {q} vandhiruku, add pannidu", { it }, { it }, "aama"),
        Style("inniku {q} {p} vandhuchu", { "$it irukku" }, { "₹$it" }, "seri"),
        Style("{p} {q} வந்திருக்கு", { it }, { it }, "ஆமா"),
        Style("இன்னைக்கு {p} {q} சேர்த்துடு", { "$it இருக்கு" }, { "$it ரூபாய்" }, "சரி"),
        Style("Please add {q} {p}", { it }, { "Rs $it" }, "yes"),
        Style("Got {q} of {p} today", { it }, { "$it rupees" }, "ok"),
    )

    @Test
    fun hundredTwentyNewProductsSaidSixWays() {
        val cases = newGoods.flatMap { g -> styles.map { st ->
            val answers = g.answers.mapValues { (q, v) -> when (q) { Q.PER_PACK -> st.count(v); Q.PURCHASE, Q.SELLING -> st.rate(v); else -> v } }
            Owner("new", st.opening.replace("{p}", g.name).replace("{q}", g.qty), answers, g.exp, confirm = listOf(st.yes))
        } }
        val failures = cases.mapNotNull(::play)
        println("MATRIX inventory-new-120 ${cases.size - failures.size}/${cases.size}")
        assertTrue("${cases.size} cases", cases.size == 120)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    @Test
    fun eightyMovementsOnStockAlreadyThere() {
        // (product, quantity said, base units it is, the stock unit, the product's pack)
        val lines = listOf(
            Triple("Colgate", "2 box", 96), Triple("Colgate", "10 pieces", 10), Triple("Colgate", "1 box 6 pieces", 54),
            Triple("Rice", "2 moota", 50), Triple("Rice", "10 kg", 10), Triple("Rice", "1 bag 5 kg", 30),
            Triple("Oil", "1 box", 12), Triple("Oil", "6 bottle", 6),
        )
        fun exp(p: String, stock: Int) = when (p) {
            "Colgate" -> Exp("Colgate", "PCS", "$stock", "BOX" to "48", "28", "35", "FMCG")
            "Rice" -> Exp("Rice", "KG", "$stock", "BAG" to "25", "56", "65", "Grocery")
            else -> Exp("Oil", "BOTTLE", "$stock", "BOX" to "12", category = "Liquids")
        }
        // language → (stock in, sale, [damage, wastage, customer return, supplier return]) and the yes
        val said = listOf(
            Triple("aama", listOf("{p} {q} vandhuchu", "{p} {q} vithuten"),
                listOf("{p} {q} damage aachu", "{p} {q} wastage", "{p} {q} customer return vandhuchu", "{p} {q} supplier-ku return anuppitten")),
            Triple("ஆமா", listOf("{p} {q} வந்திருக்கு", "{p} {q} விற்றேன்"),
                listOf("{p} {q} சேதம்", "{p} {q} வீணா போச்சு", "{p} {q} customer return", "{p} {q} supplier return")),
            Triple("yes", listOf("Received {q} {p}", "Sold {q} {p}"),
                listOf("{q} {p} damaged", "{q} {p} wasted", "Customer returned {q} {p}", "Returned {q} {p} to supplier")),
        )
        val start = mapOf("Colgate" to 240, "Rice" to 250, "Oil" to 240)
        val cases = mutableListOf<Owner>()
        for ((yes, inOut, reasons) in said) lines.forEachIndexed { i, (p, q, n) ->
            val s0 = start.getValue(p)
            fun o(t: String, stock: Int) = Owner("stocked", t.replace("{p}", p).replace("{q}", q), exp = exp(p, stock), confirm = listOf(yes), stocked = true, moves = 1)
            cases += o(inOut[0], s0 + n)
            cases += o(inOut[1], s0 - n)
            val r = i % 4
            cases += o(reasons[r], if (r == 2) s0 + n else s0 - n)
        }
        // Safety on stock already there.
        cases += Owner("safety", "Colgate 2 box vandhuchu", exp = exp("Colgate", 240), confirm = listOf("venam"), stocked = true, tap = false, moves = 0)
        cases += Owner("safety", "Colgate 2 box வந்திருக்கு", exp = exp("Colgate", 240), confirm = listOf("வேண்டாம்"), stocked = true, tap = false, moves = 0)
        cases += Owner("safety", "Colgate 300 pieces sale", exp = exp("Colgate", 240), stocked = true, moves = 0)
        cases += Owner("safety", "Colgate 2 box vandhuchu", exp = exp("Colgate", 384), pre = listOf("illa 3 box dhaan"), stocked = true, moves = 1)
        // "petti" is not a unit Kai knows: it asks pieces or boxes (never guesses); the owner says box.
        cases += Owner("safety", "Colgate 2 petti vandhuchu", a(Q.UNIT to "box"), exp("Colgate", 336), stocked = true, moves = 1)
        cases += Owner("safety", "Colgate 5 vandhuchu", a(Q.UNIT to "box"), exp("Colgate", 480), stocked = true, moves = 1)
        cases += Owner("safety", "Colgate actual 230 pieces dhaan", exp = exp("Colgate", 230), stocked = true, moves = 1)
        cases += Owner("safety", "Rice 2 moota vandhuchu", exp = exp("Rice", 300), stocked = true, tapTwice = true, moves = 1)
        val failures = cases.mapNotNull(::play)
        println("MATRIX inventory-stocked-80 ${cases.size - failures.size}/${cases.size}")
        assertTrue("${cases.size} cases", cases.size == 80)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------ Tamil Nadu shops (owner: "Mutta oru tray" — check every kind of shop)


    private val tamilNadu: List<Owner> = listOf(
        // Kaaikari / pazham kadai
        Owner("kaaikari", "Thakkali 20 kg vandhuchu", a(Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Thakkali", "KG", "20", null, "30", "40", VG),
            then = listOf(St("Thakkali 5 kg vithuten", stock = "15"))),
        Owner("kaaikari", "Vengayam 2 moota vandhuchu", a(Q.PER_PACK to "50", Q.PURCHASE to "1500", Q.SELLING to "40"), Exp("Vengayam", "KG", "100", "BAG" to "50", "30", "40", VG)),
        Owner("kaaikari", "Urulai kizhangu 25 kg add pannu", a(Q.PURCHASE to "28", Q.SELLING to "35"), Exp("Urulai Kizhangu", "KG", "25", null, "28", "35", VG)),
        Owner("pazham", "Vazhaipazham 10 seepu vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "48", Q.SELLING to "5"),
            Exp("Vazhaipazham", "PCS", "120", "SEEPU" to "12", "4", "5", VG), then = listOf(St("Vazhaipazham 1 seepu vithuten", stock = "108"))),
        Owner("pazham", "Vazhaipazham 2 thaar vandhuchu", a(Q.PER_PACK to "150", Q.PURCHASE to "450", Q.SELLING to "5"), Exp("Vazhaipazham", "PCS", "300", "THAAR" to "150", "3", "5", VG)),
        Owner("kaaikari", "Thengai 100 vandhuchu", a(Q.UNIT to "pieces", Q.PURCHASE to "20", Q.SELLING to "30"), Exp("Thengai", "PCS", "100", null, "20", "30", VG)),
        Owner("kaaikari", "Thengai 2 moota vandhuchu", a(Q.PER_PACK to "50", Q.PURCHASE to "1000", Q.SELLING to "30"), Exp("Thengai", "PCS", "100", "BAG" to "50", "20", "30", VG)),
        // Poo kadai / pooja store
        Owner("poo", "Malli poo 50 muzham vandhuchu", a(Q.PURCHASE to "10", Q.SELLING to "15"), Exp("Malli Poo", "MUZHAM", "50", null, "10", "15", PJ),
            then = listOf(St("Malli poo 10 muzham sale", stock = "40"))),
        Owner("poo", "Malli poo 2 kg vandhuchu", a(Q.PURCHASE to "400", Q.SELLING to "600"), Exp("Malli Poo", "KG", "2", null, "400", "600", PJ)),
        Owner("pooja", "Karpooram 20 packet vandhuchu", a(Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Karpooram", "PACK", "20", null, "30", "40", PJ)),
        Owner("pooja", "Kungumam 30 packet vandhuchu", a(Q.PURCHASE to "10", Q.SELLING to "15"), Exp("Kungumam", "PACK", "30", null, "10", "15", PJ)),
        Owner("pooja", "Agarbathi 5 box vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "25", Q.SELLING to "35"), Exp("Agarbathi", "PCS", "60", "BOX" to "12", "25", "35", PJ)),
        Owner("pooja", "Vilakku ennai 12 bottle vandhuchu", a(Q.SIZE to "1 litre", Q.PURCHASE to "120", Q.SELLING to "150"), Exp("Vilakku Ennai", "BOTTLE", "12", null, "120", "150", PJ)),
        // Kozhi / meen / mutton kadai
        Owner("kozhi", "Kozhi 15 kg vandhuchu", a(Q.PURCHASE to "180", Q.SELLING to "220"), Exp("Kozhi", "KG", "15", null, "180", "220", MT),
            then = listOf(St("Kozhi 2 kg sale", stock = "13"))),
        Owner("meen", "Meen 10 kg vandhuchu", a(Q.PURCHASE to "250", Q.SELLING to "320"), Exp("Meen", "KG", "10", null, "250", "320", MT)),
        Owner("mutton", "Mutton 5 kg vandhuchu", a(Q.PURCHASE to "700", Q.SELLING to "800"), Exp("Mutton", "KG", "5", null, "700", "800", MT)),
        // Paal / dairy
        Owner("paal", "Paal 50 packet vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "25"), Exp("Paal", "PACK", "50", null, "22", "25", B)),
        Owner("paal", "Thayir 20 packet vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "25", Q.SELLING to "30"), Exp("Thayir", "PACK", "20", null, "25", "30", B)),
        Owner("paal", "Paneer 10 packet add pannu", a(Q.PURCHASE to "80", Q.SELLING to "95"), Exp("Paneer", "PACK", "10", null, "80", "95", B)),
        // Bakery / sweet stall
        Owner("bakery", "Bun 3 tray vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "60", Q.SELLING to "8"), Exp("Bun", "PCS", "36", "TRAY" to "12", "5", "8", BK)),
        Owner("bakery", "Rusk 10 packet vandhuchu", a(Q.PURCHASE to "35", Q.SELLING to "45"), Exp("Rusk", "PACK", "10", null, "35", "45", BK)),
        Owner("bakery", "Cake 5 piece add pannu", a(Q.PURCHASE to "400", Q.SELLING to "550"), Exp("Cake", "PCS", "5", null, "400", "550", BK)),
        Owner("sweets", "Mysore pak 3 kg add pannu", a(Q.PURCHASE to "400", Q.SELLING to "500"), Exp("Mysore Pak", "KG", "3", null, "400", "500", SN)),
        Owner("sweets", "Laddu 50 piece add pannu", a(Q.PURCHASE to "8", Q.SELLING to "10"), Exp("Laddu", "PCS", "50", null, "8", "10", SN)),
        // Maligai
        Owner("maligai", "Tea thool 5 kg vandhuchu", a(Q.PURCHASE to "300", Q.SELLING to "380"), Exp("Tea Thool", "KG", "5", null, "300", "380", G)),
        Owner("maligai", "Puli 10 kg vandhuchu", a(Q.PURCHASE to "120", Q.SELLING to "150"), Exp("Puli", "KG", "10", null, "120", "150", G)),
        Owner("maligai", "Milagai thool 5 kg vandhuchu", a(Q.PURCHASE to "200", Q.SELLING to "260"), Exp("Milagai Thool", "KG", "5", null, "200", "260", G)),
        Owner("maligai", "Kadugu 2 kg vandhuchu", a(Q.PURCHASE to "100", Q.SELLING to "130"), Exp("Kadugu", "KG", "2", null, "100", "130", G)),
        Owner("maligai", "Pottu kadalai 10 kg vandhuchu", a(Q.PURCHASE to "110", Q.SELLING to "130"), Exp("Pottu Kadalai", "KG", "10", null, "110", "130", G)),
        Owner("maligai", "Vellam 1 moota vandhuchu", a(Q.PER_PACK to "30", Q.PURCHASE to "1500", Q.SELLING to "60"), Exp("Vellam", "KG", "30", "BAG" to "30", "50", "60", G)),
        Owner("maligai", "Nallennai 5 dabba vandhuchu", a(Q.PER_PACK to "15", Q.PURCHASE to "270", Q.SELLING to "300"), Exp("Nallennai", "LITRE", "75", "CAN" to "15", "270", "300", G)),
        Owner("muttai", "Muttai 5 tray vandhuchu", a(Q.PER_PACK to "30", Q.PURCHASE to "150", Q.SELLING to "6"), Exp("Muttai", "PCS", "150", "TRAY" to "30", "5", "6", G)),
        // Fancy / stationery / medical / petti kadai ("petti" is each owner's own word: Kai asks pieces or boxes — never guesses)
        Owner("fancy", "Valayal 5 dozen vandhuchu", a(Q.PURCHASE to "3", Q.SELLING to "5"), Exp("Valayal", "PCS", "60", null, "3", "5", GN)),
        Owner("stationery", "Notebook 2 kattu vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Notebook", "PCS", "24", "BUNDLE" to "12", "30", "40", ST)),
        Owner("stationery", "Pencil 4 petti vandhuchu", a(Q.UNIT to "box", Q.PER_PACK to "10", Q.PURCHASE to "3", Q.SELLING to "5"), Exp("Pencil", "PCS", "40", "BOX" to "10", "3", "5", ST)),
        Owner("stationery", "Pen 2 petti vandhuchu", a(Q.UNIT to "box", Q.PER_PACK to "50", Q.PURCHASE to "4", Q.SELLING to "5"), Exp("Pen", "PCS", "100", "BOX" to "50", "4", "5", ST)),
        // A strip of tablets: bought and sold by the strip, kept in tablets.
        Owner("medical", "Paracetamol 20 strip vandhuchu", a(Q.PER_PACK to "10", Q.PURCHASE to "15", Q.SELLING to "20"), Exp("Paracetamol", "TABLET", "200", "STRIP" to "10", "1.5", "2", PH)),
        Owner("medical", "Cough syrup 12 bottle add pannu", a(Q.SIZE to "100 ml", Q.PURCHASE to "60", Q.SELLING to "85"), Exp("Cough Syrup", "BOTTLE", "12", null, "60", "85", PH)),
        Owner("petti kadai", "Beedi 10 kattu vandhuchu", a(Q.PER_PACK to "25", Q.PURCHASE to "0.8", Q.SELLING to "1"), Exp("Beedi", "PCS", "250", "BUNDLE" to "25", "0.8", "1", GN)),
        Owner("petti kadai", "Goli soda 5 crate vandhuchu", a(Q.PER_PACK to "24", Q.PURCHASE to "8", Q.SELLING to "15"), Exp("Goli Soda", "BOTTLE", "120", "CASE" to "24", "8", "15", B)),
        Owner("ilai", "Ilai 10 kattu vandhuchu", a(Q.PER_PACK to "100", Q.PURCHASE to "1.5", Q.SELLING to "2"), Exp("Ilai", "PCS", "1000", "BUNDLE" to "100", "1.5", "2", GN)),
        // Hardware / electrical / uram
        Owner("hardware", "Cement 20 moota vandhuchu", a(Q.SIZE to "50 kg", Q.PURCHASE to "380", Q.SELLING to "420"), Exp("Cement", "BAG", "20", null, "380", "420", H),
            then = listOf(St("Cement 5 moota sale", stock = "15"))),
        Owner("hardware", "Kambi 100 kg vandhuchu", a(Q.SIZE to "8mm", Q.PURCHASE to "65", Q.SELLING to "72"), Exp("Kambi", "KG", "100", null, "65", "72", H)),
        Owner("hardware", "Paint 4 bucket vandhuchu", a(Q.SIZE to "20 litre", Q.PURCHASE to "2500", Q.SELLING to "3000"), Exp("Paint", "BUCKET", "4", null, "2500", "3000", H)),
        Owner("electrical", "Wire 5 churul vandhuchu", a(Q.PER_PACK to "90", Q.SIZE to "1.5 sqmm", Q.PURCHASE to "13", Q.SELLING to "17"),
            Exp("Wire", "METER", "450", "COIL" to "90", "13", "17", EL), then = listOf(St("Wire 1 churul vithuten", stock = "360"))),
        Owner("uram", "Uram 20 moota vandhuchu", a(Q.PURCHASE to "1300", Q.SELLING to "1350"), Exp("Uram", "BAG", "20", null, "1300", "1350", AG)),
        // Textiles / footwear / vessels / plastic
        Owner("jauli", "Thundu 3 dozen vandhuchu", a(Q.PURCHASE to "60", Q.SELLING to "90"), Exp("Thundu", "PCS", "36", null, "60", "90", W)),
        Owner("jauli", "Lungi 2 kattu vandhuchu", a(Q.PER_PACK to "20", Q.PURCHASE to "150", Q.SELLING to "220"), Exp("Lungi", "PCS", "40", "BUNDLE" to "20", "150", "220", W)),
        Owner("cheruppu", "Cheruppu 10 jodi vandhuchu", a(Q.PURCHASE to "120", Q.SELLING to "199"), Exp("Cheruppu", "PAIR", "10", null, "120", "199", FW)),
        Owner("paathiram", "Kodam 5 piece add pannu", a(Q.PURCHASE to "450", Q.SELLING to "550"), Exp("Kodam", "PCS", "5", null, "450", "550", KW)),
        Owner("paathiram", "Paathiram 20 kg vandhuchu", a(Q.PURCHASE to "300", Q.SELLING to "380"), Exp("Paathiram", "KG", "20", null, "300", "380", KW)),
        Owner("plastic", "Bucket 10 piece vandhuchu", a(Q.PURCHASE to "80", Q.SELLING to "120"), Exp("Bucket", "PCS", "10", null, "80", "120", KW)),
        // Tamil script
        Owner("kaaikari", "தக்காளி 20 கிலோ வந்திருக்கு", a(Q.PURCHASE to "30", Q.SELLING to "40"), Exp("தக்காளி", "KG", "20", null, "30", "40", VG), confirm = listOf("ஆமா")),
        Owner("kaaikari", "வெங்காயம் 2 மூட்டை வந்திருக்கு", a(Q.PER_PACK to "50", Q.PURCHASE to "1500", Q.SELLING to "40"),
            Exp("வெங்காயம்", "KG", "100", "BAG" to "50", "30", "40", VG), confirm = listOf("ஆமா")),
        Owner("poo", "மல்லிப்பூ 50 முழம் வந்திருக்கு", a(Q.PURCHASE to "10", Q.SELLING to "15"), Exp("மல்லிப்பூ", "MUZHAM", "50", null, "10", "15", PJ), confirm = listOf("ஆமா")),
        Owner("paal", "பால் 50 பாக்கெட் வந்திருக்கு", a(Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "25"), Exp("பால்", "PACK", "50", null, "22", "25", B), confirm = listOf("ஆமா")),
        Owner("hardware", "சிமெண்ட் 20 மூட்டை வந்திருக்கு", a(Q.SIZE to "50 kg", Q.PURCHASE to "380", Q.SELLING to "420"), Exp("சிமெண்ட்", "BAG", "20", null, "380", "420", H), confirm = listOf("ஆமா")),
        Owner("pazham", "வாழைப்பழம் 10 சீப்பு வந்திருக்கு", a(Q.PER_PACK to "12", Q.PURCHASE to "48", Q.SELLING to "5"),
            Exp("வாழைப்பழம்", "PCS", "120", "SEEPU" to "12", "4", "5", VG), confirm = listOf("ஆமா")),
        Owner("kozhi", "கோழி 15 கிலோ வந்திருக்கு", a(Q.PURCHASE to "180", Q.SELLING to "220"), Exp("கோழி", "KG", "15", null, "180", "220", MT), confirm = listOf("ஆமா")),
        Owner("uram", "உரம் 10 மூட்டை வந்திருக்கு", a(Q.PURCHASE to "1300", Q.SELLING to "1350"), Exp("உரம்", "BAG", "10", null, "1300", "1350", AG), confirm = listOf("ஆமா")),
        Owner("kaaikari", "தேங்காய் 1 மூட்டை வந்திருக்கு", a(Q.PER_PACK to "50", Q.PURCHASE to "1000", Q.SELLING to "30"),
            Exp("தேங்காய்", "PCS", "50", "BAG" to "50", "20", "30", VG), confirm = listOf("ஆமா")),
        Owner("muttai", "முட்டை 2 ட்ரே வந்திருக்கு", a(Q.PER_PACK to "30", Q.PURCHASE to "150", Q.SELLING to "6"), Exp("முட்டை", "PCS", "60", "TRAY" to "30", "5", "6", G), confirm = listOf("ஆமா")),
    )

    @Test
    fun tamilNaduShops() {
        val failures = tamilNadu.mapNotNull(::play)
        println("MATRIX inventory-tamil-nadu-shops ${tamilNadu.size - failures.size}/${tamilNadu.size}")
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    @Test
    fun payingIsNeverTakenAsAStockBundle() {
        // "kattu" is a bundle ("Ilai 10 kattu vandhuchu") and also "pay" ("EB bill 500 kattu"): money words never become stock.
        for (said in listOf("EB bill 500 kattu", "current bill kattu", "Selvam 500 kattu", "rent 5000 kattiten", "₹500 kattu")) {
            val shop = Inventory()
            val k = KaiAgent(KaiBusinessBrain(Books()), Books(), shop, { now })
            val t = runBlocking { k.ask(said) }
            assertTrue("$said → ${t.reply.text}", !t.reply.text.contains("bundle") && asked(t.reply.text) == null)
            runBlocking { k.ask("aama") }
            assertTrue("$said saved stock", shop.items.isEmpty() && shop.moves.isEmpty())
        }
    }

    // ------------------------------------------------------------------ the owner's 20 shop categories, as owners say them

    private val twenty: List<Owner> = listOf(
        // 1. Grocery & Staples — kg, gram, bag, sack, litre, packet
        Owner("1 grocery", "Arisi 10 moota vandhuchu", a(Q.PER_PACK to "25", Q.PURCHASE to "1400", Q.SELLING to "65"), Exp("Arisi", "KG", "250", "BAG" to "25", "56", "65", G),
            then = listOf(St("Arisi 5 kg vithuten", stock = "245"))),
        Owner("1 grocery", "Sugar 2 sack vandhuchu", a(Q.PER_PACK to "50", Q.PURCHASE to "2000", Q.SELLING to "45"), Exp("Sugar", "KG", "100", "BAG" to "50", "40", "45", G)),
        Owner("1 grocery", "Toor dal 25 kg add pannu", a(Q.PURCHASE to "120", Q.SELLING to "140"), Exp("Toor Dal", "KG", "25", null, "120", "140", G)),
        Owner("1 grocery", "Sunflower oil 20 litre vandhuchu", a(Q.PURCHASE to "140", Q.SELLING to "160"), Exp("Sunflower Oil", "LITRE", "20", null, "140", "160", G)),
        Owner("1 grocery", "Gold winner oil 2 box vandhuchu", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "160", Q.SELLING to "185"),
            Exp("Gold Winner Oil", "BOTTLE", "24", "BOX" to "12", "160", "185", G, size = "1 litre")),
        Owner("1 grocery", "Ghee 20 packet vandhuchu", a(Q.SIZE to "200 ml", Q.PURCHASE to "120", Q.SELLING to "140"), Exp("Ghee", "PACK", "20", null, "120", "140", G, size = "200 ml")),
        Owner("1 grocery", "Cashew 5 kg vandhuchu", a(Q.PURCHASE to "700", Q.SELLING to "850"), Exp("Cashew", "KG", "5", null, "700", "850", G)),
        Owner("1 grocery", "Rava 30 packet add pannu", a(Q.PURCHASE to "40", Q.SELLING to "48"), Exp("Rava", "PACK", "30", null, "40", "48", G)),
        Owner("1 grocery", "பருப்பு 25 கிலோ வந்திருக்கு", a(Q.PURCHASE to "120", Q.SELLING to "140"), Exp("பருப்பு", "KG", "25", null, "120", "140", G), confirm = listOf("ஆமா")),
        // 2. FMCG & Personal Care — piece, box, tube, bottle, ml, gram
        Owner("2 fmcg", "Colgate 10 tube vandhuchu", a(Q.SIZE to "100 g", Q.PURCHASE to "45", Q.SELLING to "55"), Exp("Colgate", "PCS", "10", null, "45", "55", F, size = "100 g")),
        Owner("2 fmcg", "Toothbrush 2 box vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "15", Q.SELLING to "20"), Exp("Toothbrush", "PCS", "24", "BOX" to "12", "15", "20", F)),
        Owner("2 fmcg", "Lux soap 5 box add pannu", a(Q.PER_PACK to "24", Q.SIZE to "100 g", Q.PURCHASE to "30", Q.SELLING to "38"), Exp("Lux Soap", "PCS", "120", "BOX" to "24", "30", "38", F)),
        Owner("2 fmcg", "Hair oil 12 bottle vandhuchu", a(Q.SIZE to "200 ml", Q.PURCHASE to "90", Q.SELLING to "110"), Exp("Hair Oil", "BOTTLE", "12", null, "90", "110", F, size = "200 ml")),
        Owner("2 fmcg", "Face wash 10 piece add pannu", a(Q.SIZE to "100 g", Q.PURCHASE to "120", Q.SELLING to "150"), Exp("Face Wash", "PCS", "10", null, "120", "150", F)),
        Owner("2 fmcg", "Deodorant 2 box vandhuchu", a(Q.PER_PACK to "6", Q.SIZE to "150 ml", Q.PURCHASE to "180", Q.SELLING to "220"), Exp("Deodorant", "PCS", "12", "BOX" to "6", "180", "220", F)),
        Owner("2 fmcg", "சோப்பு 5 box வந்திருக்கு", a(Q.PER_PACK to "24", Q.PURCHASE to "30", Q.SELLING to "38"), Exp("சோப்பு", "PCS", "120", "BOX" to "24", "30", "38", F), confirm = listOf("ஆமா")),
        // 3. Snacks & Confectionery — packet, box, piece, carton, gram
        Owner("3 snacks", "Parle G 10 box vandhuchu", a(Q.PER_PACK to "60", Q.PURCHASE to "4.5", Q.SELLING to "5"), Exp("Parle G", "PCS", "600", "BOX" to "60", "4.5", "5", SN)),
        Owner("3 snacks", "Lays 5 carton vandhuchu", a(Q.PER_PACK to "40", Q.SIZE to "50 g", Q.PURCHASE to "8", Q.SELLING to "10"), Exp("Lays", "PCS", "200", "CARTON" to "40", "8", "10", SN)),
        Owner("3 snacks", "Dairymilk 3 box add pannu", a(Q.PER_PACK to "36", Q.PURCHASE to "35", Q.SELLING to "40"), Exp("Dairymilk", "PCS", "108", "BOX" to "36", "35", "40", SN)),
        Owner("3 snacks", "Mixture 10 kg vandhuchu", a(Q.PURCHASE to "160", Q.SELLING to "220"), Exp("Mixture", "KG", "10", null, "160", "220", SN)),
        Owner("3 snacks", "Murukku 20 packet vandhuchu", a(Q.SIZE to "200 g", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Murukku", "PACK", "20", null, "30", "40", SN, size = "200 g")),
        Owner("3 snacks", "Add 6 boxes of chocolates", a(Q.PER_PACK to "24", Q.PURCHASE to "10", Q.SELLING to "12"), Exp("Chocolates", "PCS", "144", "BOX" to "24", "10", "12", SN), confirm = listOf("yes")),
        // 4. Beverages & Dairy — bottle, crate, packet, ml, litre, piece
        Owner("4 drinks", "Coke 5 crate vandhuchu", a(Q.PER_PACK to "24", Q.SIZE to "300 ml", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Coke", "BOTTLE", "120", "CASE" to "24", "30", "40", B),
            then = listOf(St("Coke 6 bottle sale", stock = "114"))),
        Owner("4 drinks", "Bisleri 10 case vandhuchu", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "15", Q.SELLING to "20"), Exp("Bisleri", "BOTTLE", "120", "CASE" to "12", "15", "20", B)),
        Owner("4 dairy", "Milk 50 packet vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "25"), Exp("Milk", "PACK", "50", null, "22", "25", B, size = "500 ml")),
        Owner("4 dairy", "Aavin milk 2 crate vandhuchu", a(Q.PER_PACK to "25", Q.SIZE to "500 ml", Q.PURCHASE to "22", Q.SELLING to "25"), Exp("Aavin Milk", "PACK", "50", "CASE" to "25", "22", "25", B)),
        Owner("4 dairy", "Curd 20 packet vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "25", Q.SELLING to "30"), Exp("Curd", "PACK", "20", null, "25", "30", B)),
        Owner("4 dairy", "Buttermilk 30 packet vandhuchu", a(Q.SIZE to "200 ml", Q.PURCHASE to "10", Q.SELLING to "12"), Exp("Buttermilk", "PACK", "30", null, "10", "12", B)),
        Owner("4 dairy", "Paneer 10 piece add pannu", a(Q.PURCHASE to "80", Q.SELLING to "95"), Exp("Paneer", "PCS", "10", null, "80", "95", B)),
        Owner("4 dairy", "Butter 20 piece vandhuchu", a(Q.PURCHASE to "50", Q.SELLING to "56"), Exp("Butter", "PCS", "20", null, "50", "56", B)),
        // 5. Home Care & Cleaning — packet, box, bottle, litre, piece
        Owner("5 home care", "Surf excel 10 packet vandhuchu", a(Q.SIZE to "1 kg", Q.PURCHASE to "110", Q.SELLING to "130"), Exp("Surf Excel", "PACK", "10", null, "110", "130", HC, size = "1 kg")),
        Owner("5 home care", "Vim bar 3 box vandhuchu", a(Q.PER_PACK to "40", Q.SIZE to "150 g", Q.PURCHASE to "9", Q.SELLING to "10"), Exp("Vim Bar", "PCS", "120", "BOX" to "40", "9", "10", HC)),
        Owner("5 home care", "Dishwash liquid 12 bottle vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "85", Q.SELLING to "99"), Exp("Dishwash Liquid", "BOTTLE", "12", null, "85", "99", HC)),
        Owner("5 home care", "Floor cleaner 2 box add pannu", a(Q.PER_PACK to "12", Q.SIZE to "1 litre", Q.PURCHASE to "150", Q.SELLING to "180"), Exp("Floor Cleaner", "BOTTLE", "24", "BOX" to "12", "150", "180", HC)),
        Owner("5 home care", "Phenyl 20 litre vandhuchu", a(Q.PURCHASE to "40", Q.SELLING to "60"), Exp("Phenyl", "LITRE", "20", null, "40", "60", HC)),
        Owner("5 home care", "Broom 25 piece vandhuchu", a(Q.PURCHASE to "40", Q.SELLING to "60"), Exp("Broom", "PCS", "25", null, "40", "60", HC)),
        Owner("5 home care", "Garbage bags 10 roll vandhuchu", a(Q.PER_PACK to "30", Q.PURCHASE to "1.5", Q.SELLING to "2"), Exp("Garbage Bags", "PCS", "300", "ROLL" to "30", "1.5", "2", HC)),
        // 6. Garments & Textiles — piece, set, pair, metre, roll; size, colour
        Owner("6 textiles", "Saree 20 piece vandhuchu", a(Q.SIZE to "Red, Blue", Q.PURCHASE to "800", Q.SELLING to "1200"), Exp("Saree", "PCS", "20", null, "800", "1200", W, size = "Red, Blue")),
        Owner("6 textiles", "Shirt 30 piece vandhuchu", a(Q.SIZE to "M 10 L 10 XL 10 white", Q.PURCHASE to "250", Q.SELLING to "400"),
            Exp("Shirt", "PCS", "30", null, "250", "400", W, size = "M 10, L 10, XL 10 · White")),
        Owner("6 textiles", "T-shirt 2 box vandhuchu", a(Q.PER_PACK to "20", Q.PURCHASE to "150", Q.SELLING to "250"), Exp("T-shirt", "PCS", "40", "BOX" to "20", "150", "250", W)),
        Owner("6 textiles", "Dhoti 5 dozen vandhuchu", a(Q.PURCHASE to "180", Q.SELLING to "250"), Exp("Dhoti", "PCS", "60", null, "180", "250", W)),
        Owner("6 textiles", "School uniform 10 set vandhuchu", a(Q.SIZE to "24, 26, 28", Q.PURCHASE to "450", Q.SELLING to "600"), Exp("School Uniform", "SET", "10", null, "450", "600", W)),
        Owner("6 textiles", "Fabric 3 roll vandhuchu", a(Q.PER_PACK to "40", Q.PURCHASE to "80", Q.SELLING to "120"), Exp("Fabric", "METER", "120", "ROLL" to "40", "80", "120", W)),
        Owner("6 textiles", "புடவை 10 வந்திருக்கு", a(Q.UNIT to "pieces", Q.PURCHASE to "800", Q.SELLING to "1200"), Exp("புடவை", "PCS", "10", null, "800", "1200", W), confirm = listOf("ஆமா")),
        // 7. Hardware & Plumbing — piece, box, packet, metre, kg, litre
        Owner("7 hardware", "Screw 5 box vandhuchu", a(Q.PER_PACK to "100", Q.SIZE to "1 inch", Q.PURCHASE to "1", Q.SELLING to "2"), Exp("Screw", "PCS", "500", "BOX" to "100", "1", "2", H, size = "1 inch")),
        Owner("7 hardware", "Aani 10 kg vandhuchu", a(Q.SIZE to "2 inch", Q.PURCHASE to "90", Q.SELLING to "120"), Exp("Aani", "KG", "10", null, "90", "120", H)),
        Owner("7 hardware", "PVC pipe 20 piece vandhuchu", a(Q.SIZE to "1 inch", Q.PURCHASE to "150", Q.SELLING to "200"), Exp("Pvc Pipe", "PCS", "20", null, "150", "200", H)),
        Owner("7 hardware", "Tap 2 box vandhuchu", a(Q.PER_PACK to "10", Q.SIZE to "half inch", Q.PURCHASE to "120", Q.SELLING to "180"), Exp("Tap", "PCS", "20", "BOX" to "10", "120", "180", H)),
        Owner("7 hardware", "Fevicol 2 box add pannu", a(Q.PER_PACK to "24", Q.SIZE to "50 g", Q.PURCHASE to "20", Q.SELLING to "25"), Exp("Fevicol", "PCS", "48", "BOX" to "24", "20", "25", H)),
        Owner("7 hardware", "Paint 5 litre vandhuchu", a(Q.SIZE to "Asian white", Q.PURCHASE to "300", Q.SELLING to "350"), Exp("Paint", "LITRE", "5", null, "300", "350", H)),
        Owner("7 hardware", "Pipe 30 meter vandhuchu", a(Q.SIZE to "1 inch", Q.PURCHASE to "40", Q.SELLING to "55"), Exp("Pipe", "METER", "30", null, "40", "55", H)),
        // 8. Electricals & Lighting — piece, box, coil, metre, roll
        Owner("8 electrical", "LED bulb 5 box vandhuchu", a(Q.PER_PACK to "10", Q.SIZE to "9W", Q.PURCHASE to "70", Q.SELLING to "110"), Exp("Led Bulb", "PCS", "50", "BOX" to "10", "70", "110", EL, size = "9W")),
        Owner("8 electrical", "Tube light 10 piece vandhuchu", a(Q.SIZE to "20W", Q.PURCHASE to "180", Q.SELLING to "250"), Exp("Tube Light", "PCS", "10", null, "180", "250", EL)),
        Owner("8 electrical", "Wire 3 coil vandhuchu", a(Q.PER_PACK to "90", Q.SIZE to "1.5 sqmm", Q.PURCHASE to "13", Q.SELLING to "17"), Exp("Wire", "METER", "270", "COIL" to "90", "13", "17", EL),
            then = listOf(St("Wire 20 meter sale", stock = "250"))),
        Owner("8 electrical", "Switch 2 box vandhuchu", a(Q.PER_PACK to "20", Q.SIZE to "6A", Q.PURCHASE to "25", Q.SELLING to "40"), Exp("Switch", "PCS", "40", "BOX" to "20", "25", "40", EL)),
        Owner("8 electrical", "Fan 4 piece vandhuchu", a(Q.SIZE to "48 inch", Q.PURCHASE to "1800", Q.SELLING to "2200"), Exp("Fan", "PCS", "4", null, "1800", "2200", EL)),
        Owner("8 electrical", "Battery 10 strip vandhuchu", a(Q.PER_PACK to "4", Q.SIZE to "AA", Q.PURCHASE to "10", Q.SELLING to "15"), Exp("Battery", "PCS", "40", "STRIP" to "4", "10", "15", EL)),
        Owner("8 electrical", "Wire 2 roll vandhuchu", a(Q.PER_PACK to "90", Q.PURCHASE to "13", Q.SELLING to "17"), Exp("Wire", "METER", "180", "ROLL" to "90", "13", "17", EL)),
        // 9. Mobile & Electronics — piece, box, set; model, compatibility
        Owner("9 mobile", "Charger 3 box vandhuchu", a(Q.PER_PACK to "10", Q.SIZE to "Type-C 25W", Q.PURCHASE to "300", Q.SELLING to "450"), Exp("Charger", "PCS", "30", "BOX" to "10", "300", "450", E, size = "Type-C 25W")),
        Owner("9 mobile", "Phone cover 50 piece vandhuchu", a(Q.SIZE to "Redmi Note 12", Q.PURCHASE to "60", Q.SELLING to "150"), Exp("Phone Cover", "PCS", "50", null, "60", "150", E, size = "Redmi Note 12")),
        Owner("9 mobile", "Tempered glass 2 box vandhuchu", a(Q.PER_PACK to "25", Q.SIZE to "Samsung A14", Q.PURCHASE to "20", Q.SELLING to "100"), Exp("Tempered Glass", "PCS", "50", "BOX" to "25", "20", "100", E)),
        Owner("9 mobile", "Earphones 20 piece vandhuchu", a(Q.SIZE to "boAt", Q.PURCHASE to "150", Q.SELLING to "299"), Exp("Earphones", "PCS", "20", null, "150", "299", E)),
        Owner("9 mobile", "Power bank 5 piece vandhuchu", a(Q.SIZE to "10000 mAh", Q.PURCHASE to "700", Q.SELLING to "999"), Exp("Powerbank", "PCS", "5", null, "700", "999", E)),
        Owner("9 mobile", "Speaker 4 set vandhuchu", a(Q.SIZE to "JBL", Q.PURCHASE to "900", Q.SELLING to "1299"), Exp("Speaker", "SET", "4", null, "900", "1299", E)),
        // 10. Stationery & Office Supplies — piece, pack, bundle, box, ream
        Owner("10 stationery", "Notebook 5 bundle vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Notebook", "PCS", "60", "BUNDLE" to "12", "30", "40", ST)),
        Owner("10 stationery", "Pen 10 box vandhuchu", a(Q.PER_PACK to "10", Q.PURCHASE to "4", Q.SELLING to "5"), Exp("Pen", "PCS", "100", "BOX" to "10", "4", "5", ST)),
        Owner("10 stationery", "A4 paper 5 ream vandhuchu", a(Q.PURCHASE to "280", Q.SELLING to "320"), Exp("A4 Paper", "REAM", "5", null, "280", "320", ST)),
        Owner("10 stationery", "Eraser 2 box vandhuchu", a(Q.PER_PACK to "20", Q.PURCHASE to "3", Q.SELLING to "5"), Exp("Eraser", "PCS", "40", "BOX" to "20", "3", "5", ST)),
        Owner("10 stationery", "File 50 piece vandhuchu", a(Q.PURCHASE to "10", Q.SELLING to "15"), Exp("File", "PCS", "50", null, "10", "15", ST)),
        Owner("10 stationery", "Marker 2 pack vandhuchu", a(Q.PURCHASE to "100", Q.SELLING to "120"), Exp("Marker", "PACK", "2", null, "100", "120", ST)),
        // 11. Footwear & Accessories — pair, piece, set; size, colour
        Owner("11 footwear", "Chappal 10 jodi vandhuchu", a(Q.SIZE to "7, 8, 9 black", Q.PURCHASE to "120", Q.SELLING to "199"), Exp("Chappal", "PAIR", "10", null, "120", "199", FW, size = "7, 8, 9 black")),
        Owner("11 footwear", "Shoes 2 box vandhuchu", a(Q.PER_PACK to "6", Q.PURCHASE to "600", Q.SELLING to "899"), Exp("Shoes", "PAIR", "12", "BOX" to "6", "600", "899", FW)),
        Owner("11 footwear", "Socks 3 dozen vandhuchu", a(Q.PURCHASE to "30", Q.SELLING to "50"), Exp("Socks", "PCS", "36", null, "30", "50", FW)),
        Owner("11 footwear", "Belt 20 piece vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "250"), Exp("Belt", "PCS", "20", null, "150", "250", FW)),
        Owner("11 footwear", "Wallet 10 piece vandhuchu", a(Q.PURCHASE to "200", Q.SELLING to "350"), Exp("Wallet", "PCS", "10", null, "200", "350", FW)),
        Owner("11 footwear", "School bag 10 piece vandhuchu", a(Q.PURCHASE to "350", Q.SELLING to "550"), Exp("School Bag", "PCS", "10", null, "350", "550", FW)),
        // 12. Kitchenware & Household — piece, set, dozen, pack
        Owner("12 kitchen", "Steel plate 2 dozen vandhuchu", a(Q.PURCHASE to "80", Q.SELLING to "120"), Exp("Steel Plate", "PCS", "24", null, "80", "120", KW)),
        Owner("12 kitchen", "Tumbler 5 dozen vandhuchu", a(Q.PURCHASE to "40", Q.SELLING to "60"), Exp("Tumbler", "PCS", "60", null, "40", "60", KW)),
        Owner("12 kitchen", "Cooker 4 piece vandhuchu", a(Q.PURCHASE to "1500", Q.SELLING to "1900"), Exp("Cooker", "PCS", "4", null, "1500", "1900", KW)),
        Owner("12 kitchen", "Lunch box 20 piece vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "220"), Exp("Lunch Box", "PCS", "20", null, "150", "220", KW)),
        Owner("12 kitchen", "Vessel set 5 set vandhuchu", a(Q.PURCHASE to "1200", Q.SELLING to "1600"), Exp("Vessel Set", "SET", "5", null, "1200", "1600", KW)),
        Owner("12 kitchen", "Plastic container 3 dozen vandhuchu", a(Q.PURCHASE to "50", Q.SELLING to "80"), Exp("Plastic Container", "PCS", "36", null, "50", "80", KW)),
        // 13. Pharmacy & Medical — strip, tablet, bottle, box, piece; batch and expiry
        Owner("13 pharmacy", "Dolo 20 strip vandhuchu", a(Q.PER_PACK to "15", Q.BATCH to "B2245", Q.EXPIRY to "03/2027", Q.PURCHASE to "30", Q.SELLING to "33"),
            Exp("Dolo", "TABLET", "300", "STRIP" to "15", "2", "2.2", PH, batch = "B2245", expiry = "03/2027"),
            then = listOf(St("Dolo 2 strip sale", stock = "270"))),
        Owner("13 pharmacy", "Crocin 10 box vandhuchu", a(Q.PER_PACK to "10", Q.PURCHASE to "300", Q.SELLING to "35"), Exp("Crocin", "STRIP", "100", "BOX" to "10", "30", "35", PH)),
        Owner("13 pharmacy", "Cough syrup 20 bottle vandhuchu", a(Q.SIZE to "100 ml", Q.BATCH to "CS12", Q.EXPIRY to "12/2026", Q.PURCHASE to "60", Q.SELLING to "85"),
            Exp("Cough Syrup", "BOTTLE", "20", null, "60", "85", PH, size = "100 ml", batch = "CS12", expiry = "12/2026")),
        Owner("13 pharmacy", "Bandage 5 box vandhuchu", a(Q.PER_PACK to "100", Q.PURCHASE to "100", Q.SELLING to "2"), Exp("Bandage", "PCS", "500", "BOX" to "100", "1", "2", PH)),
        Owner("13 pharmacy", "Paracetamol 50 tablet vandhuchu", a(Q.EXPIRY to "15/08/2027", Q.PURCHASE to "1", Q.SELLING to "2"),
            Exp("Paracetamol", "TABLET", "50", null, "1", "2", PH, expiry = "15/08/2027")),
        Owner("13 pharmacy", "Mask 10 box vandhuchu", a(Q.PER_PACK to "50", Q.PURCHASE to "100", Q.SELLING to "5"), Exp("Mask", "PCS", "500", "BOX" to "50", "2", "5", PH)),
        Owner("13 pharmacy", "மாத்திரை 10 strip வந்திருக்கு", a(Q.PER_PACK to "10", Q.PURCHASE to "20", Q.SELLING to "25"), Exp("மாத்திரை", "TABLET", "100", "STRIP" to "10", "2", "2.5", PH), confirm = listOf("ஆமா")),
        // 14. Fruits & Vegetables — kg, gram, piece, dozen, crate, basket
        Owner("14 veg", "Thakkali 2 crate vandhuchu", a(Q.PER_PACK to "25", Q.PURCHASE to "500", Q.SELLING to "30"), Exp("Thakkali", "KG", "50", "CASE" to "25", "20", "30", VG)),
        Owner("14 veg", "Vengayam 3 moota vandhuchu", a(Q.PER_PACK to "50", Q.PURCHASE to "1500", Q.SELLING to "40"), Exp("Vengayam", "KG", "150", "BAG" to "50", "30", "40", VG)),
        Owner("14 fruits", "Vazhaipazham 5 dozen vandhuchu", a(Q.PURCHASE to "4", Q.SELLING to "5"), Exp("Vazhaipazham", "PCS", "60", null, "4", "5", VG)),
        Owner("14 veg", "Keerai 20 kattu vandhuchu", a(Q.PURCHASE to "10", Q.SELLING to "15"), Exp("Keerai", "BUNDLE", "20", null, "10", "15", VG)),
        Owner("14 fruits", "Mango 2 basket vandhuchu", a(Q.PER_PACK to "20", Q.PURCHASE to "1600", Q.SELLING to "60"), Exp("Mango", "KG", "40", "BASKET" to "20", "80", "60", VG)),
        Owner("14 veg", "Thengai 100 piece vandhuchu", a(Q.PURCHASE to "20", Q.SELLING to "30"), Exp("Thengai", "PCS", "100", null, "20", "30", VG)),
        Owner("14 fruits", "Apple 10 kg vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "200"), Exp("Apple", "KG", "10", null, "150", "200", VG)),
        // 15. Pooja & Religious Items — packet, box, piece, gram, ml
        Owner("15 pooja", "Agarbathi 5 box vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "25", Q.SELLING to "35"), Exp("Agarbathi", "PCS", "60", "BOX" to "12", "25", "35", PJ)),
        Owner("15 pooja", "Karpooram 20 packet vandhuchu", a(Q.PURCHASE to "30", Q.SELLING to "40"), Exp("Karpooram", "PACK", "20", null, "30", "40", PJ)),
        Owner("15 pooja", "Kumkum 2 box vandhuchu", a(Q.PER_PACK to "24", Q.PURCHASE to "8", Q.SELLING to "10"), Exp("Kumkum", "PCS", "48", "BOX" to "24", "8", "10", PJ)),
        Owner("15 pooja", "Vilakku ennai 24 bottle vandhuchu", a(Q.SIZE to "500 ml", Q.PURCHASE to "90", Q.SELLING to "110"), Exp("Vilakku Ennai", "BOTTLE", "24", null, "90", "110", PJ)),
        Owner("15 pooja", "Thiri 50 packet vandhuchu", a(Q.PURCHASE to "5", Q.SELLING to "10"), Exp("Thiri", "PACK", "50", null, "5", "10", PJ)),
        Owner("15 pooja", "Oil lamp 6 piece vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "250"), Exp("Oil Lamp", "PCS", "6", null, "150", "250", PJ)),
        Owner("15 pooja", "Vibhuti 2 kg vandhuchu", a(Q.PURCHASE to "200", Q.SELLING to "300"), Exp("Vibhuti", "KG", "2", null, "200", "300", PJ)),
        // 16. Agriculture & Gardening — kg, gram, bag, packet, litre, piece
        Owner("16 agri", "Urea 20 moota vandhuchu", a(Q.PURCHASE to "270", Q.SELLING to "300"), Exp("Urea", "BAG", "20", null, "270", "300", AG)),
        Owner("16 agri", "Seeds 50 packet vandhuchu", a(Q.PURCHASE to "20", Q.SELLING to "30"), Exp("Seeds", "PACK", "50", null, "20", "30", AG)),
        Owner("16 garden", "Pot 30 piece vandhuchu", a(Q.PURCHASE to "40", Q.SELLING to "70"), Exp("Pot", "PCS", "30", null, "40", "70", AG)),
        Owner("16 agri", "Pesticide 12 bottle vandhuchu", a(Q.PURCHASE to "350", Q.SELLING to "420"), Exp("Pesticide", "BOTTLE", "12", null, "350", "420", AG)),
        Owner("16 garden", "Compost 10 bag vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "200"), Exp("Compost", "BAG", "10", null, "150", "200", AG)),
        Owner("16 garden", "Hose 3 roll vandhuchu", a(Q.PER_PACK to "30", Q.PURCHASE to "25", Q.SELLING to "35"), Exp("Hose", "METER", "90", "ROLL" to "30", "25", "35", AG)),
        // 17. Automobile Spare Parts — piece, set, litre, bottle, pack; vehicle / model
        Owner("17 auto", "Engine oil 24 bottle vandhuchu", a(Q.SIZE to "1 litre", Q.PURCHASE to "350", Q.SELLING to "420"), Exp("Engine Oil", "BOTTLE", "24", null, "350", "420", AU, size = "1 litre")),
        Owner("17 auto", "Spark plug 2 box vandhuchu", a(Q.PER_PACK to "10", Q.SIZE to "Splendor", Q.PURCHASE to "90", Q.SELLING to "130"), Exp("Spark Plug", "PCS", "20", "BOX" to "10", "90", "130", AU, size = "Splendor")),
        Owner("17 auto", "Brake pad 10 set vandhuchu", a(Q.SIZE to "Activa", Q.PURCHASE to "250", Q.SELLING to "350"), Exp("Brake Pad", "SET", "10", null, "250", "350", AU, size = "Activa")),
        Owner("17 auto", "Air filter 15 piece vandhuchu", a(Q.SIZE to "Pulsar 150", Q.PURCHASE to "120", Q.SELLING to "180"), Exp("Air Filter", "PCS", "15", null, "120", "180", AU)),
        Owner("17 auto", "Helmet 5 piece vandhuchu", a(Q.PURCHASE to "800", Q.SELLING to "1100"), Exp("Helmet", "PCS", "5", null, "800", "1100", AU)),
        Owner("17 auto", "Chain sprocket 4 set vandhuchu", a(Q.SIZE to "Apache", Q.PURCHASE to "900", Q.SELLING to "1200"), Exp("Chain Sprocket", "SET", "4", null, "900", "1200", AU)),
        // 18. Bakery & Fresh Foods — piece, pack, tray, kg; expiry
        Owner("18 bakery", "Bread 30 packet vandhuchu", a(Q.EXPIRY to "15/10/2026", Q.PURCHASE to "35", Q.SELLING to "40"), Exp("Bread", "PACK", "30", null, "35", "40", BK, expiry = "15/10/2026")),
        Owner("18 bakery", "Bun 4 tray vandhuchu", a(Q.PER_PACK to "12", Q.PURCHASE to "60", Q.SELLING to "8"), Exp("Bun", "PCS", "48", "TRAY" to "12", "5", "8", BK)),
        Owner("18 bakery", "Cake 3 kg add pannu", a(Q.PURCHASE to "400", Q.SELLING to "600"), Exp("Cake", "KG", "3", null, "400", "600", BK)),
        Owner("18 bakery", "Puffs 40 piece vandhuchu", a(Q.PURCHASE to "12", Q.SELLING to "18"), Exp("Puffs", "PCS", "40", null, "12", "18", BK)),
        Owner("18 bakery", "Rusk 20 packet vandhuchu", a(Q.PURCHASE to "35", Q.SELLING to "45"), Exp("Rusk", "PACK", "20", null, "35", "45", BK)),
        Owner("18 bakery", "Cupcake 2 tray vandhuchu", a(Q.PER_PACK to "6", Q.PURCHASE to "60", Q.SELLING to "15"), Exp("Cupcake", "PCS", "12", "TRAY" to "6", "10", "15", BK)),
        // 19. Toys, Gifts & Party Supplies — piece, set, pack, box
        Owner("19 toys", "Teddy 10 piece vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "250"), Exp("Teddy", "PCS", "10", null, "150", "250", TY)),
        Owner("19 party", "Balloon 20 packet vandhuchu", a(Q.PURCHASE to "40", Q.SELLING to "60"), Exp("Balloon", "PACK", "20", null, "40", "60", TY)),
        Owner("19 gifts", "Return gift 50 piece vandhuchu", a(Q.PURCHASE to "30", Q.SELLING to "50"), Exp("Return Gift", "PCS", "50", null, "30", "50", TY)),
        Owner("19 toys", "Doll 2 box vandhuchu", a(Q.PER_PACK to "6", Q.PURCHASE to "120", Q.SELLING to "199"), Exp("Doll", "PCS", "12", "BOX" to "6", "120", "199", TY)),
        Owner("19 party", "Birthday decoration 10 set vandhuchu", a(Q.PURCHASE to "150", Q.SELLING to "250"), Exp("Birthday Decoration", "SET", "10", null, "150", "250", TY)),
        Owner("19 gifts", "Wrapping paper 5 roll vandhuchu", a(Q.PER_PACK to "10", Q.PURCHASE to "8", Q.SELLING to "15"), Exp("Wrapping Paper", "PCS", "50", "ROLL" to "10", "8", "15", TY)),
        // 20. Baby Care & Hygiene
        Owner("20 baby", "Pampers 10 packet vandhuchu", a(Q.PURCHASE to "450", Q.SELLING to "550"), Exp("Pampers", "PACK", "10", null, "450", "550", BY)),
        Owner("20 baby", "Baby soap 3 box vandhuchu", a(Q.PER_PACK to "12", Q.SIZE to "75 g", Q.PURCHASE to "40", Q.SELLING to "55"), Exp("Baby Soap", "PCS", "36", "BOX" to "12", "40", "55", BY)),
        Owner("20 baby", "Baby oil 12 bottle vandhuchu", a(Q.SIZE to "100 ml", Q.PURCHASE to "90", Q.SELLING to "120"), Exp("Baby Oil", "BOTTLE", "12", null, "90", "120", BY)),
        Owner("20 baby", "Wipes 20 packet vandhuchu", a(Q.PURCHASE to "60", Q.SELLING to "80"), Exp("Wipes", "PACK", "20", null, "60", "80", BY)),
        Owner("20 baby", "Cerelac 12 piece vandhuchu", a(Q.SIZE to "300 g", Q.PURCHASE to "220", Q.SELLING to "260"), Exp("Cerelac", "PCS", "12", null, "220", "260", BY)),
        Owner("20 baby", "Feeding bottle 10 piece vandhuchu", a(Q.PURCHASE to "120", Q.SELLING to "180"), Exp("Feeding Bottle", "PCS", "10", null, "120", "180", BY)),
    )

    @Test
    fun twentyShopCategories() {
        val failures = twenty.mapNotNull(::play)
        println("MATRIX inventory-20-categories ${twenty.size - failures.size}/${twenty.size}")
        // Every one of the owner's 20 categories is covered.
        assertTrue(twenty.map { it.shop.substringBefore(' ') }.toSet().size == 20)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------ 100 owners: add a product, then a day of stock in / out

    private val baseWord = mapOf("PCS" to "pieces", "KG" to "kg", "BOTTLE" to "bottle", "PACK" to "packet", "PAIR" to "pair", "METER" to "meter",
        "LITRE" to "litre", "BAG" to "bag", "SET" to "set", "TABLET" to "tablet", "STRIP" to "strip", "REAM" to "ream", "BUNDLE" to "kattu", "MUZHAM" to "muzham")
    private val packWord = mapOf("BOX" to "box", "BAG" to "moota", "CASE" to "case", "CARTON" to "carton", "STRIP" to "strip", "TRAY" to "tray",
        "ROLL" to "roll", "COIL" to "coil", "BUNDLE" to "kattu", "BASKET" to "basket", "CAN" to "dabba")

    /** How each owner speaks after the product is in: stock in, sale, damage, customer return, a draft dropped, a stock question. */
    private data class Talk(val inn: String, val sale: String, val damage: String, val ret: String, val query: String, val no: String, val dropped: String)
    private val talks = listOf(
        Talk("{p} {q} vandhuchu", "{p} {q} vithuten", "{p} {q} damage aachu", "{p} {q} customer return vandhuchu", "{p} evlo irukku?", "venam", "cancel pannitten"),
        Talk("{p} {q} வந்திருக்கு", "{p} {q} விற்றேன்", "{p} {q} சேதம்", "{p} {q} customer return", "{p} எவ்வளவு இருக்கு?", "வேண்டாம்", "ரத்து"),
        Talk("Received {q} {p}", "Sold {q} {p}", "{q} {p} damaged", "Customer returned {q} {p}", "How much {p} is left?", "cancel", "cancel pannitten"),
    )

    private fun plainQty(q: BigDecimal) = q.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    /** One owner's day after adding the product; every step checks the stock it must leave. */
    private fun day(o: Owner, talk: Talk): List<St> {
        val e = o.exp!!
        var stock = BigDecimal(e.stock)
        val name = o.first.let { f -> e.name.takeIf { f.contains(it, ignoreCase = true) } ?: e.name }
        val base = baseWord[e.unit] ?: return emptyList()
        fun t(tpl: String, q: String) = tpl.replace("{p}", name).replace("{q}", q)
        val steps = mutableListOf<St>()
        // Stock in: a pack when the product has one, else 5 of its unit.
        val pack = e.pack
        if (pack != null && packWord[pack.first] != null) {
            stock += BigDecimal(pack.second)
            steps += St(t(talk.inn, "1 ${packWord.getValue(pack.first)}"), stock = plainQty(stock))
        } else {
            stock += BigDecimal(5)
            steps += St(t(talk.inn, "5 $base"), stock = plainQty(stock))
        }
        stock -= BigDecimal(2); steps += St(t(talk.sale, "2 $base"), stock = plainQty(stock))
        stock -= BigDecimal.ONE; steps += St(t(talk.damage, "1 $base"), stock = plainQty(stock))
        stock += BigDecimal.ONE; steps += St(t(talk.ret, "1 $base"), stock = plainQty(stock))
        // The owner starts an entry and drops it: nothing changes.
        steps += St(t(talk.inn, "3 $base"), follow = listOf(talk.no), tap = false, has = talk.dropped, stock = plainQty(stock))
        steps += St(t(talk.query, ""), tap = false, has = plainQty(stock), stock = plainQty(stock))
        return steps
    }

    @Test
    fun hundredOwnersAddThenSellAcrossAllCategories() {
        val five = twenty.groupBy { it.shop.substringBefore(' ') }.values.flatMap { it.take(5) }
        val sessions = five.mapIndexed { i, o -> o.copy(then = day(o, talks[i % talks.size])) }
        val failures = sessions.mapNotNull(::play)
        val steps = sessions.sumOf { 1 + it.then.size }
        println("MATRIX inventory-100-owner-days ${sessions.size - failures.size}/${sessions.size} owners, $steps steps")
        assertTrue("${sessions.size} owners", sessions.size == 100)
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
