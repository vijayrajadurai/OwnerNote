package com.shopai.app.ui.kaichat

import android.content.Context
import com.shopai.app.brain.tools.KaiServices
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.DailyCashEntryType
import com.shopai.app.util.buildDailyCashShareCaption
import com.shopai.app.util.computeDailyCashTotals
import com.shopai.app.util.createDailyCashNotePdf
import com.shopai.app.util.formatInr
import com.shopai.app.util.formatLocalDateForDisplay
import com.shopai.app.util.localDateKey
import com.shopai.app.util.shareDailyCashNoteToWhatsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Kai's Daily Sales Note PDF button: that day's Daily Cash Note read from the phone (the same entries Kai answered from), drawn
 * as the screen's own PDF with the day's sales by service, then the share sheet / WhatsApp opened with it — the owner sends.
 */
suspend fun shareCashNote(container: AppContainer, context: Context, day: LocalDate) {
    val key = localDateKey(day)
    val repo = container.dailyCashRepository
    val entries = repo.listDailyCashEntries(key)
    val box = repo.getCashBoxSnapshot(key)
    val services = LinkedHashMap<String, Pair<Int, Double>>()
    for (e in entries.filter { it.type == DailyCashEntryType.IN }) for ((n, q, a) in KaiServices.fromNote(e.note)) {
        services[n] = (services[n]?.first ?: 0) + q to (services[n]?.second ?: 0.0) + a.toDouble()
    }
    val dateLabel = formatLocalDateForDisplay(day)
    val boxLabel = box?.current?.let { formatInr(it) } ?: "—"
    val pdf = withContext(Dispatchers.Default) {
        createDailyCashNotePdf(
            context = context,
            dateLabel = dateLabel,
            openingLabel = box?.opening?.let { formatInr(it) } ?: "—",
            cashBoxLabel = boxLabel,
            totals = computeDailyCashTotals(entries),
            entries = entries,
            services = services.map { (n, v) -> "$n ×${v.first}" to formatInr(v.second) },
        )
    }
    shareDailyCashNoteToWhatsApp(context, pdf, buildDailyCashShareCaption(dateLabel, boxLabel))
}
