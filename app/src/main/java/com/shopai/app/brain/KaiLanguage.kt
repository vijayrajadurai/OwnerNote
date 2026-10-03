package com.shopai.app.brain

import java.time.LocalDate
import java.util.Locale

/** The language the owner is talking in; Kai answers in the same one. */
enum class KaiLang { TAMIL, TANGLISH, ENGLISH }

object KaiLanguage {
    // Tamil words written in English letters: any of these means Tanglish.
    private val tanglishWords = setOf(
        "kitta", "kita", "kitte", "vanganum", "vaanganum", "vanganu", "vaanganu", "vangunum", "kudukkanum", "kodukkanum",
        "kudukanum", "kudukkanu", "kuduthen", "kuduthuten", "koduthen", "tharanum", "tharanu", "thara", "tharuvan", "tharuvaan",
        "panniten", "pannunga", "pannanum", "irukku", "iruku", "enna", "evlo", "evvalavu", "yaar", "yaaru", "yaarukku", "yarukku",
        "eppo", "epdi", "eppadi", "sollu", "sollunga", "innaikku", "innaiku", "naalaikku", "nalaiku", "ayiram", "aayiram",
        "nooru", "moonu", "rendu", "naalu", "anju", "pathu", "vaaram", "baaki", "bakki", "motham", "mukkiyam", "vasool",
        "vandhuchu", "vanthuchu", "kaasu", "panam", "ennaku", "enakku", "naan", "avan", "aval", "ku", "kku", "ukku",
        "pannu", "panna", "inniku", "innikku", "maniku", "manikku", "mani", "kalichu", "kalichi", "nyabagam", "kaalaila", "podu",
        "eduthuka", "eduka", "mathiyam", "raathiri", "iravu", "saayangalam", "venum", "illa", "andha", "adha", "maathu", "mattum",
    )

    fun detect(text: String): KaiLang {
        if (text.any { it in '஀'..'௿' }) return KaiLang.TAMIL
        val words = text.lowercase(Locale.ROOT).split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
        // "Kumar-ku", "Ravi-kitta": suffixes count too.
        val tanglish = words.any { it in tanglishWords } ||
            Regex("""[a-z]+-?(kitta|ukku|kku)\b""").containsMatchIn(text.lowercase(Locale.ROOT))
        return if (tanglish) KaiLang.TANGLISH else KaiLang.ENGLISH
    }

    /** Chat: English only when the owner clearly writes English; otherwise Kai's usual Tanglish (or Tamil). */
    fun forChat(text: String): KaiLang {
        val detected = detect(text)
        if (detected != KaiLang.ENGLISH) return detected
        val words = text.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).toSet()
        val english = setOf("what", "how", "who", "whom", "when", "which", "is", "are", "does", "do", "did", "the", "much", "owe", "owes",
            "my", "me", "i", "today", "tomorrow", "show", "tell", "total", "pay", "paid", "will", "has", "have", "from", "this", "next", "last",
            "remind", "gave", "received", "call", "stock", "low", "sales", "balance", "add", "scan", "every", "after", "minutes", "hour")
        return if (words.any { it in english }) KaiLang.ENGLISH else KaiLang.TANGLISH
    }

    /** For entries without words (manual form, photos): Tamil app → Tamil, otherwise Tanglish. */
    fun forAppLocale(locale: Locale = Locale.getDefault()): KaiLang =
        if (locale.language == "ta") KaiLang.TAMIL else KaiLang.TANGLISH
}

/** How Kai writes and says amounts and dates. */
object KaiFormat {
    /** "₹3,000" / "₹1,24,500.50" — Indian grouping, paise only when there are any. */
    fun rupees(amount: Double): String {
        val paise = Math.round(kotlin.math.abs(amount) * 100)
        val whole = (paise / 100).toString()
        val grouped = if (whole.length <= 3) whole else {
            whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        }
        val rest = paise % 100
        return (if (amount < 0) "-₹" else "₹") + grouped + if (rest == 0L) "" else "." + rest.toString().padStart(2, '0')
    }

    /** "3000" / "3000.50" — the amount as Kai's voice reads it. */
    fun spokenAmount(amount: Double): String {
        val paise = Math.round(amount * 100)
        return if (paise % 100 == 0L) (paise / 100).toString() else "%d.%02d".format(paise / 100, paise % 100)
    }

    private val englishMonths = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val tamilMonths = listOf(
        "ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்",
        "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்",
    )

    /** "innaikku" / "naalaikku" / "October 10th" (year only when it isn't this year). */
    fun date(date: LocalDate, lang: KaiLang, today: LocalDate): String {
        when (date) {
            today -> return when (lang) { KaiLang.TAMIL -> "இன்னைக்கு"; KaiLang.TANGLISH -> "innaikku"; KaiLang.ENGLISH -> "today" }
            today.plusDays(1) -> return when (lang) { KaiLang.TAMIL -> "நாளைக்கு"; KaiLang.TANGLISH -> "naalaikku"; KaiLang.ENGLISH -> "tomorrow" }
            today.minusDays(1) -> return when (lang) { KaiLang.TAMIL -> "நேத்து"; KaiLang.TANGLISH -> "nethu"; KaiLang.ENGLISH -> "yesterday" }
            else -> Unit
        }
        val year = if (date.year != today.year) " ${date.year}" else ""
        return when (lang) {
            KaiLang.TAMIL -> "${tamilMonths[date.monthValue - 1]} ${date.dayOfMonth}$year"
            else -> "${englishMonths[date.monthValue - 1]} ${date.dayOfMonth}${ordinal(date.dayOfMonth)}$year"
        }
    }

    private fun ordinal(day: Int) = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
}
