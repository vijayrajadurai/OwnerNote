package com.shopai.app.util

/**
 * An amount said in words under the number the owner types (owner, 22 Oct 2026: Add Credit / Debit "user friendly") — "₹2,500" →
 * "இரண்டு ஆயிரத்து ஐநூறு ரூபாய்" / "Two thousand five hundred rupees" — so an extra zero is seen before Save. Indian grouping
 * (thousand, lakh, crore); whole rupees, the paise said after.
 */
object AmountWords {

    fun of(amount: Double, tamil: Boolean): String {
        if (amount <= 0.0 || amount.isNaN()) return ""
        val rupees = amount.toLong()
        val paise = Math.round((amount - rupees) * 100).toInt()
        val r = if (tamil) tamil(rupees) else english(rupees)
        if (paise == 0) return r
        return if (tamil) "$r ${twoTa(paise)} பைசா" else "$r and ${twoEn(paise).lowercase()} paise"
    }

    // ------------------------------------------------------------------ Tamil

    private val unitsTa = listOf("", "ஒன்று", "இரண்டு", "மூன்று", "நான்கு", "ஐந்து", "ஆறு", "ஏழு", "எட்டு", "ஒன்பது")
    private val teensTa = listOf("பத்து", "பதினொன்று", "பன்னிரண்டு", "பதின்மூன்று", "பதினான்கு", "பதினைந்து", "பதினாறு", "பதினேழு", "பதினெட்டு", "பத்தொன்பது")
    private val tensTa = listOf("", "", "இருபது", "முப்பது", "நாற்பது", "ஐம்பது", "அறுபது", "எழுபது", "எண்பது", "தொண்ணூறு")
    private val tensJoinTa = listOf("", "", "இருபத்து", "முப்பத்து", "நாற்பத்து", "ஐம்பத்து", "அறுபத்து", "எழுபத்து", "எண்பத்து", "தொண்ணூற்று")
    private val hundredsTa = listOf("", "நூறு", "இருநூறு", "முந்நூறு", "நானூறு", "ஐநூறு", "அறுநூறு", "எழுநூறு", "எண்ணூறு", "தொள்ளாயிரம்")
    private val hundredsJoinTa = listOf("", "நூற்று", "இருநூற்று", "முந்நூற்று", "நானூற்று", "ஐநூற்று", "அறுநூற்று", "எழுநூற்று", "எண்ணூற்று", "தொள்ளாயிரத்து")

    private fun twoTa(n: Int): String = when {
        n < 10 -> unitsTa[n]
        n < 20 -> teensTa[n - 10]
        n % 10 == 0 -> tensTa[n / 10]
        else -> tensJoinTa[n / 10] + " " + unitsTa[n % 10]
    }

    private fun threeTa(n: Int): String {
        val h = n / 100
        val r = n % 100
        return when {
            h == 0 -> twoTa(r)
            r == 0 -> hundredsTa[h]
            else -> hundredsJoinTa[h] + " " + twoTa(r)
        }
    }

    fun tamil(rupees: Long): String {
        if (rupees <= 0) return ""
        val crore = (rupees / 10_000_000).toInt()
        val lakh = ((rupees / 100_000) % 100).toInt()
        val thousand = ((rupees / 1000) % 100).toInt()
        val rest = (rupees % 1000).toInt()
        val parts = mutableListOf<String>()
        // A group joins the next with its "-த்து" form ("ஆயிரத்து ஐநூறு"); the last one stands alone ("இரண்டு ஆயிரம்").
        fun group(n: Int, word: String, join: String, more: Boolean) {
            if (n == 0) return
            val count = if (n == 1) (if (word == "ஆயிரம்") "" else "ஒரு") else (if (n < 100) twoTa(n) else threeTa(n))
            parts += (count + " " + if (more) join else word).trim()
        }
        group(crore, "கோடி", "கோடியே", lakh + thousand + rest > 0)
        group(lakh, "லட்சம்", "லட்சத்து", thousand + rest > 0)
        group(thousand, "ஆயிரம்", "ஆயிரத்து", rest > 0)
        if (rest > 0) parts += threeTa(rest)
        return parts.joinToString(" ") + " ரூபாய்"
    }

    // ------------------------------------------------------------------ English

    private val unitsEn = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tensEn = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun twoEn(n: Int): String = if (n < 20) unitsEn[n] else (tensEn[n / 10] + if (n % 10 > 0) "-" + unitsEn[n % 10] else "")

    private fun threeEn(n: Int): String {
        val h = n / 100
        val r = n % 100
        return listOfNotNull(if (h > 0) unitsEn[h] + " hundred" else null, if (r > 0) twoEn(r) else null).joinToString(" ")
    }

    fun english(rupees: Long): String {
        if (rupees <= 0) return ""
        val crore = rupees / 10_000_000
        val lakh = ((rupees / 100_000) % 100).toInt()
        val thousand = ((rupees / 1000) % 100).toInt()
        val rest = (rupees % 1000).toInt()
        val parts = listOfNotNull(
            if (crore > 0) (if (crore < 1000) threeEn(crore.toInt()) else crore.toString()) + " crore" else null,
            if (lakh > 0) twoEn(lakh) + " lakh" else null,
            if (thousand > 0) twoEn(thousand) + " thousand" else null,
            if (rest > 0) threeEn(rest) else null,
        )
        val s = parts.joinToString(" ") + if (rupees == 1L) " rupee" else " rupees"
        return s.replaceFirstChar { it.uppercase() }
    }
}
