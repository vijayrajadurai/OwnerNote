package com.shopai.app.books.engine

import com.shopai.app.books.data.HsnSacEntity
import com.shopai.app.books.model.CodeKind
import com.shopai.app.books.tax.HsnRules
import com.shopai.app.util.Csv
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Reads an HSN / SAC list exported from the official GST classification:
 * `code, kind, description, gst %, cess %` — kind may be left blank (a 6-digit
 * code starting 99 is a SAC). A header row is skipped. Rates are optional and
 * stored only as a reference; nothing is filled in that the file does not say.
 */
object HsnImport {
    data class Parsed(val rows: List<HsnSacEntity>, val skipped: Int)

    fun parse(csv: String, source: String, now: Long): Parsed {
        var skipped = 0
        val rows = Csv.parse(csv).mapNotNull { cols ->
            val code = HsnRules.normalize(cols.getOrElse(0) { "" })
            if (code.isEmpty() || !code.all(Char::isDigit)) {
                // Header or a blank / non-code line.
                if (code.isNotEmpty() && cols.size > 1 && cols[0].any(Char::isLetter)) return@mapNotNull null
                skipped++
                return@mapNotNull null
            }
            val kindText = cols.getOrElse(1) { "" }.uppercase()
            val kind = when {
                kindText == "HSN" || kindText == "SAC" -> CodeKind.valueOf(kindText)
                code.length == 6 && code.startsWith("99") -> CodeKind.SAC
                else -> CodeKind.HSN
            }
            val description = cols.getOrElse(2) { "" }
            if (!HsnRules.isWellFormed(code, kind) || description.isBlank()) {
                skipped++
                return@mapNotNull null
            }
            HsnSacEntity(
                code = code, kind = kind.name, description = description,
                gstBp = percentBp(cols.getOrElse(3) { "" }), cessBp = percentBp(cols.getOrElse(4) { "" }),
                source = source, updatedAt = now,
            )
        }
        return Parsed(rows, skipped)
    }

    private fun percentBp(text: String): Int? = text.trim().removeSuffix("%").trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { BigDecimal(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact() }.getOrNull()?.takeIf { bp -> bp in 0..10_000 }
    }
}
