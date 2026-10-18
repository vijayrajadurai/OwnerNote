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
 * With [to] (a week / month asked — "indha maasam sales PDF"): every day from [day] to [to] in one PDF, each entry with its date,
 * the services added up (at most 62 days, as Kai answered).
 */
suspend fun shareCashNote(container: AppContainer, context: Context, day: LocalDate, to: LocalDate? = null) {
    val repo = container.dailyCashRepository
    val last = to?.takeIf { it.isAfter(day) }
    val days = if (last == null) listOf(day) else generateSequence(day) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.take(62).toList()
    val byDay = days.map { d -> d to repo.listDailyCashEntries(localDateKey(d)) }
    val entries = byDay.flatMap { it.second }
    val box = if (last == null) repo.getCashBoxSnapshot(localDateKey(day)) else null
    val services = LinkedHashMap<String, Pair<Int, Double>>()
    for (e in entries.filter { it.type == DailyCashEntryType.IN }) for ((n, q, a) in KaiServices.fromNote(e.note)) {
        services[n] = (services[n]?.first ?: 0) + q to (services[n]?.second ?: 0.0) + a.toDouble()
    }
    val dateLabel = if (last == null) formatLocalDateForDisplay(day) else "${formatLocalDateForDisplay(day)} – ${formatLocalDateForDisplay(last)}"
    val boxLabel = box?.current?.let { formatInr(it) } ?: "—"
    // (a span's entries carry their day: "14 Oct · Gents Haircut ×2 ₹300")
    val dayMark = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
    val shownEntries = if (last == null) entries else byDay.flatMap { (d, es) -> es.map { e -> e.copy(note = listOfNotNull(d.format(dayMark), e.note?.takeIf { it.isNotBlank() }).joinToString(" · ")) } }
    val pdf = withContext(Dispatchers.Default) {
        createDailyCashNotePdf(
            context = context,
            dateLabel = dateLabel,
            openingLabel = box?.opening?.let { formatInr(it) } ?: "—",
            cashBoxLabel = boxLabel,
            totals = computeDailyCashTotals(entries),
            entries = shownEntries,
            services = services.entries.sortedByDescending { it.value.second }.map { (n, v) -> "$n ×${v.first}" to formatInr(v.second) },
        )
    }
    val caption = if (last == null) buildDailyCashShareCaption(dateLabel, boxLabel) else "Daily Sales Note for $dateLabel · Total in ${formatInr(computeDailyCashTotals(entries).totalIn)}"
    shareDailyCashNoteToWhatsApp(context, pdf, caption)
}
