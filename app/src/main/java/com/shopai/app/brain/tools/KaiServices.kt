package com.shopai.app.brain.tools

import java.math.BigDecimal
import java.util.Locale

/**
 * The services a shop sells by the piece, for the Daily Sales Note (owner, 14 Oct 2026): a salon's hair cutting, shaving,
 * hair dye, head massage; a beauty parlour's threading, facial, waxing; a tea shop's tea, coffee, vadai; a hotel's idli,
 * dosa, meals; a power tool rental's drill / generator by the day. "oru cutting", "cutting plus shaving", "2 tea", "drill
 * machine 2 naal vaadagai" — typed, or as the ta-IN mic writes it ("கட்டிங் பிளஸ் ஷேவிங்").
 *
 * Kai knows the words, never the price: the owner says it once ("Hair cutting evlo?" → "150"), Confirm keeps it in the
 * owner's own memory, and the next sale is drafted with it. A word Kai can't read is never turned into a service.
 */
object KaiServices {

    enum class Kind { SALON, PARLOUR, TEA, HOTEL, RENTAL, OWN }

    /**
     * One service: its name as the Daily Sales Note shows it, how the owner says it (typed and as the mic writes it),
     * and [clear] — a word only a service shop uses ("shaving", "facial", "idli"), or one a grocery sells too ("tea", "milk",
     * a hardware shop's "drill"), which is a sale only where the shop sells services.
     */
    data class Service(
        val name: String,
        val kind: Kind,
        val words: List<String>,
        val clear: Boolean = true,
        /** A word that is one of several services ("hand wax": full arms or half arms) — Kai asks which, never guesses. */
        val ask: List<String> = emptyList(),
        /** The heading it is listed under on the Menu's service list (the salon master list's sections). */
        val group: String = "",
    )

    val catalogue: List<Service> = listOf(
        // ---- salon
        Service("Hair cutting", Kind.SALON, listOf("hair cutting", "haircut", "hair cut", "cutting", "mudi vettu", "mudi vettal", "முடி வெட்டு", "கட்டிங்", "ஹேர் கட்டிங்", "ஹேர்கட்", "ஹேர் கட்"), group = "Hair Services"),
        Service("Kids cutting", Kind.SALON, listOf("kids cutting", "kids haircut", "baby cutting", "kuzhandhai cutting", "pasanga cutting", "கிட்ஸ் கட்டிங்", "பேபி கட்டிங்", "குழந்தை கட்டிங்"), group = "Hair Services"),
        Service("Shaving", Kind.SALON, listOf("shaving", "shave", "savaram", "savarum", "ஷேவிங்", "சேவிங்", "ஷேவ்", "சவரம்"), group = "Men's Salon & Grooming"),
        Service("Beard trim", Kind.SALON, listOf("beard trim", "beard trimming", "beard", "thaadi trim", "thadi trim", "thaadi", "trimming", "பியர்ட் ட்ரிம்", "பியர்ட்", "தாடி ட்ரிம்", "தாடி", "ட்ரிம்மிங்"), group = "Men's Salon & Grooming"),
        // (never "டை" alone: the mic writes "tie" the same — School tie, Tie rod end)
        Service("Hair dye", Kind.SALON, listOf("hair dye", "hair colour", "hair color", "hair coloring", "dye", "ஹேர் டை", "ஹேர் கலர்"), group = "Hair Services"),
        Service("Head massage", Kind.SALON, listOf("head massage", "thalai massage", "massage", "ஹெட் மசாஜ்", "தலை மசாஜ்", "மசாஜ்"), group = "Hair Services"),
        Service("Hair wash", Kind.SALON, listOf("hair wash", "head wash", "ஹேர் வாஷ்", "ஹெட் வாஷ்"), group = "Hair Services"),
        Service("Hair spa", Kind.SALON, listOf("hair spa", "ஹேர் ஸ்பா"), group = "Hair Services"),
        Service("Hair straightening", Kind.SALON, listOf("hair straightening", "straightening", "ஹேர் ஸ்ட்ரெய்ட்னிங்", "ஸ்ட்ரெய்ட்னிங்"), group = "Hair Services"),
        Service("Facial", Kind.SALON, listOf("facial", "face massage", "ஃபேஷியல்", "பேஷியல்"), group = "Facial & Skin Care"),
        // ---- beauty parlour
        Service("Threading", Kind.PARLOUR, listOf("eyebrow threading", "threading", "eyebrow", "puruvam", "த்ரெட்டிங்", "திரெட்டிங்", "ஐப்ரோ", "புருவம்"), group = "Threading & Face Services"),
        Service("Waxing", Kind.PARLOUR, listOf("waxing", "wax", "வாக்சிங்", "வேக்சிங்", "வாக்ஸ்"), group = "Waxing & Hair Removal"),
        Service("Bleach", Kind.PARLOUR, listOf("bleach", "bleaching", "ப்ளீச்", "பிளீச்"), group = "Facial & Skin Care"),
        Service("Manicure", Kind.PARLOUR, listOf("manicure", "மெனிக்யூர்", "மேனிக்யூர்"), group = "Manicure, Pedicure & Nails"),
        Service("Pedicure", Kind.PARLOUR, listOf("pedicure", "பெடிக்யூர்"), group = "Manicure, Pedicure & Nails"),
        Service("Bridal makeup", Kind.PARLOUR, listOf("bridal makeup", "bridal make up", "bridal", "kalyana makeup", "பிரைடல் மேக்கப்", "பிரைடல்"), group = "Makeup Services"),
        Service("Makeup", Kind.PARLOUR, listOf("makeup", "make up", "மேக்கப்"), group = "Makeup Services"),
        Service("Mehndi", Kind.PARLOUR, listOf("mehndi", "mehandi", "mehendi", "maruthani", "மெஹந்தி", "மெஹெந்தி", "மருதாணி"), group = "Mehendi Services"),
        // ---- the beauty parlour & salon master list (owner's PDF, 19 Oct 2026): every service by its own name — the owner sets
        // each price (never a sample price), the Menu lists them to pick from, and a name said that is one of two ("hand wax":
        // full arms or half arms) is asked, never guessed.
        Service("Arms waxing", Kind.PARLOUR, listOf("hand wax", "hand waxing", "hands wax", "arms wax", "arm wax", "arms waxing", "arm waxing",
            "kai wax", "kai waxing", "கை வாக்ஸ்", "கை வேக்ஸ்", "கை வாக்சிங்", "ஹேண்ட் வாக்ஸ்", "ஹேண்ட் வேக்ஸ்", "ஹேண்ட் வாக்சிங்", "ஹேண்ட் வேக்சிங்",
            "ஆர்ம்ஸ் வாக்ஸ்", "ஆர்ம் வாக்ஸ்"), ask = listOf("Full Arms Waxing", "Half Arms Waxing")),
        Service("Legs waxing", Kind.PARLOUR, listOf("leg wax", "legs wax", "leg waxing", "legs waxing", "kaal wax", "லெக் வாக்ஸ்", "லெக்ஸ் வாக்ஸ்",
            "லெக் வேக்ஸ்", "கால் வாக்ஸ்", "கால் வேக்ஸ்"), ask = listOf("Full Legs Waxing", "Half Legs Waxing")),
        // ---- Hair Services
        Service("Layer Cut", Kind.SALON, listOf("layer cut"), group = "Hair Services"),
        Service("Step Cut", Kind.SALON, listOf("step cut"), group = "Hair Services"),
        Service("Feather Cut", Kind.SALON, listOf("feather cut"), group = "Hair Services"),
        Service("U Cut", Kind.SALON, listOf("u cut"), group = "Hair Services"),
        Service("V Cut", Kind.SALON, listOf("v cut"), group = "Hair Services"),
        Service("Bob Cut", Kind.SALON, listOf("bob cut"), group = "Hair Services"),
        Service("Pixie Cut", Kind.SALON, listOf("pixie cut"), group = "Hair Services"),
        Service("Fringe / Bangs Cut", Kind.SALON, listOf("fringe cut", "bangs cut"), group = "Hair Services"),
        Service("Trim / Split Ends Trim", Kind.SALON, listOf("split ends trim", "split end trim", "hair trim"), group = "Hair Services"),
        Service("Men's Hair Cut", Kind.SALON, listOf("mens hair cut", "mens haircut", "men hair cut", "men haircut", "gents hair cut", "gents haircut", "gents cutting", "male hair cut"), group = "Hair Services"),
        Service("Women's Hair Cut", Kind.SALON, listOf("womens hair cut", "womens haircut", "women hair cut", "women haircut", "ladies hair cut", "ladies haircut", "ladies cutting"), group = "Hair Services"),
        Service("Shampoo Wash", Kind.SALON, listOf("shampoo wash"), group = "Hair Services"),
        Service("Conditioning", Kind.SALON, listOf("conditioning"), group = "Hair Services"),
        Service("Deep Conditioning", Kind.SALON, listOf("deep conditioning"), group = "Hair Services"),
        Service("Anti-Dandruff Treatment", Kind.SALON, listOf("anti dandruff treatment"), group = "Hair Services"),
        Service("Hair Fall Treatment", Kind.SALON, listOf("hair fall treatment"), group = "Hair Services"),
        Service("Scalp Treatment", Kind.SALON, listOf("scalp treatment"), group = "Hair Services"),
        Service("Hair Blow Dry", Kind.SALON, listOf("hair blow dry"), group = "Hair Services"),
        Service("Hair Setting", Kind.SALON, listOf("hair setting"), clear = false, group = "Hair Services"),
        Service("Ironing / Straight Blow Dry", Kind.SALON, listOf("hair ironing", "ironing", "straight blow dry"), group = "Hair Services"),
        Service("Curling", Kind.SALON, listOf("curling"), clear = false, group = "Hair Services"),
        Service("Tong Styling", Kind.SALON, listOf("tong styling"), group = "Hair Services"),
        Service("Smoothening", Kind.SALON, listOf("smoothening"), group = "Hair Services"),
        Service("Rebonding", Kind.SALON, listOf("rebonding"), group = "Hair Services"),
        Service("Keratin Treatment", Kind.SALON, listOf("keratin treatment", "keratin", "கெரட்டின்", "கெராட்டின்"), group = "Hair Services"),
        Service("Botox Hair Treatment", Kind.SALON, listOf("botox hair treatment"), group = "Hair Services"),
        Service("Nanoplastia", Kind.SALON, listOf("nanoplastia"), group = "Hair Services"),
        Service("Hair Perming", Kind.SALON, listOf("hair perming"), group = "Hair Services"),
        Service("Global Hair Color", Kind.SALON, listOf("global hair color"), group = "Hair Services"),
        Service("Root Touch-Up", Kind.SALON, listOf("root touch up", "root touchup"), group = "Hair Services"),
        Service("Grey Hair Coverage", Kind.SALON, listOf("grey hair coverage"), group = "Hair Services"),
        Service("Highlights", Kind.SALON, listOf("highlights"), clear = false, group = "Hair Services"),
        Service("Low Lights", Kind.SALON, listOf("low lights"), group = "Hair Services"),
        Service("Balayage", Kind.SALON, listOf("balayage"), group = "Hair Services"),
        Service("Ombre Hair Color", Kind.SALON, listOf("ombre hair color"), group = "Hair Services"),
        Service("Fashion Hair Color", Kind.SALON, listOf("fashion hair color"), group = "Hair Services"),
        Service("Henna / Marudhani", Kind.SALON, listOf("hair henna", "henna", "marudhani"), group = "Hair Services"),
        Service("Hair Streaks", Kind.SALON, listOf("hair streaks"), group = "Hair Services"),
        Service("Hair Extensions", Kind.SALON, listOf("hair extensions"), clear = false, group = "Hair Services"),
        Service("Hair Patch Styling", Kind.SALON, listOf("hair patch styling"), group = "Hair Services"),
        Service("Bridal Hair Styling", Kind.SALON, listOf("bridal hair styling"), group = "Hair Services"),
        Service("Party Hair Styling", Kind.SALON, listOf("party hair styling"), group = "Hair Services"),
        Service("Saree Bun / Juda", Kind.SALON, listOf("saree bun", "juda", "kondai"), group = "Hair Services"),
        Service("Braiding / Pinnal", Kind.SALON, listOf("braiding", "pinnal", "jadai pinnal"), clear = false, group = "Hair Services"),
        Service("Hair Wash & Blow Dry", Kind.SALON, listOf("hair wash and blow dry"), group = "Hair Services"),
        // ---- Facial & Skin Care
        Service("Basic Facial", Kind.PARLOUR, listOf("basic facial"), group = "Facial & Skin Care"),
        Service("Fruit Facial", Kind.PARLOUR, listOf("fruit facial", "ஃப்ரூட் ஃபேஷியல்", "ப்ரூட் ஃபேஷியல்"), group = "Facial & Skin Care"),
        Service("Gold Facial", Kind.PARLOUR, listOf("gold facial", "கோல்ட் ஃபேஷியல்", "கோல்டு ஃபேஷியல்"), group = "Facial & Skin Care"),
        Service("Diamond Facial", Kind.PARLOUR, listOf("diamond facial", "டைமண்ட் ஃபேஷியல்"), group = "Facial & Skin Care"),
        Service("Pearl Facial", Kind.PARLOUR, listOf("pearl facial", "பேர்ல் ஃபேஷியல்"), group = "Facial & Skin Care"),
        Service("Silver Facial", Kind.PARLOUR, listOf("silver facial"), group = "Facial & Skin Care"),
        Service("Herbal Facial", Kind.PARLOUR, listOf("herbal facial"), group = "Facial & Skin Care"),
        Service("Aroma Facial", Kind.PARLOUR, listOf("aroma facial"), group = "Facial & Skin Care"),
        Service("Anti-Ageing Facial", Kind.PARLOUR, listOf("anti ageing facial"), group = "Facial & Skin Care"),
        Service("Anti-Tan Facial", Kind.PARLOUR, listOf("anti tan facial"), group = "Facial & Skin Care"),
        Service("De-Tan Treatment", Kind.PARLOUR, listOf("de tan treatment"), group = "Facial & Skin Care"),
        Service("Brightening Facial", Kind.PARLOUR, listOf("brightening facial"), group = "Facial & Skin Care"),
        Service("Skin Glow Facial", Kind.PARLOUR, listOf("skin glow facial"), group = "Facial & Skin Care"),
        Service("Hydrating Facial", Kind.PARLOUR, listOf("hydrating facial"), group = "Facial & Skin Care"),
        Service("Oily Skin Facial", Kind.PARLOUR, listOf("oily skin facial"), group = "Facial & Skin Care"),
        Service("Dry Skin Facial", Kind.PARLOUR, listOf("dry skin facial"), group = "Facial & Skin Care"),
        Service("Acne Facial", Kind.PARLOUR, listOf("acne facial"), group = "Facial & Skin Care"),
        Service("Pimple Treatment", Kind.PARLOUR, listOf("pimple treatment"), group = "Facial & Skin Care"),
        Service("Sensitive Skin Facial", Kind.PARLOUR, listOf("sensitive skin facial"), group = "Facial & Skin Care"),
        Service("Charcoal Facial", Kind.PARLOUR, listOf("charcoal facial"), group = "Facial & Skin Care"),
        Service("Vitamin C Facial", Kind.PARLOUR, listOf("vitamin c facial"), group = "Facial & Skin Care"),
        Service("Cleanup", Kind.PARLOUR, listOf("cleanup", "clean up"), clear = false, group = "Facial & Skin Care"),
        Service("Basic Cleanup", Kind.PARLOUR, listOf("basic cleanup", "basic clean up", "face cleanup", "face clean up"), group = "Facial & Skin Care"),
        Service("Deep Cleansing", Kind.PARLOUR, listOf("deep cleansing"), group = "Facial & Skin Care"),
        Service("Blackhead Removal", Kind.PARLOUR, listOf("blackhead removal"), group = "Facial & Skin Care"),
        Service("Whitehead Removal", Kind.PARLOUR, listOf("whitehead removal"), group = "Facial & Skin Care"),
        Service("Pore Cleansing", Kind.PARLOUR, listOf("pore cleansing"), group = "Facial & Skin Care"),
        Service("Face Scrub", Kind.PARLOUR, listOf("face scrub"), clear = false, group = "Facial & Skin Care"),
        Service("Face Pack", Kind.PARLOUR, listOf("face pack"), clear = false, group = "Facial & Skin Care"),
        Service("Face Bleach", Kind.PARLOUR, listOf("face bleach"), group = "Facial & Skin Care"),
        Service("Oxy Facial", Kind.PARLOUR, listOf("oxy facial"), group = "Facial & Skin Care"),
        Service("Hydra Facial", Kind.PARLOUR, listOf("hydra facial"), group = "Facial & Skin Care"),
        Service("Microdermabrasion", Kind.PARLOUR, listOf("microdermabrasion"), group = "Facial & Skin Care"),
        Service("Chemical Peel", Kind.PARLOUR, listOf("chemical peel"), group = "Facial & Skin Care"),
        Service("Face Mask", Kind.PARLOUR, listOf("face mask"), clear = false, group = "Facial & Skin Care"),
        Service("Under-Eye Treatment", Kind.PARLOUR, listOf("under eye treatment"), group = "Facial & Skin Care"),
        Service("Lip Care Treatment", Kind.PARLOUR, listOf("lip care treatment"), group = "Facial & Skin Care"),
        Service("Skin Polishing", Kind.PARLOUR, listOf("skin polishing"), group = "Facial & Skin Care"),
        Service("Body Polishing", Kind.PARLOUR, listOf("body polishing"), group = "Facial & Skin Care"),
        // ---- Threading & Face Services
        Service("Upper Lip Threading", Kind.PARLOUR, listOf("upper lip threading", "upper lip", "அப்பர் லிப்"), group = "Threading & Face Services"),
        Service("Lower Lip Threading", Kind.PARLOUR, listOf("lower lip threading"), group = "Threading & Face Services"),
        Service("Forehead Threading", Kind.PARLOUR, listOf("forehead threading"), group = "Threading & Face Services"),
        Service("Chin Threading", Kind.PARLOUR, listOf("chin threading"), group = "Threading & Face Services"),
        Service("Full Face Threading", Kind.PARLOUR, listOf("full face threading"), group = "Threading & Face Services"),
        Service("Side Locks Threading", Kind.PARLOUR, listOf("side locks threading"), group = "Threading & Face Services"),
        Service("Neck Threading", Kind.PARLOUR, listOf("neck threading"), group = "Threading & Face Services"),
        Service("Eyebrow Shaping", Kind.PARLOUR, listOf("eyebrow shaping"), group = "Threading & Face Services"),
        Service("Eyebrow Tinting", Kind.PARLOUR, listOf("eyebrow tinting"), group = "Threading & Face Services"),
        Service("Eyebrow Waxing", Kind.PARLOUR, listOf("eyebrow waxing", "eyebrow wax"), group = "Threading & Face Services"),
        Service("Upper Lip Waxing", Kind.PARLOUR, listOf("upper lip waxing", "upper lip wax"), group = "Threading & Face Services"),
        Service("Full Face Waxing", Kind.PARLOUR, listOf("full face waxing", "full face wax", "face wax", "face waxing", "ஃபேஸ் வாக்ஸ்"), group = "Threading & Face Services"),
        Service("Neck Bleach", Kind.PARLOUR, listOf("neck bleach"), group = "Threading & Face Services"),
        // ---- Waxing & Hair Removal
        Service("Full Arms Waxing", Kind.PARLOUR, listOf("full arms waxing", "full arms wax", "full hand wax", "full hand waxing", "full hands wax", "full kai wax", "muzhu kai wax", "ஃபுல் ஹேண்ட் வாக்ஸ்", "ஃபுல் ஹேண்ட் வேக்ஸ்", "ஃபுல் கை வாக்ஸ்", "முழு கை வாக்ஸ்", "ஃபுல் ஆர்ம்ஸ் வாக்ஸ்", "ஃபுல் ஆர்ம்ஸ் வாக்சிங்"), group = "Waxing & Hair Removal"),
        Service("Half Arms Waxing", Kind.PARLOUR, listOf("half arms waxing", "half arms wax", "half hand wax", "half hand waxing", "half hands wax", "half kai wax", "arai kai wax", "ஹாஃப் ஹேண்ட் வாக்ஸ்", "ஹாப் ஹேண்ட் வாக்ஸ்", "ஹாஃப் ஹேண்ட் வேக்ஸ்", "half கை வாக்ஸ்", "அரை கை வாக்ஸ்", "ஹாஃப் ஆர்ம்ஸ் வாக்ஸ்", "half ஹேண்ட் வாக்ஸ்"), group = "Waxing & Hair Removal"),
        Service("Underarms Waxing", Kind.PARLOUR, listOf("underarms waxing", "underarms wax", "underarm wax", "underarm waxing", "under arm wax", "armpit wax", "அண்டர் ஆர்ம் வாக்ஸ்", "அண்டர்ஆர்ம் வாக்ஸ்"), group = "Waxing & Hair Removal"),
        Service("Full Legs Waxing", Kind.PARLOUR, listOf("full legs waxing", "full legs wax", "full leg wax", "full leg waxing", "full kaal wax", "ஃபுல் லெக் வாக்ஸ்", "ஃபுல் லெக்ஸ் வாக்ஸ்", "முழு கால் வாக்ஸ்", "ஃபுல் கால் வாக்ஸ்"), group = "Waxing & Hair Removal"),
        Service("Half Legs Waxing", Kind.PARLOUR, listOf("half legs waxing", "half legs wax", "half leg wax", "half leg waxing", "half kaal wax", "arai kaal wax", "ஹாஃப் லெக் வாக்ஸ்", "ஹாப் லெக் வாக்ஸ்", "half லெக் வாக்ஸ்", "half கால் வாக்ஸ்", "அரை கால் வாக்ஸ்"), group = "Waxing & Hair Removal"),
        Service("Full Body Waxing", Kind.PARLOUR, listOf("full body waxing", "full body wax", "ஃபுல் பாடி வாக்ஸ்"), group = "Waxing & Hair Removal"),
        Service("Bikini Line Waxing", Kind.PARLOUR, listOf("bikini line waxing", "bikini line wax"), group = "Waxing & Hair Removal"),
        Service("Brazilian Waxing", Kind.PARLOUR, listOf("brazilian waxing", "brazilian wax"), group = "Waxing & Hair Removal"),
        Service("Back Waxing", Kind.PARLOUR, listOf("back waxing", "back wax"), group = "Waxing & Hair Removal"),
        Service("Front Body Waxing", Kind.PARLOUR, listOf("front body waxing", "front body wax"), group = "Waxing & Hair Removal"),
        Service("Chest Waxing", Kind.PARLOUR, listOf("chest waxing", "chest wax"), group = "Waxing & Hair Removal"),
        Service("Stomach Waxing", Kind.PARLOUR, listOf("stomach waxing", "stomach wax"), group = "Waxing & Hair Removal"),
        Service("Shoulder Waxing", Kind.PARLOUR, listOf("shoulder waxing", "shoulder wax"), group = "Waxing & Hair Removal"),
        Service("Neck Waxing", Kind.PARLOUR, listOf("neck waxing", "neck wax"), group = "Waxing & Hair Removal"),
        Service("Rica Waxing", Kind.PARLOUR, listOf("rica waxing", "rica wax"), group = "Waxing & Hair Removal"),
        Service("Chocolate Waxing", Kind.PARLOUR, listOf("chocolate waxing", "chocolate wax"), group = "Waxing & Hair Removal"),
        Service("Honey Waxing", Kind.PARLOUR, listOf("honey waxing", "honey wax"), group = "Waxing & Hair Removal"),
        Service("Aloe Vera Waxing", Kind.PARLOUR, listOf("aloe vera waxing", "aloe vera wax"), group = "Waxing & Hair Removal"),
        Service("Roll-On Waxing", Kind.PARLOUR, listOf("roll on waxing", "roll on wax"), group = "Waxing & Hair Removal"),
        Service("Hard Waxing", Kind.PARLOUR, listOf("hard waxing", "hard wax"), group = "Waxing & Hair Removal"),
        Service("Soft Waxing", Kind.PARLOUR, listOf("soft waxing", "soft wax"), group = "Waxing & Hair Removal"),
        // ---- Manicure, Pedicure & Nails
        Service("Basic Manicure", Kind.PARLOUR, listOf("basic manicure"), group = "Manicure, Pedicure & Nails"),
        Service("Classic Manicure", Kind.PARLOUR, listOf("classic manicure"), group = "Manicure, Pedicure & Nails"),
        Service("Spa Manicure", Kind.PARLOUR, listOf("spa manicure"), group = "Manicure, Pedicure & Nails"),
        Service("Gel Manicure", Kind.PARLOUR, listOf("gel manicure"), group = "Manicure, Pedicure & Nails"),
        Service("French Manicure", Kind.PARLOUR, listOf("french manicure"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Manicure", Kind.PARLOUR, listOf("nail manicure"), group = "Manicure, Pedicure & Nails"),
        Service("Basic Pedicure", Kind.PARLOUR, listOf("basic pedicure"), group = "Manicure, Pedicure & Nails"),
        Service("Classic Pedicure", Kind.PARLOUR, listOf("classic pedicure"), group = "Manicure, Pedicure & Nails"),
        Service("Spa Pedicure", Kind.PARLOUR, listOf("spa pedicure"), group = "Manicure, Pedicure & Nails"),
        Service("Foot Spa", Kind.PARLOUR, listOf("foot spa"), group = "Manicure, Pedicure & Nails"),
        Service("Heel Care", Kind.PARLOUR, listOf("heel care"), group = "Manicure, Pedicure & Nails"),
        Service("Cracked Heel Treatment", Kind.PARLOUR, listOf("cracked heel treatment"), group = "Manicure, Pedicure & Nails"),
        Service("Foot Scrub", Kind.PARLOUR, listOf("foot scrub"), clear = false, group = "Manicure, Pedicure & Nails"),
        Service("Foot Massage", Kind.PARLOUR, listOf("foot massage"), group = "Manicure, Pedicure & Nails"),
        Service("Callus Removal", Kind.PARLOUR, listOf("callus removal"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Cleaning", Kind.PARLOUR, listOf("nail cleaning"), clear = false, group = "Manicure, Pedicure & Nails"),
        Service("Nail Shaping", Kind.PARLOUR, listOf("nail shaping"), group = "Manicure, Pedicure & Nails"),
        Service("Cuticle Care", Kind.PARLOUR, listOf("cuticle care"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Polish Application", Kind.PARLOUR, listOf("nail polish application", "nail polish"), group = "Manicure, Pedicure & Nails"),
        Service("Gel Polish", Kind.PARLOUR, listOf("gel polish"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Polish Removal", Kind.PARLOUR, listOf("nail polish removal"), clear = false, group = "Manicure, Pedicure & Nails"),
        Service("Nail Art", Kind.PARLOUR, listOf("nail art"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Extensions", Kind.PARLOUR, listOf("nail extensions"), group = "Manicure, Pedicure & Nails"),
        Service("Acrylic Nails", Kind.PARLOUR, listOf("acrylic nails"), group = "Manicure, Pedicure & Nails"),
        Service("Gel Nail Extensions", Kind.PARLOUR, listOf("gel nail extensions"), group = "Manicure, Pedicure & Nails"),
        Service("Press-On Nails", Kind.PARLOUR, listOf("press on nails"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Refill", Kind.PARLOUR, listOf("nail refill"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Repair", Kind.PARLOUR, listOf("nail repair"), group = "Manicure, Pedicure & Nails"),
        Service("Nail Chrome Finish", Kind.PARLOUR, listOf("nail chrome finish"), group = "Manicure, Pedicure & Nails"),
        Service("French Nail Art", Kind.PARLOUR, listOf("french nail art"), group = "Manicure, Pedicure & Nails"),
        // ---- Makeup Services
        Service("Basic Makeup", Kind.PARLOUR, listOf("basic makeup"), group = "Makeup Services"),
        Service("Party Makeup", Kind.PARLOUR, listOf("party makeup"), group = "Makeup Services"),
        Service("Engagement Makeup", Kind.PARLOUR, listOf("engagement makeup"), group = "Makeup Services"),
        Service("Reception Makeup", Kind.PARLOUR, listOf("reception makeup"), group = "Makeup Services"),
        Service("HD Makeup", Kind.PARLOUR, listOf("hd makeup"), group = "Makeup Services"),
        Service("Airbrush Makeup", Kind.PARLOUR, listOf("airbrush makeup"), group = "Makeup Services"),
        Service("Natural Makeup", Kind.PARLOUR, listOf("natural makeup"), group = "Makeup Services"),
        Service("Matte Makeup", Kind.PARLOUR, listOf("matte makeup"), group = "Makeup Services"),
        Service("Dewy Makeup", Kind.PARLOUR, listOf("dewy makeup"), group = "Makeup Services"),
        Service("Eye Makeup", Kind.PARLOUR, listOf("eye makeup"), group = "Makeup Services"),
        Service("Smokey Eye Makeup", Kind.PARLOUR, listOf("smokey eye makeup"), group = "Makeup Services"),
        Service("Saree Draping", Kind.PARLOUR, listOf("saree draping"), group = "Makeup Services"),
        Service("Hair Styling for Events", Kind.PARLOUR, listOf("hair styling for events"), group = "Makeup Services"),
        Service("Dupatta Setting", Kind.PARLOUR, listOf("dupatta setting"), group = "Makeup Services"),
        Service("Jewellery Setting", Kind.PARLOUR, listOf("jewellery setting"), group = "Makeup Services"),
        Service("False Eyelash Application", Kind.PARLOUR, listOf("false eyelash application"), group = "Makeup Services"),
        Service("Makeup Touch-Up", Kind.PARLOUR, listOf("makeup touch up", "makeup touchup"), group = "Makeup Services"),
        Service("Pre-Wedding Makeup", Kind.PARLOUR, listOf("pre wedding makeup"), group = "Makeup Services"),
        Service("Maternity Photoshoot Makeup", Kind.PARLOUR, listOf("maternity photoshoot makeup"), group = "Makeup Services"),
        Service("Photoshoot Makeup", Kind.PARLOUR, listOf("photoshoot makeup"), group = "Makeup Services"),
        Service("Groom Makeup", Kind.PARLOUR, listOf("groom makeup"), group = "Makeup Services"),
        Service("Kids Party Makeup", Kind.PARLOUR, listOf("kids party makeup"), group = "Makeup Services"),
        // ---- Bridal & Pre-Bridal Packages
        Service("Bridal Consultation", Kind.PARLOUR, listOf("bridal consultation"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Facial", Kind.PARLOUR, listOf("bridal facial"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Cleanup", Kind.PARLOUR, listOf("bridal cleanup", "bridal clean up"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal De-Tan", Kind.PARLOUR, listOf("bridal de tan"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Bleach", Kind.PARLOUR, listOf("bridal bleach"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Body Polishing", Kind.PARLOUR, listOf("bridal body polishing"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Waxing", Kind.PARLOUR, listOf("bridal waxing", "bridal wax"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Threading", Kind.PARLOUR, listOf("bridal threading"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Manicure", Kind.PARLOUR, listOf("bridal manicure"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Pedicure", Kind.PARLOUR, listOf("bridal pedicure"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Hair Spa", Kind.PARLOUR, listOf("bridal hair spa"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Hair Color", Kind.PARLOUR, listOf("bridal hair color"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Makeup Trial", Kind.PARLOUR, listOf("bridal makeup trial"), group = "Bridal & Pre-Bridal Packages"),
        Service("Mehendi Application", Kind.PARLOUR, listOf("mehendi application", "mehndi application", "mehandi application"), group = "Bridal & Pre-Bridal Packages"),
        Service("Bridal Jewellery Setting", Kind.PARLOUR, listOf("bridal jewellery setting"), group = "Bridal & Pre-Bridal Packages"),
        Service("Pre-Bridal Package", Kind.PARLOUR, listOf("pre bridal package"), group = "Bridal & Pre-Bridal Packages"),
        Service("Complete Bridal Package", Kind.PARLOUR, listOf("complete bridal package"), group = "Bridal & Pre-Bridal Packages"),
        Service("Grooming Package for Bride", Kind.PARLOUR, listOf("grooming package for bride"), group = "Bridal & Pre-Bridal Packages"),
        Service("Groom Package", Kind.PARLOUR, listOf("groom package"), group = "Bridal & Pre-Bridal Packages"),
        Service("Wedding Guest Makeup", Kind.PARLOUR, listOf("wedding guest makeup"), group = "Bridal & Pre-Bridal Packages"),
        // ---- Mehendi Services
        Service("Simple Mehendi", Kind.PARLOUR, listOf("simple mehendi", "simple mehndi", "simple mehandi"), group = "Mehendi Services"),
        Service("Arabic Mehendi", Kind.PARLOUR, listOf("arabic mehendi", "arabic mehndi", "arabic mehandi"), group = "Mehendi Services"),
        Service("Indian Mehendi", Kind.PARLOUR, listOf("indian mehendi", "indian mehndi", "indian mehandi"), group = "Mehendi Services"),
        Service("Bridal Mehendi", Kind.PARLOUR, listOf("bridal mehendi", "bridal mehndi", "bridal mehandi"), group = "Mehendi Services"),
        Service("Full Hand Mehendi", Kind.PARLOUR, listOf("full hand mehendi", "full hand mehndi", "full hand mehandi"), group = "Mehendi Services"),
        Service("Half Hand Mehendi", Kind.PARLOUR, listOf("half hand mehendi", "half hand mehndi", "half hand mehandi"), group = "Mehendi Services"),
        Service("Full Leg Mehendi", Kind.PARLOUR, listOf("full leg mehendi", "full leg mehndi", "full leg mehandi"), group = "Mehendi Services"),
        Service("Half Leg Mehendi", Kind.PARLOUR, listOf("half leg mehendi", "half leg mehndi", "half leg mehandi"), group = "Mehendi Services"),
        Service("Engagement Mehendi", Kind.PARLOUR, listOf("engagement mehendi", "engagement mehndi", "engagement mehandi"), group = "Mehendi Services"),
        Service("Feet Mehendi", Kind.PARLOUR, listOf("feet mehendi", "feet mehndi", "feet mehandi"), group = "Mehendi Services"),
        Service("Kids Mehendi", Kind.PARLOUR, listOf("kids mehendi", "kids mehndi", "kids mehandi"), group = "Mehendi Services"),
        Service("Mehendi Removal", Kind.PARLOUR, listOf("mehendi removal", "mehndi removal", "mehandi removal"), group = "Mehendi Services"),
        // ---- Men's Salon & Grooming
        Service("Fade Haircut", Kind.SALON, listOf("fade haircut"), group = "Men's Salon & Grooming"),
        Service("Taper Fade", Kind.SALON, listOf("taper fade"), group = "Men's Salon & Grooming"),
        Service("Crew Cut", Kind.SALON, listOf("crew cut"), group = "Men's Salon & Grooming"),
        Service("Buzz Cut", Kind.SALON, listOf("buzz cut"), group = "Men's Salon & Grooming"),
        Service("Beard Styling", Kind.SALON, listOf("beard styling"), group = "Men's Salon & Grooming"),
        Service("Clean Shave", Kind.SALON, listOf("clean shave"), group = "Men's Salon & Grooming"),
        Service("Head Shave", Kind.SALON, listOf("head shave"), group = "Men's Salon & Grooming"),
        Service("Moustache Trim", Kind.SALON, listOf("moustache trim"), group = "Men's Salon & Grooming"),
        Service("Beard Color", Kind.SALON, listOf("beard color"), group = "Men's Salon & Grooming"),
        Service("Grey Coverage", Kind.SALON, listOf("grey coverage"), group = "Men's Salon & Grooming"),
        Service("Men's Facial", Kind.SALON, listOf("mens facial", "men facial"), group = "Men's Salon & Grooming"),
        Service("Men's Cleanup", Kind.SALON, listOf("mens cleanup", "mens clean up", "men cleanup", "men clean up"), group = "Men's Salon & Grooming"),
        Service("De-Tan", Kind.SALON, listOf("de tan"), group = "Men's Salon & Grooming"),
        Service("Men's Bleach", Kind.SALON, listOf("mens bleach", "men bleach"), group = "Men's Salon & Grooming"),
        Service("Dandruff Treatment", Kind.SALON, listOf("dandruff treatment"), group = "Men's Salon & Grooming"),
        Service("Hair Smoothening", Kind.SALON, listOf("hair smoothening"), group = "Men's Salon & Grooming"),
        Service("Hair Keratin", Kind.SALON, listOf("hair keratin"), group = "Men's Salon & Grooming"),
        Service("Men's Manicure", Kind.SALON, listOf("mens manicure", "men manicure"), group = "Men's Salon & Grooming"),
        Service("Men's Pedicure", Kind.SALON, listOf("mens pedicure", "men pedicure"), group = "Men's Salon & Grooming"),
        Service("Men's Waxing", Kind.SALON, listOf("mens waxing", "mens wax", "men waxing", "men wax"), group = "Men's Salon & Grooming"),
        Service("Nose Waxing", Kind.SALON, listOf("nose waxing", "nose wax"), group = "Men's Salon & Grooming"),
        Service("Ear Waxing", Kind.SALON, listOf("ear waxing", "ear wax"), group = "Men's Salon & Grooming"),
        Service("Groom Styling", Kind.SALON, listOf("groom styling"), group = "Men's Salon & Grooming"),
        // ---- Body Spa & Massage
        Service("Neck Massage", Kind.PARLOUR, listOf("neck massage"), group = "Body Spa & Massage"),
        Service("Shoulder Massage", Kind.PARLOUR, listOf("shoulder massage"), group = "Body Spa & Massage"),
        Service("Hand Massage", Kind.PARLOUR, listOf("hand massage"), group = "Body Spa & Massage"),
        Service("Back Massage", Kind.PARLOUR, listOf("back massage"), group = "Body Spa & Massage"),
        Service("Full Body Massage", Kind.PARLOUR, listOf("full body massage"), group = "Body Spa & Massage"),
        Service("Aroma Massage", Kind.PARLOUR, listOf("aroma massage"), group = "Body Spa & Massage"),
        Service("Swedish Massage", Kind.PARLOUR, listOf("swedish massage"), group = "Body Spa & Massage"),
        Service("Deep Tissue Massage", Kind.PARLOUR, listOf("deep tissue massage"), group = "Body Spa & Massage"),
        Service("Body Scrub", Kind.PARLOUR, listOf("body scrub"), clear = false, group = "Body Spa & Massage"),
        Service("Body Wrap", Kind.PARLOUR, listOf("body wrap"), clear = false, group = "Body Spa & Massage"),
        Service("Foot Reflexology", Kind.PARLOUR, listOf("foot reflexology"), group = "Body Spa & Massage"),
        Service("Steam Bath", Kind.PARLOUR, listOf("steam bath"), clear = false, group = "Body Spa & Massage"),
        Service("Body Spa Package", Kind.PARLOUR, listOf("body spa package"), group = "Body Spa & Massage"),
        // ---- Lashes, Brows & Special Treatments
        Service("Eyelash Tinting", Kind.PARLOUR, listOf("eyelash tinting"), group = "Lashes, Brows & Special Treatments"),
        Service("Eyelash Lifting", Kind.PARLOUR, listOf("eyelash lifting"), group = "Lashes, Brows & Special Treatments"),
        Service("Eyelash Extensions", Kind.PARLOUR, listOf("eyelash extensions"), group = "Lashes, Brows & Special Treatments"),
        Service("Eyelash Refill", Kind.PARLOUR, listOf("eyelash refill"), group = "Lashes, Brows & Special Treatments"),
        Service("Lash Removal", Kind.PARLOUR, listOf("lash removal"), group = "Lashes, Brows & Special Treatments"),
        Service("Eyebrow Lamination", Kind.PARLOUR, listOf("eyebrow lamination"), group = "Lashes, Brows & Special Treatments"),
        Service("Brow Shaping", Kind.PARLOUR, listOf("brow shaping"), group = "Lashes, Brows & Special Treatments"),
        Service("Brow Henna", Kind.PARLOUR, listOf("brow henna"), group = "Lashes, Brows & Special Treatments"),
        Service("Lash & Brow Package", Kind.PARLOUR, listOf("lash and brow package"), group = "Lashes, Brows & Special Treatments"),
        // ---- Additional Services
        Service("Ear Piercing", Kind.PARLOUR, listOf("ear piercing"), clear = false, group = "Additional Services"),
        Service("Nose Piercing", Kind.PARLOUR, listOf("nose piercing"), clear = false, group = "Additional Services"),
        Service("Temporary Tattoo", Kind.PARLOUR, listOf("temporary tattoo"), clear = false, group = "Additional Services"),
        Service("Permanent Makeup Consultation", Kind.PARLOUR, listOf("permanent makeup consultation"), group = "Additional Services"),
        Service("Scalp Micropigmentation Consultation", Kind.PARLOUR, listOf("scalp micropigmentation consultation"), group = "Additional Services"),
        Service("Skin Consultation", Kind.PARLOUR, listOf("skin consultation"), group = "Additional Services"),
        Service("Hair Consultation", Kind.PARLOUR, listOf("hair consultation"), group = "Additional Services"),
        Service("Hair Treatment Consultation", Kind.PARLOUR, listOf("hair treatment consultation"), group = "Additional Services"),
        Service("Saree Pre-Pleating", Kind.PARLOUR, listOf("saree pre pleating"), group = "Additional Services"),
        Service("Kids Grooming", Kind.PARLOUR, listOf("kids grooming"), group = "Additional Services"),
        Service("Senior Citizen Haircut", Kind.PARLOUR, listOf("senior citizen haircut"), group = "Additional Services"),
        Service("Home Service Visit", Kind.PARLOUR, listOf("home service visit"), clear = false, group = "Additional Services"),
        Service("Salon Package / Membership", Kind.PARLOUR, listOf("salon package", "membership", "salon membership"), group = "Additional Services"),
        Service("Express Grooming Package", Kind.PARLOUR, listOf("express grooming package"), group = "Additional Services"),

        // ---- tea shop (tea, coffee, milk, bun are a grocery's goods too)
        Service("Lemon tea", Kind.TEA, listOf("lemon tea", "லெமன் டீ"), clear = false),
        Service("Ginger tea", Kind.TEA, listOf("ginger tea", "inji tea", "இஞ்சி டீ"), clear = false),
        Service("Tea", Kind.TEA, listOf("tea", "chai", "டீ"), clear = false),
        Service("Coffee", Kind.TEA, listOf("coffee", "kaapi", "kapi", "காபி", "காப்பி"), clear = false),
        Service("Milk", Kind.TEA, listOf("milk", "paal", "பால்"), clear = false),
        Service("Boost", Kind.TEA, listOf("boost", "பூஸ்ட்"), clear = false),
        Service("Horlicks", Kind.TEA, listOf("horlicks", "ஹார்லிக்ஸ்"), clear = false),
        Service("Bun", Kind.TEA, listOf("bun", "பன்"), clear = false),
        Service("Vadai", Kind.TEA, listOf("vadai", "vada", "vadai", "வடை")),
        Service("Bajji", Kind.TEA, listOf("bajji", "baji", "பஜ்ஜி")),
        Service("Samosa", Kind.TEA, listOf("samosa", "சமோசா")),
        // ---- hotel
        Service("Masala dosa", Kind.HOTEL, listOf("masala dosa", "masala dosai", "மசாலா தோசை")),
        Service("Dosa", Kind.HOTEL, listOf("dosa", "dosai", "thosai", "தோசை")),
        Service("Idli", Kind.HOTEL, listOf("idli", "idly", "இட்லி")),
        Service("Pongal", Kind.HOTEL, listOf("pongal", "பொங்கல்")),
        Service("Poori", Kind.HOTEL, listOf("poori", "puri", "பூரி")),
        Service("Kothu parotta", Kind.HOTEL, listOf("kothu parotta", "kothu porotta", "கொத்து பரோட்டா")),
        Service("Parotta", Kind.HOTEL, listOf("parotta", "porotta", "barotta", "paratha", "parota", "பரோட்டா", "புரோட்டா")),
        Service("Chapathi", Kind.HOTEL, listOf("chapathi", "chapati", "chappathi", "சப்பாத்தி")),
        Service("Meals", Kind.HOTEL, listOf("meals", "saapadu", "sappadu", "lunch meals", "மீல்ஸ்", "சாப்பாடு")),
        Service("Chicken biryani", Kind.HOTEL, listOf("chicken biryani", "சிக்கன் பிரியாணி")),
        Service("Mutton biryani", Kind.HOTEL, listOf("mutton biryani", "மட்டன் பிரியாணி")),
        Service("Biryani", Kind.HOTEL, listOf("biryani", "briyani", "பிரியாணி")),
        Service("Fried rice", Kind.HOTEL, listOf("fried rice", "ஃப்ரைட் ரைஸ்", "ப்ரைட் ரைஸ்")),
        Service("Noodles", Kind.HOTEL, listOf("noodles", "நூடுல்ஸ்"), clear = false),
        Service("Omelette", Kind.HOTEL, listOf("omelette", "omelet", "omlet", "ஆம்லெட்")),
        Service("Curd rice", Kind.HOTEL, listOf("curd rice", "thayir sadam", "தயிர் சாதம்")),
        // ---- power tool rental (a hardware shop sells drills too: a rental only with "vaadagai" / days said, or in a service shop)
        Service("Drill machine rent", Kind.RENTAL, listOf("drill machine", "drilling machine", "drill", "டிரில் மெஷின்", "ட்ரில் மெஷின்", "டிரில்", "ட்ரில்"), clear = false),
        Service("Cutting machine rent", Kind.RENTAL, listOf("cutting machine", "cutter machine", "கட்டிங் மெஷின்", "கட்டர் மெஷின்"), clear = false),
        Service("Grinder machine rent", Kind.RENTAL, listOf("grinder machine", "angle grinder", "grinder", "கிரைண்டர் மெஷின்", "கிரைண்டர்"), clear = false),
        Service("Breaker rent", Kind.RENTAL, listOf("breaker machine", "breaker", "hammer machine", "jack hammer", "பிரேக்கர் மெஷின்", "பிரேக்கர்"), clear = false),
        Service("Generator rent", Kind.RENTAL, listOf("generator", "genset", "ஜெனரேட்டர்"), clear = false),
        Service("Welding machine rent", Kind.RENTAL, listOf("welding machine", "welding", "வெல்டிங் மெஷின்", "வெல்டிங்"), clear = false),
        Service("Concrete mixer rent", Kind.RENTAL, listOf("concrete mixer", "mixer machine", "கான்கிரீட் மிக்சர்", "மிக்சர் மெஷின்"), clear = false),
        Service("Pressure washer rent", Kind.RENTAL, listOf("pressure washer", "பிரஷர் வாஷர்"), clear = false),
        Service("Ladder rent", Kind.RENTAL, listOf("ladder", "eni", "ஏணி"), clear = false),
        Service("Scaffolding rent", Kind.RENTAL, listOf("scaffolding", "saaram", "ஸ்கேஃபோல்டிங்", "சாரம்"), clear = false),
    )

    /** The services a word said may be ("hand wax" → Full / Half Arms Waxing): Kai asks which. Empty for a service of its own. */
    fun askFor(name: String): List<String> = catalogue.firstOrNull { it.name.equals(name, ignoreCase = true) }?.ask.orEmpty()

    /**
     * The services the Menu lists for the owner to pick from (the salon master list, by its sections; a tea shop's / hotel's /
     * rental's own): name only — the owner gives every price. [kinds] null: every kind.
     */
    fun menuList(kinds: Set<Kind>?): List<Pair<String, List<String>>> = catalogue
        .filter { it.ask.isEmpty() && (kinds == null || it.kind in kinds) }
        .groupBy { it.group.ifEmpty { groupOf(it.kind) } }
        .map { (g, list) -> g to list.map { it.name }.distinct() }

    private fun groupOf(kind: Kind) = when (kind) {
        Kind.SALON -> "Hair Services"; Kind.PARLOUR -> "Beauty"; Kind.TEA -> "Tea & snacks"; Kind.HOTEL -> "Food"; Kind.RENTAL -> "Tool rental"; Kind.OWN -> "Other"
    }

    /** Service names for what the owner is typing on the Menu ("gold" → Gold Facial, "wax" → Full Arms Waxing …), best first. */
    fun suggestions(query: String, kinds: Set<Kind>?, limit: Int = 8): List<String> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.length < 2) return emptyList()
        val list = catalogue.filter { it.ask.isEmpty() && (kinds == null || it.kind in kinds) }
        val scored = list.mapNotNull { s ->
            val n = s.name.lowercase(Locale.ROOT)
            when {
                n.startsWith(q) -> 0 to s.name
                n.split(' ').any { it.startsWith(q) } -> 1 to s.name
                s.words.any { it.startsWith(q) } -> 2 to s.name
                n.contains(q) || s.words.any { it.contains(q) } -> 3 to s.name
                else -> null
            }
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.length })).map { it.second }.distinct().take(limit)
    }

    /**
     * [text] with the beauty services the mic wrote in Tamil letters put in by sound, as a whole phrase ("கோல்ட் ஃபேஷியல்" = gold
     * facial, "கெரட்டின் ட்ரீட்மென்ட்" = keratin treatment) — the master list is long, so its names are heard, never listed one by one
     * in Tamil (KaiProductSound). Only a salon / parlour's services; a person in the books is never one.
     */
    fun withServiceSounds(text: String, kinds: Set<Kind>?, people: List<String>): String {
        if (!KaiSpokenWords.hasTamil(text) || kinds == null || (Kind.SALON !in kinds && Kind.PARLOUR !in kinds)) return text
        return runCatching { KaiProductSound.withProducts(text, soundNames, people, goods = false) }.getOrDefault(text)
    }

    /** The salon / parlour words to hear by sound: English ones of two words or more ("gold facial"), or long ones ("keratin"). */
    private val soundNames: List<String> by lazy {
        catalogue.filter { it.kind == Kind.SALON || it.kind == Kind.PARLOUR }.flatMap { s -> s.words.filter { w -> !KaiSpokenWords.hasTamil(w) && (' ' in w || w.length >= 7) } }
            .distinct()
    }

    /**
     * Prepares the salon list's sounds ahead (owner, 20 Oct 2026: "Kai response konjam late"): the first mic line of a beauty shop
     * no longer waits seconds for it. Called off the main thread when Kai Chat opens; nothing else changes.
     */
    fun warmUp(category: String?) {
        val kinds = kindsFor(category)
        if (kinds == null || (Kind.SALON !in kinds && Kind.PARLOUR !in kinds)) return
        runCatching { KaiProductSound.withProducts("ஒரு", soundNames, goods = false) }
    }

    /** "oru", "rendu" … "pathu" — and as the mic writes them. */
    private val numberWords = mapOf(
        "oru" to 1, "onnu" to 1, "one" to 1, "orey" to 1, "rendu" to 2, "two" to 2, "moonu" to 3, "three" to 3, "naalu" to 4, "four" to 4,
        "anju" to 5, "five" to 5, "aaru" to 6, "six" to 6, "ezhu" to 7, "seven" to 7, "ettu" to 8, "eight" to 8, "ombadhu" to 9, "nine" to 9,
        "pathu" to 10, "ten" to 10,
        "ஒரு" to 1, "ஒண்ணு" to 1, "ஒன்னு" to 1, "ஒன்று" to 1, "ரெண்டு" to 2, "இரண்டு" to 2, "மூணு" to 3, "மூன்று" to 3, "நாலு" to 4, "நான்கு" to 4, "அஞ்சு" to 5,
        "ஐந்து" to 5, "ஆறு" to 6, "ஏழு" to 7, "எட்டு" to 8, "ஒன்பது" to 9, "பத்து" to 10,
    )

    /** A rental's time said with it: "2 naal", "3 days", "4 manineram" — the count of days / hours rented. */
    private val duration = Regex("""^\s*(\d+|${numberWords.keys.joinToString("|")})\s*(naal|naalu|naala|days?|day|manineram|mani\s*neram|hours?|hrs?|நாள்|நாளு|மணிநேரம்|மணி\s*நேரம்)(?![\p{L}\p{M}])""")
    private val rentWord = Regex("""(?i)(?<![\p{L}\p{M}])(rent|rental|vaadagai|vadagai|vaadagaiku|vaadagaikku|vadagaiku|bhaadagai|வாடகை\p{M}*\p{L}*)""")
    private val upiWord = Regex("""(?i)(?<![\p{L}\p{M}])(gpay|g\s*pay|google\s*pay|phonepe|phone\s*pe|paytm|upi|online|qr|scan)(?![\p{L}\p{M}])|ஜிபே|ஜி பே|போன்பே|யுபிஐ|பேடிஎம்""")
    private val cashWord = Regex("""(?i)(?<![\p{L}\p{M}])(cash|kaasu|rokkam|ready\s*cash)(?![\p{L}\p{M}])|கேஷ்|ரொக்கம்|காசு""")
    private val stockWord = Regex("""(?i)(?<![\p{L}\p{M}])(vandhuchu|vanthuchu|vandhadhu|vanthathu|vandhiruku|vandhirukku|stock|irakkinen|irakkunen|vaanginen|vanginen|purchase|purchased|received|recieved|arrived|delivered|came|kammi|minus|damage|udanjiduchu|kettupochu|iruppu)(?![\p{L}\p{M}])|வந்துச்சு|வந்தது|ஸ்டாக்|வாங்கினேன்|இருப்பு""")
    /** A thing named after the service, not the service: "cutting tool", "cutting wheel", "shaving cream", "massage oil", "dye bottle". */
    private val thingAfter = Regex("""^\s*(tool|tools|wheel|wheels|blade|blades|disc|disk|board|kit|cream|oil|powder|gel|brush|set|plier|pliers|scissor|scissors|machine|mat|chair|cape|cloth|spray|comb|razor|trimmer|liquid|soap|paper|bottle|bottles|pack|packet|stand|box|tray|bag|cup|cups|glass|plate|flask|maker|stove|vessel|கருவி|மெஷின்|கிரீம்|ஆயில்|கிட்|வீல்|பிளேடு)(?![\p{L}\p{M}])""")
    /** Words that say a sale was made — enough to read a grocery's "tea" as a tea shop's sale. */
    private val saleWord = Regex("""(?i)(?<![\p{L}\p{M}])(sale|sales|sold|vithuchu|vithen|vithom|vithutten|vitthen|serve|served|order|parcel)(?![\p{L}\p{M}])|வித்துச்சு|வித்தேன்|பார்சல்""")

    /** One service said: how many (days / hours for a rental) and, when said with it, the amount for that line. */
    data class Item(val name: String, val qty: Int, val amount: BigDecimal?, val per: String? = null)

    /** A sale line: the services said, and the way it was paid when said (true = UPI / GPay, false = cash, null = not said). */
    data class SaleLine(val items: List<Item>, val upi: Boolean?)

    private data class Hit(val start: Int, val end: Int, val name: String, val service: Service?)

    /** A name of two words and the same words said as one ("hair cutting" / "haircutting", "ஹேர் கட்டிங்" / "ஹேர்கட்டிங்"). */
    private fun spellings(word: String): List<String> = listOf(word, word.replace(" ", "")).distinct()

    private class Alias(val word: String, val service: Service?) {
        val pattern = Regex("""(?<![\p{L}\p{M}])${Regex.escape(word)}(?![\p{L}\p{M}])""")
    }

    /** The catalogue's words, each with its pattern built once. */
    private val catalogueAliases: List<Alias> by lazy {
        catalogue.flatMap { s -> s.words.flatMap { w -> spellings(w.lowercase(Locale.ROOT)).map { Alias(it, s) } } }.distinctBy { it.word }
    }

    private val catalogueWords: Set<String> by lazy { catalogueAliases.map { it.word }.toSet() }

    private fun aliases(own: Collection<String>): List<Alias> {
        // (a catalogue word the owner also keeps as a name stays the catalogue's service, as before)
        val words = catalogueWords
        val owned = own.flatMap { o -> spellings(o.lowercase(Locale.ROOT)) }.distinct().filter { it !in words }
        return (catalogueAliases + owned.map { Alias(it, null) }).sortedByDescending { it.word.length }
    }

    /** Every service word in [text], longest first, never overlapping. [own] = services the owner added, by name. */
    private fun hits(text: String, own: Collection<String>): List<Hit> {
        val lower = text.lowercase(Locale.ROOT)
        val taken = BooleanArray(lower.length)
        val out = mutableListOf<Hit>()
        for (a in aliases(own)) {
            val alias = a.word
            val s = a.service
            if (!lower.contains(alias)) continue
            a.pattern.findAll(lower).forEach { m ->
                if ((m.range.first..m.range.last).none { taken[it] }) {
                    (m.range.first..m.range.last).forEach { taken[it] = true }
                    out += Hit(m.range.first, m.range.last + 1, s?.name ?: own.first { alias in spellings(it.lowercase(Locale.ROOT)) }, s)
                }
            }
        }
        return out.sortedBy { it.start }
    }

    private fun number(token: String): Int? = token.toIntOrNull()?.takeIf { it in 1..999 } ?: numberWords[token.lowercase(Locale.ROOT)]

    /**
     * The services said in [text] as a sale — null when none is (or the line is plainly about stock / something else).
     * [own]: the owner's own services (name → price kept). [products]: the shop's product names — a word that is the shop's
     * own product is that product, not a service, unless the owner keeps a price for it as a service.
     * [serviceShop]: the shop sells services (a word a grocery sells too, "tea", is then a sale).
     */
    fun saleIn(text: String, own: Map<String, BigDecimal>, products: List<String>, serviceShop: Boolean, kinds: Set<Kind>? = null): SaleLine? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[,!?.]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        val found = hits(lower, own.keys)
        if (found.isEmpty()) return null
        val rent = rentWord.containsMatchIn(lower)
        val productWords = products.map { " " + it.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ") + " " }
        val known = own.keys.map { it.lowercase(Locale.ROOT) }.toSet()
        val anyKept = own.isNotEmpty()
        val items = mutableListOf<Item>()
        // (where the line was read up to: an amount said after one service is never the count of the next — "cutting 150 shaving")
        var cursor = 0
        for ((i, h) in found.withIndex()) {
            val said = lower.substring(h.start, h.end)
            // The owner's own Menu item for what was said: "Cutting" on the Menu is the sale of "hair cutting onnu" and of the mic's
            // "கட்டிங் ஒன்னு" — the owner's name and price, never the catalogue's.
            val ownName = ownFor(h, said, own.keys)
            val name = ownName ?: h.name
            val kept = ownName != null || name.lowercase(Locale.ROOT) in known
            // (a service said in Tamil letters is the shop's product too when its English words are in a product's name: "கட்டிங் வீல்"
            // is Cutting wheel, "ஷேவிங் கிரீம்" Shaving cream, "ஹேர் கலர்" the shop's Hair colour)
            val english = if (com.shopai.app.brain.tools.KaiSpokenWords.hasTamil(said)) h.service?.words.orEmpty().filter { w -> w.none { it in '஀'..'௿' } } else emptyList()
            val isProduct = productWords.any { p -> p.contains(" $said ") || english.any { w -> p.contains(" $w ") } } || thingAfter.containsMatchIn(lower.substring(h.end))
            val afterEnd = found.getOrNull(i + 1)?.start ?: lower.length
            val after = lower.substring(h.end, afterEnd)
            val dur = if (h.service?.kind == Kind.RENTAL) duration.find(after) else null
            val ok = when {
                kept -> true
                isProduct -> false
                // (the services of the shop's own kind only: a salon's "cutting", a hotel's "idli" — never a grocery's "tea" sale)
                h.service != null && kinds != null && h.service.kind !in kinds -> false
                h.service == null -> true
                h.service.kind == Kind.RENTAL -> rent || dur != null || serviceShop || anyKept
                h.service.clear -> true
                else -> serviceShop || anyKept || saleWord.containsMatchIn(lower)
            }
            // How many: the number right before it ("oru cutting", "2 tea"), or a rental's days / hours after it.
            val before = lower.substring(minOf(maxOf(cursor, found.getOrNull(i - 1)?.end ?: 0), h.start), h.start).trim().split(' ').lastOrNull().orEmpty()
            cursor = h.end
            // Its amount, when said right after it ("cutting 150", "drill 2 naal 600") — not the count of the next one ("2 tea 3 coffee").
            val restAt = h.end + (dur?.range?.last?.plus(1) ?: 0)
            val rest = lower.substring(restAt, afterEnd)
            val m = Regex("""^\s*(?:ku|kku|ukku|-?ku)?\s*(?:₹|rs\.?\s*)?(\d[\d,]*(?:\.\d+)?)\s*(rs|rupees|ruba|rubai|roobai|ரூபாய்|ரூ)?(?![\p{L}\p{M}\d])""").find(rest)
            val value = m?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
            val countOfNext = m != null && found.getOrNull(i + 1) != null && rest.substring(m.range.last + 1).isBlank() && m.groupValues[2].isEmpty() &&
                value != null && value <= BigDecimal.TEN
            // "head massage 1" ("ஹெட் மசாஜ் ஒண்ணு"), "tea 3": a small number after it, no rupee word, no count before — how many, not ₹1.
            val keptPrice = own.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            val countAfter = !countOfNext && value != null && m!!.groupValues[2].isEmpty() && number(before) == null && value <= BigDecimal.TEN &&
                value.stripTrailingZeros().scale() <= 0 && (if (keptPrice != null) keptPrice.compareTo(value) != 0 else value < BigDecimal(5))
            val amount = if (countOfNext || countAfter) null else value
            if (amount != null) cursor = restAt + m!!.range.last + 1
            // "hair cutting rendu", "cutting onnu": a number word after it, as typed (the mic's "ரெண்டு" is read the same) — how many,
            // unless it is said right before the next service ("cutting rendu shaving": the next one's count).
            val wordAfter = if (m == null && number(before) == null) Regex("""^\s*([\p{L}\p{M}]+)(?![\p{L}\p{M}])""").find(rest)
                ?.takeIf { w -> number(w.groupValues[1]) != null && !(found.getOrNull(i + 1) != null && rest.substring(w.range.last + 1).isBlank()) } else null
            if (wordAfter != null) cursor = restAt + wordAfter.range.last + 1
            if (!ok) continue
            val qty = dur?.let { number(it.groupValues[1]) } ?: number(before) ?: (if (countAfter) value!!.toInt() else null)
                ?: wordAfter?.let { number(it.groupValues[1]) } ?: 1
            val per = dur?.groupValues?.get(2)?.let { if (Regex("""(?i)mani|hour|hr|மணி""").containsMatchIn(it)) "hour" else "day" }
            items += Item(name, qty, amount, per)
        }
        if (items.isEmpty()) return null
        // "cutting machine 2 vandhuchu": stock coming in — not a sale (a rental said with "vaadagai" still is).
        if (stockWord.containsMatchIn(lower) && !rent && !saleWord.containsMatchIn(lower)) return null
        val upi = if (upiWord.containsMatchIn(lower)) true else if (cashWord.containsMatchIn(lower)) false else null
        return SaleLine(items, upi)
    }

    /** The service named in [text] (one only), for "cutting evlo?" / "cutting rate 200" — null when none or several are. */
    fun serviceNamed(text: String, own: Collection<String>, kinds: Set<Kind>? = null): String? {
        val lower = " " + text.lowercase(Locale.ROOT).replace(Regex("""[,!?.]"""), " ") + " "
        return hits(lower, own).map { h -> h to ownFor(h, lower.substring(h.start, h.end), own) }
            .filter { (h, mine) -> mine != null || h.service == null || kinds == null || h.service.kind in kinds }
            .map { (h, mine) -> mine ?: h.name }.distinct().singleOrNull()
    }

    /** The owner's Menu name for a word said ("cutting" → their "Cutting" — or their "Hair cutting"), or null. */
    /**
     * (owner, 16 Oct 2026: "facial onnu" was sold as their "Face massage" — Kai's own list calls both Facial. The owner's Menu
     * names are theirs: an item is the one said, never another of theirs that Kai's list counts as the same.) In order: the
     * name as said ("face massage", "facemassage"); said in Tamil letters, the name by sound ("கோல்ட் ஃபேஷியல்" = Gold facial);
     * the name inside what was said ("hair cutting" for their "Cutting"); their item that has Kai's own name for the service
     * ("Hair cutting" on the Menu for "mudi vettu").
     */
    private fun ownFor(h: Hit, said: String, own: Collection<String>): String? {
        val tamil = KaiSpokenWords.hasTamil(said)
        val padded = " " + said.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ") + " "
        return own.firstOrNull { o -> said in spellings(o.lowercase(Locale.ROOT)) }
            ?: own.firstOrNull { o -> tamil && !KaiSpokenWords.hasTamil(o) && KaiProductSound.sameName(said, o) }
            // (and the other way: an item the owner said into the Menu by mic, "கட்டிங்", typed later as "cutting")
            // ("hair cutting" for a "கட்டிங்" kept: the name inside what was said, by sound)
            ?: own.firstOrNull { o -> !tamil && KaiSpokenWords.hasTamil(o) && said.trim().split(Regex("""\s+""")).let { w ->
                w.indices.any { i -> (i + 1..w.size).any { j -> KaiProductSound.sameName(o, w.subList(i, j).joinToString(" ")) } }
            } }
            ?: own.firstOrNull { o -> !tamil && padded.contains(" " + o.lowercase(Locale.ROOT).trim() + " ") }
            ?: own.firstOrNull { o -> o.equals(h.name, ignoreCase = true) }
    }

    /** Words an owner puts before or after a service's name for their own kinds of it ("Gents Haircut", "Kids Haircut", "Gold Facial"). */
    private val ownKindWords = setOf("gents", "gent", "men", "mens", "male", "ladies", "lady", "women", "womens", "female", "kids", "kid", "child",
        "children", "baby", "boys", "boy", "girls", "girl", "adult", "normal", "regular", "simple", "special", "premium", "deluxe", "gold", "silver",
        "diamond", "pearl", "herbal", "fruit", "mini", "full", "half", "small", "big", "large", "basic", "classic", "royal", "quick", "express", "style",
        "styling", "fancy", "plain")

    /**
     * The owner's Menu items that may be the service [service] Kai's list read ("Hair cutting" said, "Gents Haircut" and "Kids
     * Haircut" kept): an item named by one of the service's own words, alone or with the owner's own kind words ("gents", "kids",
     * "gold"…). Never sold as one of them by itself — Kai asks (owner, 16 Oct 2026: an item is never another of theirs; owner,
     * 19 Oct 2026: "mudi vettu onnu" with "Gents Haircut" on the Menu was asked its price as a new "Hair cutting").
     */
    fun ownChoices(service: String, own: Collection<String>): List<String> {
        val s = catalogue.firstOrNull { it.name.equals(service, ignoreCase = true) } ?: return emptyList()
        val words = s.words.filterNot(KaiSpokenWords::hasTamil).map { it.lowercase(Locale.ROOT).split(' ') }
        return own.filter { o -> !KaiSpokenWords.hasTamil(o) && !o.equals(s.name, ignoreCase = true) }.filter { o ->
            val ow = o.lowercase(Locale.ROOT).trim().split(Regex("""\s+"""))
            words.any { w -> (0..ow.size - w.size).any { i -> ow.subList(i, i + w.size) == w && (ow.take(i) + ow.drop(i + w.size)).all { it in ownKindWords } } }
        }.distinct()
    }

    /**
     * The services a shop of [category] sells (the owner's pick at setup): a beauty salon's salon and parlour services, a
     * restaurant's / tea shop's food, a hardware / electrical shop's tool rental; any other shop's none (only the services the
     * owner adds). Null — no category, or "OTHER": every kind, read as before.
     */
    fun kindsFor(category: String?): Set<Kind>? = when (category?.trim()?.uppercase(Locale.ROOT)) {
        null, "", "OTHER" -> null
        "BEAUTY_SALON" -> setOf(Kind.SALON, Kind.PARLOUR)
        "RESTAURANT_FOOD" -> setOf(Kind.TEA, Kind.HOTEL)
        "HARDWARE", "ELECTRICAL" -> setOf(Kind.RENTAL)
        else -> emptySet()
    }

    /** A shop of [category] that sells services by the piece (its "tea" is a sale). */
    fun serviceCategory(category: String?) = category?.trim()?.uppercase(Locale.ROOT) in setOf("BEAUTY_SALON", "RESTAURANT_FOOD")

    /** A service's name as the owner says it when Kai has no word for it ("hair botox"), cut from "pudhu service hair botox 1500". */
    fun newServiceName(text: String): String? {
        val m = Regex("""(?i)(?:pudhu|puthu|new|புது)\s+(?:service|servis|சர்வீஸ்)\s+([\p{L}\p{M}][\p{L}\p{M}\s]{1,40}?)\s*(?:-|:)?\s*(?:₹|rs\.?\s*)?\d""").find(text)
            ?: Regex("""(?i)([\p{L}\p{M}][\p{L}\p{M}\s]{1,40}?)\s+(?:service|servis|சர்வீஸ்)\s*(?:-|:)?\s*(?:₹|rs\.?\s*)?\d""").find(text)
            ?: return null
        val name = m.groupValues[1].trim().split(Regex("""\s+""")).filter { it.lowercase(Locale.ROOT) !in setOf("oru", "add", "pannu", "pudhu", "puthu", "new", "service") }
            .joinToString(" ").lowercase(Locale.ROOT)
        return name.takeIf { it.length >= 2 }?.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    // ------------------------------------------------------------ the Daily Sales Note line

    private fun plain(a: BigDecimal) = a.stripTrailingZeros().toPlainString()

    /** How a sale is written in the Daily Cash Note's note: "Hair cutting ×1 ₹150 · Shaving ×1 ₹80" — what the screen and PDF show. */
    fun note(items: List<Pair<Item, BigDecimal>>): String = items.joinToString(" · ") { (i, amt) ->
        "${i.name} ×${i.qty}${i.per?.let { if (it == "hour") " hr" else " day" }.orEmpty()} ₹${plain(amt)}"
    }

    /** The services read back from a Daily Cash Note entry's note (empty for an entry written by hand). */
    fun fromNote(note: String?): List<Triple<String, Int, BigDecimal>> =
        Regex("""([^·]+?)\s×(\d+)(?:\s(?:day|hr))?\s₹(\d+(?:\.\d+)?)""").findAll(note.orEmpty()).mapNotNull { m ->
            val amt = m.groupValues[3].toBigDecimalOrNull() ?: return@mapNotNull null
            Triple(m.groupValues[1].trim().removePrefix("Kai:").trim(), m.groupValues[2].toInt(), amt)
        }.toList()
}
