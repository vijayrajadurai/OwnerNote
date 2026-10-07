package com.shopai.app.util.bench

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.shopai.app.util.BillTextParser
import org.junit.Test
import java.io.File

/** Scratch benchmark: fields that exist only after the change (GSTIN, status, issues). */
class TextBenchAfter {
    @Test fun run() {
        val data = Gson().fromJson(File("/home/user/ownernote/artifacts/ocr-complete-hardening/dataset/text_invoices.json").readText(), JsonArray::class.java)
        val out = data.map { el ->
            val o = el.asJsonObject
            val b = BillTextParser.parse(o["text"].asString)
            mapOf("id" to o["id"].asString, "sellerGstin" to b.sellerGstin?.value, "status" to b.check.status.name,
                "issues" to b.check.issues.map { it.name }, "moneyNeedsCheck" to b.check.moneyNeedsCheck,
                "itemsDetail" to b.items.map { listOf(it.description, it.hsn, it.quantity?.toPlainString(), it.unitPrice?.toPlainString()) })
        }
        File("/tmp/claude-0/-home-user-Store-accountant-/f97f8eb5-4562-541c-b4cd-83eb1fa79552/scratchpad/bench/out_after_extra.json").writeText(Gson().toJson(out))
    }
}
