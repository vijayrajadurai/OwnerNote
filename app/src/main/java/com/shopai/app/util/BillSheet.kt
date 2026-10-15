package com.shopai.app.util

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A bill the owner has as a file, not a photo (owner, 15 Oct 2026: "photo or PDF or Excel upload"): an Excel sheet (.xlsx)
 * or a CSV export of a purchase invoice. Its cells are read as they are — no OCR, nothing guessed — and each row becomes one
 * line of text ("1 Rice Ponni Aachi 100630 10 Bag (25 kg) 1350 13500"), the same lines the bill reader reads from a photo, so
 * the party, total, items and stock come out of [BillTextParser] / [BillStock] exactly as for a scanned bill.
 */
object BillSheet {

    /** The kind of file picked: by its type, else its name. */
    enum class Kind { IMAGE, PDF, XLSX, XLS, CSV, OTHER }

    fun kindOf(mime: String?, name: String?): Kind {
        val m = mime?.lowercase().orEmpty()
        val n = name?.lowercase().orEmpty()
        return when {
            m.startsWith("image/") || Regex("""\.(jpe?g|png|webp|heic)$""").containsMatchIn(n) -> Kind.IMAGE
            m == "application/pdf" || n.endsWith(".pdf") -> Kind.PDF
            m.contains("spreadsheetml") || n.endsWith(".xlsx") -> Kind.XLSX
            m == "application/vnd.ms-excel" && n.endsWith(".csv") -> Kind.CSV
            m == "application/vnd.ms-excel" || n.endsWith(".xls") -> Kind.XLS
            m.contains("csv") || m == "text/plain" && n.endsWith(".csv") || n.endsWith(".csv") -> Kind.CSV
            else -> Kind.OTHER
        }
    }

    /** The bill's rows as text lines, one per row, cells in order (empty cells left out). */
    fun text(rows: List<List<String>>): String =
        rows.map { r -> r.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") }.filter { it.isNotBlank() }.joinToString("\n")

    // ------------------------------------------------------------ CSV

    /** A CSV file's rows: commas (or semicolons / tabs, whichever the first line uses), quoted cells kept whole. */
    fun csvRows(content: String): List<List<String>> {
        val body = content.removePrefix("﻿")
        val first = body.lineSequence().firstOrNull().orEmpty()
        val sep = listOf(',', ';', '\t').maxByOrNull { c -> first.count { it == c } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && body.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    // ------------------------------------------------------------ Excel (.xlsx)

    /**
     * An Excel workbook's first sheet, as rows of cell text: shared strings, inline strings, numbers (a formula's saved value),
     * each cell put in its own column ("C5") so an empty cell never shifts the row. Null when it is not an .xlsx file.
     */
    fun xlsxRows(bytes: ByteArray): List<List<String>>? {
        val parts = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { e ->
                    if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet") || e.name == "xl/workbook.xml") parts[e.name] = zip.readBytes()
                }
            }
        }.getOrElse { return null }
        val sheetName = parts.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .minByOrNull { Regex("""sheet(\d+)\.xml""").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE } ?: return null
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            val doc = parse(xml) ?: return null
            val si = doc.getElementsByTagName("si")
            (0 until si.length).map { i -> texts(si.item(i) as Element) }
        }.orEmpty()
        val doc = parse(parts.getValue(sheetName)) ?: return null
        val rowNodes = doc.getElementsByTagName("row")
        val rows = mutableListOf<List<String>>()
        for (r in 0 until rowNodes.length) {
            val row = rowNodes.item(r) as Element
            val cells = row.getElementsByTagName("c")
            val byCol = sortedMapOf<Int, String>()
            for (c in 0 until cells.length) {
                val cell = cells.item(c) as Element
                val col = column(cell.getAttribute("r")) ?: byCol.size
                val type = cell.getAttribute("t")
                val v = cell.getElementsByTagName("v").item(0)?.textContent
                val value = when (type) {
                    "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
                    "inlineStr" -> texts(cell)
                    "b" -> if (v == "1") "TRUE" else "FALSE"
                    else -> v?.let(::number)
                }
                if (!value.isNullOrBlank()) byCol[col] = value
            }
            if (byCol.isNotEmpty()) rows += (0..byCol.lastKey()).map { byCol[it].orEmpty() }
        }
        return rows
    }

    /** "C12" → 2 (A = 0). */
    private fun column(ref: String?): Int? {
        val letters = ref?.takeWhile { it.isLetter() }?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }

    /** "1350", "1350.5", "13500.000000001" (a float as Excel saves it) → as a person writes it. */
    private fun number(raw: String): String {
        val d = raw.trim().toBigDecimalOrNull() ?: return raw.trim()
        return d.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun texts(e: Element): String {
        val t = e.getElementsByTagName("t")
        return (0 until t.length).joinToString("") { t.item(it).textContent.orEmpty() }
    }

    // (a file from outside: no DOCTYPE, no external entities — an .xlsx never needs them)
    private fun parse(xml: ByteArray) = runCatching {
        if (String(xml, 0, minOf(xml.size, 2048), Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) return@runCatching null
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }.getOrNull()
}
