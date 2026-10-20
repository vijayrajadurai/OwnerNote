package com.shopai.app.brain.chat

import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiInventoryChatTest.Inventory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A hardware shop's stock coming in (owner's Pixel 8 test, 20 Oct 2026: brand + size + unit in one line went on the wrong product
 * with the wrong quantity), typed and as the ta-IN mic writes it, each said while Kai still holds an earlier line's draft:
 *  - a product the shop doesn't keep ("Tata Tiscon", "Birla cement", "Anchor switch") is never put on one it does;
 *  - a size ("12mm", "6A", "8x4", "2 inch", "2.5 sqmm") is the product's, never added to the quantity — a size the shop's product
 *    doesn't have ("Havells switch 16 amp" with only the 6A kept) is another product;
 *  - packs of a size are counted by the pack ("arai kilo 24 dabba" = 12 kg, "50 adi 4 roll" = 200 ft, "oru roll 90 meter");
 *  - no medicine batch / expiry or phone-model question for a hardware shop's goods.
 * Product names come from the shop's own words; a new name said by mic stays as said (Kai's rule), so mic cases of new products
 * check only that nothing the shop keeps moved and the new one was made.
 */
class KaiHardwareStockInTest {
    private val now = LocalDateTime.of(2026, 10, 20, 11, 0)
    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }
    private fun shop() = Inventory().also { inv ->
        fun i(id: String, n: String, u: String, stock: String = "0") { inv.items += Inventory.Item(id, n, u, BigDecimal(stock), emptyMap(), null, null, "Hardware", null) }
        i("h1", "Finolex Wire 1.5 sqmm", "METER"); i("h2", "Ultratech Cement", "BAG", "50"); i("h3", "Fevicol SH", "KG"); i("h4", "Asian Paints Apex", "BUCKET")
        i("h5", "Garden Hose Pipe", "METER"); i("h6", "Havells Switch 6A", "PCS"); i("h7", "Greenply Plywood 12 mm", "SHEET"); i("h8", "Washer 1/2 inch", "PCS")
        i("h9", "Jaquar Tap", "PCS"); i("h10", "Wire Nails 2 inch", "KG"); i("h11", "Paint Brush 4 inch", "PCS")
    }
    private val start = mapOf("h2" to BigDecimal("50"))
    private fun all(t: KaiTurn?) = (t?.reply?.text.orEmpty() + " | " + t?.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " / ")

    /** [want]: "<product>=<stock after>", or "NEW:<word of its name>" (a new product, nothing the shop keeps moved). */
    data class Case(val typed: String, val mic: String, val want: String, val before: String? = "Ultratech cement 10 bag vandhuchu")

    private val needless = Regex("""(?i)expiry|batch|model|type-c|samsung|எக்ஸ்பைரி|பேட்ச்|மாடல்""")

    /** Plays [line] to Confirm, answering Kai's questions as an owner would; null when right, else what went wrong. */
    private fun play(line: String, c: Case, spoken: Boolean): String? {
        val inv = shop()
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        if (c.before != null) runBlocking { kai.ask(c.before) }
        var t = runBlocking { kai.ask(line) }
        val log = StringBuilder("$line → ${all(t)}")
        var offered = false
        for (step in 0 until 8) {
            if (needless.containsMatchIn(t.reply.text)) return "needless question: $log"
            if (inv.items.any { it.stock.compareTo(start[it.id] ?: BigDecimal.ZERO) != 0 }) return "saved before Confirm: $log"
            val btn = t.card?.buttons.orEmpty()
            val confirm = btn.firstOrNull { it.action is KaiAction.ConfirmStock || it.action is KaiAction.ConfirmPlan }
            if (confirm != null) { runBlocking { kai.act(confirm.action, KaiLang.TANGLISH) }; break }
            if (btn.any { it.action is KaiAction.CreateProduct }) { offered = true; break }
            val txt = t.reply.text
            val ans = when {
                Regex("""(?i)feet.{0,8}(evlo|எத்தனை) (m|meter)\b""").containsMatchIn(txt) -> "0.3048"
                Regex("""(?i)evlo (m|meter|metre)\b|எத்தனை (m|meter)\b""").containsMatchIn(txt) -> "450"
                Regex("""(?i)pieces-aa|pieces-ஆ""").containsMatchIn(txt) -> "pieces"
                Regex("""(?i)evlo pieces|எத்தனை pieces""").containsMatchIn(txt) -> "10"
                Regex("""(?i)rate|விலை|'skip'""").containsMatchIn(txt) -> "skip"
                Regex("""(?i)pannattuma|பண்ணட்டுமா""").containsMatchIn(txt) -> "aama"
                else -> return "stuck: $log"
            }
            t = runBlocking { kai.ask(ans) }
            log.append(" ↳ $ans → ${all(t)}")
        }
        val moved = inv.items.filter { it.id.startsWith("h") && it.stock.compareTo(start[it.id] ?: BigDecimal.ZERO) != 0 }
        if (c.want.startsWith("NEW:")) {
            if (moved.isNotEmpty()) return "put on ${moved.map { "${it.name}=${it.stock}" }}: $log"
            if (inv.created.isEmpty() && !offered) return "no new product: $log"
            val w = c.want.removePrefix("NEW:")
            if (!spoken && w.isNotEmpty() && inv.created.none { it.name.contains(w, true) }) return "new product named ${inv.created.map { it.name }}: $log"
            return null
        }
        val (n, q) = c.want.split("=")
        val got = moved.map { "${it.name}=${it.stock.stripTrailingZeros().toPlainString()}" }
        return if (got == listOf("$n=$q") && inv.created.isEmpty()) null else "got $got ${inv.created.map { it.name }}: $log"
    }

    @Test fun typedAndSpokenHardwareStockIn() {
        val cases = listOf(
            Case("Finolex wire 1 coil vandhuchu", "ஃபினோலெக்ஸ் வயர் 1 காயில் வந்துச்சு", "Finolex Wire 1.5 sqmm=450"),
            Case("Ultratech cement 10 moottai vandhuchu", "அல்ட்ராடெக் சிமெண்ட் 10 மூட்டை வந்துச்சு", "Ultratech Cement=60", before = null),
            Case("Supreme pipe 1 inch 20 length vandhuchu", "சுப்ரீம் பைப் 1 இன்ச் 20 லெங்த் வந்துச்சு", "NEW:Supreme"),
            Case("Tata Tiscon 12mm 100 rod vandhuchu", "டாடா டிஸ்கான் 12 எம்எம் 100 ராட் வந்துச்சு", "NEW:Tiscon"),
            Case("Anchor switch 6A 3 box vandhuchu", "ஆங்கர் ஸ்விட்ச் 6 ஆம்ப் 3 பாக்ஸ் வந்துச்சு", "NEW:Anchor"),
            Case("Philips bulb 9 watt 2 box vandhuchu", "பிலிப்ஸ் பல்ப் 9 வாட் 2 பாக்ஸ் வந்துச்சு", "NEW:Philips"),
            Case("Century plywood 8x4 10 sheet vandhuchu", "செஞ்சுரி ப்ளைவுட் 8 பை 4 10 ஷீட் வந்துச்சு", "NEW:Century"),
            Case("Fevicol arai kilo 24 dabba vandhuchu", "ஃபெவிகால் அரை கிலோ 24 டப்பா வந்துச்சு", "Fevicol SH=12"),
            Case("Apex 4 litre 10 dabba vandhuchu", "அபெக்ஸ் 4 லிட்டர் 10 டப்பா வந்துச்சு", "Asian Paints Apex=10"),
            Case("Garden pipe 50 adi 4 roll vandhuchu", "கார்டன் பைப் 50 அடி 4 ரோல் வந்துச்சு", "Garden Hose Pipe=60.96"),
            Case("Havells switch 6 amp 20 nos vandhuchu", "ஹேவல்ஸ் ஸ்விட்ச் 6 ஆம்ப் 20 நாஸ் வந்துச்சு", "Havells Switch 6A=20"),
            Case("Greenply 12 mm 5 sheet vandhuchu", "கிரீன்ப்ளை 12 எம்எம் 5 ஷீட் வந்துச்சு", "Greenply Plywood 12 mm=5"),
            Case("Washer half inch 100 nos vandhuchu", "வாஷர் அரை இன்ச் 100 நாஸ் வந்துச்சு", "Washer 1/2 inch=100"),
            Case("Nails 2 inch 5 kg vandhuchu", "நெயில்ஸ் 2 இன்ச் 5 கிலோ வந்துச்சு", "Wire Nails 2 inch=5"),
            Case("Paint brush 4 inch 12 vandhuchu", "பெயிண்ட் பிரஷ் 4 இன்ச் 12 வந்துச்சு", "Paint Brush 4 inch=12"),
            Case("Finolex wire 1.5 sqmm 3 roll vandhuchu oru roll 90 meter", "ஃபினோலெக்ஸ் வயர் 1.5 ஸ்கொயர் எம்எம் 3 ரோல் வந்துச்சு ஒரு ரோல் 90 மீட்டர்", "Finolex Wire 1.5 sqmm=270"),
            Case("rope 10 kattu vandhuchu", "கயிறு 10 கட்டு வந்துச்சு", "NEW:"),
            Case("Sintex tank 1000 litre 2 vandhuchu", "சின்டெக்ஸ் டேங்க் 1000 லிட்டர் 2 வந்துச்சு", "NEW:Sintex"),
            Case("Yale lock 5 vandhuchu", "யேல் லாக் 5 வந்துச்சு", "NEW:Yale"),
            Case("tube light 20 vandhuchu", "டியூப் லைட் 20 வந்துச்சு", "NEW:Tube"),
            Case("cable tie 4 packet vandhuchu", "கேபிள் டை 4 பாக்கெட் வந்துச்சு", "NEW:Cable"),
            Case("Havells fan 4 vandhuchu", "ஹேவல்ஸ் ஃபேன் 4 வந்துச்சு", "NEW:Fan"),
            Case("Anchor PVC tape 10 vandhuchu", "ஆங்கர் பிவிசி டேப் 10 வந்துச்சு", "NEW:Tape"),
            Case("drill bit set 3 vandhuchu", "டிரில் பிட் செட் 3 வந்துச்சு", "NEW:Drill"),
            Case("Birla cement 20 bag vandhuchu", "பிர்லா சிமெண்ட் 20 பேக் வந்துச்சு", "NEW:Birla"),
            Case("Polycab wire 2.5 sqmm 2 coil vandhuchu", "பாலிகேப் வயர் 2.5 ஸ்கொயர் எம்எம் 2 காயில் வந்துச்சு", "NEW:Polycab"),
            Case("PVC elbow 1 inch 50 vandhuchu", "பிவிசி எல்போ 1 இன்ச் 50 வந்துச்சு", "NEW:Elbow"),
            Case("Asian paints Apex 3 bucket vandhuchu", "ஏசியன் பெயிண்ட்ஸ் அபெக்ஸ் 3 பக்கெட் வந்துச்சு", "Asian Paints Apex=3"),
            Case("Jaquar tap 6 vandhuchu", "ஜாக்குவார் டேப் 6 வந்துச்சு", "Jaquar Tap=6"),
            // never fed: other brands, sizes the shop doesn't keep, other pack sizes
            Case("Ashirvad pipe 3/4 inch 15 length vandhuchu", "ஆஷிர்வாத் பைப் முக்கால் இன்ச் 15 லெங்த் வந்துச்சு", "NEW:"),
            Case("Crompton bulb 12 watt 3 box vandhuchu", "க்ராம்ப்டன் பல்ப் 12 வாட் 3 பாக்ஸ் வந்துச்சு", "NEW:"),
            Case("Jindal kambi 16mm 50 rod vandhuchu", "ஜிண்டால் கம்பி 16 எம்எம் 50 ராட் வந்துச்சு", "NEW:"),
            Case("Dalmia cement 30 moottai vandhuchu", "டால்மியா சிமெண்ட் 30 மூட்டை வந்துச்சு", "NEW:"),
            Case("Berger paint 1 litre 20 dabba vandhuchu", "பெர்ஜர் பெயிண்ட் 1 லிட்டர் 20 டப்பா வந்துச்சு", "NEW:"),
            Case("Garden pipe 100 adi 2 roll vandhuchu", "கார்டன் பைப் 100 அடி 2 ரோல் வந்துச்சு", "Garden Hose Pipe=60.96"),
            Case("Fevicol kaal kilo 40 dabba vandhuchu", "ஃபெவிகால் கால் கிலோ 40 டப்பா வந்துச்சு", "Fevicol SH=10"),
            Case("Washer mukkal inch 50 vandhuchu", "வாஷர் முக்கால் இன்ச் 50 வந்துச்சு", "NEW:"),
            Case("Havells switch 16 amp 10 vandhuchu", "ஹேவல்ஸ் ஸ்விட்ச் 16 ஆம்ப் 10 வந்துச்சு", "NEW:"),
            Case("Greenply 18 mm 4 sheet vandhuchu", "கிரீன்ப்ளை 18 எம்எம் 4 ஷீட் வந்துச்சு", "NEW:"),
            Case("Nails 3 inch 2 kg vandhuchu", "நெயில்ஸ் 3 இன்ச் 2 கிலோ வந்துச்சு", "NEW:"),
            Case("Ultratech cement 5 bag pochu", "அல்ட்ராடெக் சிமெண்ட் 5 பேக் போச்சு", "Ultratech Cement=45", before = null),
        )
        val fails = cases.flatMap { c -> listOfNotNull(play(c.typed, c, false)?.let { "[typed] $it" }, play(c.mic, c, true)?.let { "[mic] $it" }) }
        assertTrue("${fails.size} of ${cases.size * 2} fail:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
