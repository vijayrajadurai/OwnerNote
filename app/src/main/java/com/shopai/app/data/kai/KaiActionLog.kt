package com.shopai.app.data.kai

import android.content.Context
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiActionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Kai's action log on the phone: every action with its intent, the tool
 * used, the owner's words (text, never audio), the result, its status, a
 * timestamp, a reference id and the owner. The last [MAX] records are kept.
 */
class KaiActionLog(
    context: Context,
    /** The signed-in owner (each record says whose action it was). */
    private val ownerId: () -> String? = { null },
) {
    private val prefs = context.getSharedPreferences("kai_action_log", Context.MODE_PRIVATE)

    @Synchronized
    fun add(intent: String, tool: String, result: String, status: ActionStatus, reference: String?, input: String? = null): String {
        val ref = reference ?: newReference()
        val owner = runCatching { ownerId() }.getOrNull()
        val record = KaiActionRecord(ref, intent.take(120), tool, result.take(300), status, System.currentTimeMillis(), input?.take(200), owner)
        val list = (records() + record).takeLast(MAX)
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("ref", it.reference).put("intent", it.intent).put("tool", it.tool).put("result", it.result)
                .put("status", it.status.name).put("ts", it.timestamp).put("input", it.input ?: "").put("owner", it.ownerId ?: ""))
        }
        prefs.edit().putString(KEY, a.toString()).apply()
        return ref
    }

    @Synchronized
    fun records(): List<KaiActionRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                KaiActionRecord(o.getString("ref"), o.getString("intent"), o.getString("tool"), o.getString("result"),
                    ActionStatus.valueOf(o.getString("status")), o.getLong("ts"),
                    o.optString("input").ifEmpty { null }, o.optString("owner").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    fun clear() = prefs.edit().clear().apply()

    private fun newReference(): String =
        "K-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd-HHmm")) + "-" + Random.nextInt(0x1000, 0xFFFF).toString(16).uppercase()

    private companion object {
        const val KEY = "records"
        const val MAX = 300
    }
}
