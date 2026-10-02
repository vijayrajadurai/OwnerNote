package com.shopai.app.books.tax

import com.shopai.app.books.model.CodeKind

/** GST state codes (first two digits of a GSTIN). */
object GstStates {
    val byCode: Map<String, String> = linkedMapOf(
        "01" to "Jammu and Kashmir", "02" to "Himachal Pradesh", "03" to "Punjab", "04" to "Chandigarh",
        "05" to "Uttarakhand", "06" to "Haryana", "07" to "Delhi", "08" to "Rajasthan",
        "09" to "Uttar Pradesh", "10" to "Bihar", "11" to "Sikkim", "12" to "Arunachal Pradesh",
        "13" to "Nagaland", "14" to "Manipur", "15" to "Mizoram", "16" to "Tripura",
        "17" to "Meghalaya", "18" to "Assam", "19" to "West Bengal", "20" to "Jharkhand",
        "21" to "Odisha", "22" to "Chhattisgarh", "23" to "Madhya Pradesh", "24" to "Gujarat",
        "26" to "Dadra and Nagar Haveli and Daman and Diu", "27" to "Maharashtra", "29" to "Karnataka",
        "30" to "Goa", "31" to "Lakshadweep", "32" to "Kerala", "33" to "Tamil Nadu",
        "34" to "Puducherry", "35" to "Andaman and Nicobar Islands", "36" to "Telangana",
        "37" to "Andhra Pradesh", "38" to "Ladakh", "97" to "Other Territory",
    )

    fun isValid(code: String?): Boolean = code != null && code in byCode
}

object Gstin {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val SHAPE = Regex("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$")

    fun normalize(raw: String): String = raw.trim().uppercase().replace(" ", "")

    /** Shape, state code and the GSTN check character. */
    fun isValid(raw: String): Boolean {
        val g = normalize(raw)
        if (!SHAPE.matches(g) || !GstStates.isValid(g.substring(0, 2))) return false
        return g[14] == checkChar(g.substring(0, 14))
    }

    fun stateCode(raw: String): String? = normalize(raw).takeIf { it.length >= 2 }?.substring(0, 2)?.takeIf(GstStates::isValid)

    internal fun checkChar(first14: String): Char {
        var sum = 0
        first14.forEachIndexed { i, c ->
            val product = CHARS.indexOf(c) * (if (i % 2 == 0) 1 else 2)
            sum += product / 36 + product % 36
        }
        return CHARS[(36 - sum % 36) % 36]
    }
}

/**
 * HSN/SAC shape rules. A well-formed code is only "verified" when it is in
 * the admin-managed master; otherwise the item is flagged
 * "HSN verification required" and the owner must confirm it. The app never
 * makes a code up.
 */
object HsnRules {
    enum class Verdict { VERIFIED, VERIFICATION_REQUIRED, INVALID_FORMAT }

    fun normalize(raw: String): String = raw.filter { !it.isWhitespace() }

    fun isWellFormed(raw: String, kind: CodeKind): Boolean {
        val c = normalize(raw)
        if (!c.all(Char::isDigit)) return false
        return when (kind) {
            CodeKind.HSN -> c.length == 4 || c.length == 6 || c.length == 8
            CodeKind.SAC -> c.length == 6 && c.startsWith("99")
        }
    }

    fun verdict(raw: String, kind: CodeKind, inMaster: Boolean): Verdict = when {
        !isWellFormed(raw, kind) -> Verdict.INVALID_FORMAT
        inMaster -> Verdict.VERIFIED
        else -> Verdict.VERIFICATION_REQUIRED
    }

    const val VERIFICATION_REQUIRED_MESSAGE = "HSN verification required"
}
