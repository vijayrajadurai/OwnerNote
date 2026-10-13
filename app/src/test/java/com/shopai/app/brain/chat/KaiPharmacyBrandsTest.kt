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
 * The owner's pharmacy stock master (13 Oct 2026, "Pharmacy Stock Master with Brands, Dosage / Strength"): every brand in it kept
 * as a product of one shop, by brand and strength as a pharmacy names them ("Telma 40", "Pan 40", "Augmentin 625"). Each is said
 * typed and as the ta-IN mic writes it (Tamil letters), to add stock and to ask its stock: Kai takes exactly that product — never
 * another brand that sounds near it (Telma / Telmikind, Ciplox / Cipcal / Cifran, Omez / Omron, Pan 40 / Pantocid 40).
 */
class KaiPharmacyBrandsTest {
    private val now = LocalDateTime.of(2026, 10, 13, 10, 0)

    private class Books : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    /** Product name | how the mic writes it | stock unit. */
    private val shop = """
        Pigeon Nipple|பிஜன் நிப்பிள்|PCS
        Mee Mee Wipes|மீ மீ வைப்ஸ்|PACK
        Himalaya Baby Powder|ஹிமாலயா பேபி பவுடர்|PCS
        Pampers|பேம்பர்ஸ்|PACK
        Huggies|ஹக்கீஸ்|PACK
        MamyPoko|மாமி போக்கோ|PACK
        Johnsons Baby Lotion|ஜான்சன்ஸ் பேபி லோஷன்|PCS
        Sebamed Cream|செபாமெட் கிரீம்|TUBE
        Nan Pro|நான் ப்ரோ|PCS
        Lactogen|லாக்டோஜென்|PCS
        Cerelac|செரிலாக்|PCS
        Dr Trust Thermometer|டாக்டர் டிரஸ்ட் தெர்மாமீட்டர்|PCS
        Omron BP Monitor|ஓம்ரான் பிபி மானிட்டர்|PCS
        Hicks Thermometer|ஹிக்ஸ் தெர்மாமீட்டர்|PCS
        Philips Avent|பிலிப்ஸ் அவென்ட்|PCS
        Prega News|பிரேகா நியூஸ்|PCS
        Velocit|வெலோசிட்|PCS
        Whisper|விஸ்பர்|PACK
        Stayfree|ஸ்டேஃப்ரீ|PACK
        Sofy|சோஃபி|PACK
        Tynor Collar|டைனர் காலர்|PCS
        Vissco|விஸ்கோ|PCS
        Flamingo|ஃபிளமிங்கோ|PCS
        BPL|பிபிஎல்|PCS
        Rossmax|ராஸ்மேக்ஸ்|PCS
        Accu-Chek|அக்யூ செக்|BOX
        OneTouch|ஒன் டச்|BOX
        Dr Morepen|டாக்டர் மோர்பென்|PCS
        HealthSense|ஹெல்த் சென்ஸ்|PCS
        Human Actrapid|ஹியூமன் ஆக்ட்ராபிட்|VIAL
        Huminsulin|ஹுமின்சுலின்|VIAL
        Lantus|லாண்டஸ்|PCS
        Beurer|பியூரர்|PCS
        Kosmocare|காஸ்மோகேர்|PCS
        Karma|கர்மா|PCS
        Digene|டைஜீன்|BOTTLE
        Gelusil|ஜெலுசில்|BOTTLE
        ENO|ஈனோ|PACK
        Domstal 10|டாம்ஸ்டால் 10|STRIP
        Vomistop|வாமிஸ்டாப்|STRIP
        Nexpro 40|நெக்ஸ்ப்ரோ 40|STRIP
        Esoz 40|ஈசோஸ் 40|STRIP
        Famocid 20|ஃபேமோசிட் 20|STRIP
        Famtac|ஃபேம்டாக்|STRIP
        Duphalac|டுஃபலாக்|BOTTLE
        Looz|லூஸ்|BOTTLE
        Eldoper|எல்டோபர்|STRIP
        Imodium|இமோடியம்|STRIP
        Omez 20|ஓமேஸ் 20|STRIP
        Ocid 20|ஓசிட் 20|STRIP
        Emeset 4|எமெசெட் 4|STRIP
        Ondem 4|ஒண்டெம் 4|STRIP
        Electral|எலக்ட்ரால்|PACK
        ORSL|ஓஆர்எஸ்எல்|BOTTLE
        Pan 40|பான் 40|STRIP
        Pantocid 40|பான்டோசிட் 40|STRIP
        Pegclear|பெக்க்ளியர்|PACK
        Peglec|பெக்லெக்|PACK
        Econorm|எகோனார்ம்|PACK
        Vizylac|விசிலாக்|STRIP
        Rablet 20|ரேப்லெட் 20|STRIP
        Razo 20|ரேசோ 20|STRIP
        Mox 500|மாக்ஸ் 500|STRIP
        Novamox 500|நோவாமாக்ஸ் 500|STRIP
        Augmentin 625|ஆக்மென்டின் 625|STRIP
        Clavam 625|கிளாவம் 625|STRIP
        Azee 500|அசீ 500|STRIP
        Azithral 500|அசித்ரால் 500|STRIP
        Taxim-O 200|டாக்ஸிம் ஓ 200|STRIP
        Zifi 200|சிஃபி 200|STRIP
        Cepodem 200|செபோடெம் 200|STRIP
        Gudcef 200|குட்செஃப் 200|STRIP
        Ciplox 500|சிப்லாக்ஸ் 500|STRIP
        Cifran 500|சிஃப்ரான் 500|STRIP
        Candid Cream|கேண்டிட் கிரீம்|TUBE
        Doxicip|டாக்ஸிசிப்|STRIP
        Forcan 150|ஃபோர்கான் 150|STRIP
        Zocon 150|சோகான் 150|STRIP
        Oflox 200|ஆஃப்லாக்ஸ் 200|STRIP
        Zenflox 200|சென்ஃப்லாக்ஸ் 200|STRIP
        Flagyl 400|ஃபிளாஜில் 400|STRIP
        Metrogyl 400|மெட்ரோஜில் 400|STRIP
        T-Bact|டி பேக்ட்|TUBE
        Mupin|முபின்|TUBE
        Amlong 5|ஆம்லாங் 5|STRIP
        Stamlo 5|ஸ்டாம்லோ 5|STRIP
        Atorva 10|அடோர்வா 10|STRIP
        Lipitor 10|லிப்பிட்டர் 10|STRIP
        Clopilet 75|க்ளோபிலெட் 75|STRIP
        Deplatt 75|டிப்ளாட் 75|STRIP
        Losar 50|லோசார் 50|STRIP
        Repace 50|ரிபேஸ் 50|STRIP
        Ecosprin 75|எகோஸ்பிரின் 75|STRIP
        Disprin|டிஸ்பிரின்|STRIP
        Metolar 25|மெட்டோலார் 25|STRIP
        Seloken|செலோகன்|STRIP
        Olmezest 20|ஓல்மெசெஸ்ட் 20|STRIP
        Olmy 20|ஓல்மி 20|STRIP
        Rosuvas 10|ரோசுவாஸ் 10|STRIP
        Crestor 10|கிரெஸ்டர் 10|STRIP
        Telma 40|டெல்மா 40|STRIP
        Telmikind 40|டெல்மிகைண்ட் 40|STRIP
        Shelcal 500|ஷெல்கால் 500|STRIP
        Cipcal 500|சிப்கால் 500|STRIP
        Orofer XT|ஓரோஃபர் எக்ஸ்டி|STRIP
        Livogen|லிவோஜென்|STRIP
        Cetzine 10|செட்சின் 10|STRIP
        Okacet 10|ஓகாசெட் 10|STRIP
        Benadryl Syrup|பெனாட்ரில் சிரப்|BOTTLE
        Ascoril Syrup|அஸ்கோரில் சிரப்|BOTTLE
        Allegra 120|அலெக்ரா 120|STRIP
        Fexova 120|ஃபெக்சோவா 120|STRIP
        Claritin|கிளாரிட்டின்|STRIP
        Lorfast|லார்ஃபாஸ்ட்|STRIP
        Montair LC|மான்டேர் எல்சி|STRIP
        Montek LC|மான்டெக் எல்சி|STRIP
        Amaryl|அமரில்|STRIP
        Glimisave|க்ளிமிசேவ்|STRIP
        Thyronorm 50|தைரோநார்ம் 50|STRIP
        Eltroxin 50|எல்ட்ராக்ஸின் 50|STRIP
        Glycomet 500|கிளைகோமெட் 500|STRIP
        Gluformin 500|குளுஃபார்மின் 500|STRIP
        Refresh Tears|ரிஃப்ரெஷ் டியர்ஸ்|BOTTLE
        Tears Naturale|டியர்ஸ் நேச்சுரல்|BOTTLE
        Zerodol P|சீரோடால் பி|STRIP
        Hifenac P|ஹைஃபெனாக் பி|STRIP
        Voveran 50|வோவரான் 50|STRIP
        Dynapar|டைனாபார்|STRIP
        Brufen 400|புரூஃபென் 400|STRIP
        Ibugesic Plus|இபுஜெசிக் பிளஸ்|STRIP
        Dolo 650|டோலோ 650|STRIP
        Crocin Advance|குரோசின் அட்வான்ஸ்|STRIP
        Calpol 500|கால்பால் 500|STRIP
        D Rise|டி ரைஸ்|PACK
        Uprise D3|அப்ரைஸ் டி3|PACK
        Betnovate|பெட்னோவேட்|TUBE
        Hexidine|ஹெக்சிடின்|BOTTLE
        Cetaphil|செட்டாஃபில்|BOTTLE
        Neutrogena|நியூட்ரோஜினா|BOTTLE
        Cort-S|கார்ட் எஸ்|VIAL
        Dove Soap|டவ் சோப்|PCS
        Head & Shoulders|ஹெட் அண்ட் ஷோல்டர்ஸ்|BOTTLE
        Clinic Plus|கிளினிக் பிளஸ்|BOTTLE
        Permite|பெர்மைட்|TUBE
        Scaboma|ஸ்கபோமா|BOTTLE
        Sebifin|செபிஃபின்|TUBE
        Terbicip|டெர்பிசிப்|STRIP
        Folvite|ஃபோல்வைட்|STRIP
        Folinext|ஃபோலினெக்ஸ்ட்|STRIP
        Zincovit|சிங்கோவிட்|STRIP
        Supradyn|சுப்ராடின்|STRIP
        Nurokind|நியூரோகைண்ட்|STRIP
        Methycobal|மெத்திகோபால்|STRIP
        Ensure|என்ஷ்யூர்|PCS
        Protinex|புரோட்டினெக்ஸ்|PCS
        Horlicks|ஹார்லிக்ஸ்|PCS
        Dettol|டெட்டால்|BOTTLE
        Savlon|சாவ்லான்|BOTTLE
        Lifebuoy|லைஃப்பாய்|PCS
        Colgate|கோல்கேட்|PCS
        Sensodyne|சென்சோடைன்|PCS
        Listerine|லிஸ்டரின்|BOTTLE
        Durex|டியூரெக்ஸ்|PACK
        Manforce|மேன்ஃபோர்ஸ்|PACK
        Moods|மூட்ஸ்|PACK
        Vaseline|வாசலின்|PCS
        Boroline|போரோலின்|TUBE
        Romsons|ராம்சன்ஸ்|PCS
        BD Syringe|பிடி சிரிஞ்ச்|PCS
        Dispovan|டிஸ்போவான்|PCS
        Hansaplast|ஹன்சாப்ளாஸ்ட்|BOX
        Venus|வீனஸ்|PCS
        Ansell Gloves|அன்செல் கிளவுஸ்|BOX
        Dabur Honey|டாபர் ஹனி|BOTTLE
    """.trim().lines().map { it.trim().split('|') }

    private val unitWord = mapOf("STRIP" to ("strip" to "ஸ்ட்ரிப்"), "BOTTLE" to ("bottle" to "பாட்டில்"), "TUBE" to ("tube" to "டியூப்"),
        "PCS" to ("pieces" to "பீஸ்"), "PACK" to ("packet" to "பாக்கெட்"), "BOX" to ("box" to "பாக்ஸ்"), "VIAL" to ("vial" to "வயல்"))

    private fun inventory() = Inventory().also { inv ->
        shop.forEachIndexed { i, (name, _, unit) -> inv.items += Inventory.Item("b$i", name, unit, BigDecimal("50"), emptyMap(), null, null, "Medical", null) }
    }

    private fun all(t: KaiTurn) = (t.reply.text + " | " + t.card?.lines.orEmpty().joinToString(" | ")).replace("\n", " ")

    /** Adds 5 of the product by [line]; the product whose stock moved (null: none / not exactly one), and Kai's reply. */
    private fun added(line: String, qty: Int = 5): Pair<String?, String> {
        val inv = inventory()
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
        val t = runBlocking { kai.ask(line) }
        if (inv.items.any { it.stock.compareTo(BigDecimal("50")) != 0 }) return "(saved before Confirm)" to all(t)
        val c = t.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") } ?: return null to all(t)
        runBlocking { kai.act(c.action, KaiLang.TANGLISH) }
        val moved = inv.items.filter { it.stock.compareTo(BigDecimal("50")) != 0 }
        return moved.singleOrNull()?.takeIf { it.stock.compareTo(BigDecimal(50 + qty)) == 0 }?.name to all(t)
    }

    private fun asked(q: String): String {
        val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inventory(), { now })
        return all(runBlocking { kai.ask(q) })
    }

    @Test fun everyBrandTypedAndSpoken() {
        val fails = mutableListOf<String>()
        for ((name, mic, unit) in shop) {
            val (en, ta) = unitWord.getValue(unit)
            for (line in listOf("$name 5 $en add pannu", "$mic 5 $ta ஆட் பண்ணு")) {
                val (got, reply) = added(line)
                if (got != name) fails += "ADD [$line] → $got — $reply"
            }
            for (q in listOf("$name evlo stock iruku", "$mic எவ்வளவு ஸ்டாக் இருக்கு")) {
                val a = asked(q)
                if (!a.contains(name) || !a.contains("50")) fails += "ASK [$q] → $a"
            }
        }
        assertTrue("${fails.size} fails:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun theBrandWithoutItsStrength() {
        // "Telma 7 strip add pannu" with one Telma in the shop (Telma 40): that one. ("Amlong 5 strip" for Amlong 5 is the name
        // itself — Kai asks how many; that is not this case.)
        val fails = mutableListOf<String>()
        for ((name, mic, unit) in shop.filter { Regex("""\s\d+$""").containsMatchIn(it[0]) }) {
            val (en, ta) = unitWord.getValue(unit)
            val brand = name.substringBeforeLast(' ')
            val micBrand = mic.substringBeforeLast(' ')
            for (line in listOf("$brand 7 $en add pannu", "$micBrand 7 $ta ஆட் பண்ணு")) {
                val (got, reply) = added(line, 7)
                if (got != name) fails += "ADD [$line] → $got — $reply"
            }
        }
        assertTrue("${fails.size} fails:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun twoStrengthsOfOneBrandAreAsked() {
        for (line in listOf("Telma 5 strip add pannu", "டெல்மா 5 ஸ்ட்ரிப் ஆட் பண்ணு")) {
            val inv = inventory().also { it.items += Inventory.Item("t80", "Telma 80", "STRIP", BigDecimal("50"), emptyMap(), null, null, "Medical", null) }
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            val t = runBlocking { kai.ask(line) }
            assertTrue("$line → ${all(t)}", all(t).contains("Telma 40") && all(t).contains("Telma 80") && inv.items.all { it.stock.compareTo(BigDecimal("50")) == 0 })
            // The answer goes on: "80" → Telma 80's draft.
            val d = runBlocking { kai.ask("80") }
            val c = d.card?.buttons?.firstOrNull { it.label.contains("Confirm") || it.label.contains("உறுதி") }
            assertTrue("80 → ${all(d)}", c != null)
            runBlocking { kai.act(c!!.action, KaiLang.TANGLISH) }
            assertTrue(inv.item("Telma 80").stock.compareTo(BigDecimal("55")) == 0 && inv.item("Telma 40").stock.compareTo(BigDecimal("50")) == 0)
        }
        for (line in listOf("Telma 80 5 strip add pannu", "டெல்மா 80 5 ஸ்ட்ரிப் ஆட் பண்ணு")) {
            val inv = inventory().also { it.items += Inventory.Item("t80", "Telma 80", "STRIP", BigDecimal("50"), emptyMap(), null, null, "Medical", null) }
            val kai = KaiAgent(KaiBusinessBrain(Books()), Books(), inv, { now })
            val t = runBlocking { kai.ask(line) }
            runBlocking { kai.act(t.card!!.buttons.first { it.label.contains("Confirm") || it.label.contains("உறுதி") }.action, KaiLang.TANGLISH) }
            assertTrue("$line → ${all(t)}", inv.item("Telma 80").stock.compareTo(BigDecimal("55")) == 0 && inv.item("Telma 40").stock.compareTo(BigDecimal("50")) == 0)
        }
    }
}
