package com.shopai.app.util.bench

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.shopai.app.util.BillTextParser
import org.junit.Test
import java.io.File

/** Scratch benchmark (not part of the repo): per-invoice fields that exist before AND after the change. */
class TextBenchCommon {
    @Test fun run() {
        val data = Gson().fromJson(File("/home/user/ownernote/artifacts/ocr-complete-hardening/dataset/text_invoices.json").readText(), JsonArray::class.java)
        val out = data.map { el ->
            val o = el.asJsonObject
            val b = BillTextParser.parse(o["text"].asString)
            mapOf("id" to o["id"].asString, "total" to b.total?.toPlainString(), "totalFromLabel" to b.totalFromLabel,
                "tax" to b.tax?.toPlainString(), "buyer" to b.customerName,
                "items" to b.items.map { listOf(it.description, it.amount.toPlainString()) })
        }
        File(System.getProperty("bench.out") ?: "/tmp/claude-0/-home-user-Store-accountant-/f97f8eb5-4562-541c-b4cd-83eb1fa79552/scratchpad/bench/out_common.json").writeText(Gson().toJson(out))
    }
}
