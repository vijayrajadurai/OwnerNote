package com.shopai.app.brain

import com.shopai.app.data.model.ParsedTransaction
import com.shopai.app.data.repository.InsightsRepository
import com.shopai.app.data.repository.PartyRepository
import com.shopai.app.data.repository.ReminderRepository
import com.shopai.app.data.repository.VoiceRepository
import com.shopai.app.data.tts.NaturalTtsSpeaker
import com.shopai.app.util.localDateToIsoInstant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** What Kai does with what the owner said. */
sealed interface KaiTurn {
    val reply: KaiReply

    /** A complete entry to confirm and save through the existing save flow. */
    data class Proposal(
        val record: KaiIntent.Record,
        /** The same entry in the shape the existing voice form uses. */
        val transaction: ParsedTransaction,
        override val reply: KaiReply,
    ) : KaiTurn

    /** Kai needs one more detail; the partial entry is remembered for the next sentence. */
    data class Clarify(val partial: KaiIntent.Record?, override val reply: KaiReply) : KaiTurn

    /** An answer to a business question, from the owner's data. */
    data class Answer(override val reply: KaiReply) : KaiTurn
}

/**
 * KAI — the Business Brain of OwnerNote. Every input (voice, typed
 * sentence, manual form, printed bill, handwritten note) and every output
 * (confirmation, answer, daily brief, reminder) goes through here, so Kai
 * never gives two different answers about the same business.
 *
 *  input → understand (KaiUnderstanding) → check against the ledger
 *  (Business Memory from the existing backend) → act through the existing
 *  repositories → one reply (KaiResponder) → text + voice + Kai's mood.
 *
 * The database is the source of truth: Kai never invents an amount, a
 * person, a date or a payment; anything missing or unclear is asked.
 */
class KaiBrain(
    private val parties: PartyRepository,
    private val reminders: ReminderRepository,
    private val insights: InsightsRepository,
    private val voice: VoiceRepository,
    private val speaker: NaturalTtsSpeaker,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    // ---------------------------------------------------------- memory

    private val memoryLock = Mutex()
    private var snapshot: BusinessSnapshot? = null
    private var loadedAt = 0L

    /** The owner's ledger, refreshed at most once a minute unless [fresh]. */
    suspend fun memory(fresh: Boolean = false): BusinessSnapshot = memoryLock.withLock {
        val cached = snapshot
        if (!fresh && cached != null && System.currentTimeMillis() - loadedAt < 60_000) return cached
        val loaded = coroutineScope {
            val customers = async { runCatching { parties.getCustomers() } }
            val suppliers = async { runCatching { parties.getSuppliers() } }
            val reminderList = async { runCatching { reminders.listReminders() } }
            val cashFlow = async { runCatching { insights.getCashFlow() }.getOrNull() }
            val priorities = async { runCatching { insights.getPriorities() }.getOrNull() }
            val c = customers.await()
            val s = suppliers.await()
            // Without the party lists Kai has no facts to speak from.
            if (c.isFailure && s.isFailure) throw (c.exceptionOrNull() ?: IllegalStateException("ledger unavailable"))
            BusinessSnapshot(
                customers = c.getOrDefault(emptyList()),
                suppliers = s.getOrDefault(emptyList()),
                reminders = reminderList.await().getOrDefault(emptyList()),
                cashFlow = cashFlow.await(),
                priorities = priorities.await().orEmpty(),
            )
        }
        snapshot = loaded
        loadedAt = System.currentTimeMillis()
        loaded
    }

    /** Bumped on every [forget]: caches built on the ledger (party histories) know they are stale. */
    @Volatile var version = 0L
        private set

    /** Something changed in the ledger: the next question reloads it. */
    fun forget() {
        loadedAt = 0L
        version++
    }

    // ---------------------------------------------------- conversation

    /** An entry Kai asked a question about; the owner's next words complete it. */
    private var pending: KaiIntent.Record? = null

    /** Voice or typed words from the owner. */
    suspend fun hear(text: String): KaiTurn {
        val day = today()
        val lang = KaiLanguage.detect(text)
        val ledger = runCatching { memory() }.getOrNull()
        val intent = KaiUnderstanding.understand(text, day, ledger?.people.orEmpty())

        // Completing an entry Kai asked about ("Kumar 3000" … "tharanum").
        pending?.let { waiting ->
            if (intent is KaiIntent.Record || intent is KaiIntent.OpenQuestion || intent is KaiIntent.Unclear) {
                // A bare answer ("Selvam") to "yaaru kitta?" is just the name.
                val more = intent as? KaiIntent.Record
                    ?: KaiIntent.Record(KaiUnderstanding.personIn(text), null, null, null).takeIf { waiting.person == null }
                val merged = waiting.copy(
                    person = waiting.person ?: more?.person,
                    amount = waiting.amount ?: more?.amount,
                    direction = waiting.direction ?: more?.direction,
                    dueDate = waiting.dueDate ?: more?.dueDate,
                    amountAmbiguous = if (waiting.amount == null) more?.amountAmbiguous ?: waiting.amountAmbiguous else false,
                )
                if (merged != waiting) return decide(merged, text, null, lang, ledger)
            }
        }
        pending = null

        return when (intent) {
            is KaiIntent.Record -> {
                val server = runCatching { voice.parseVoiceText(text) }.getOrNull()
                // The server heard a question and the owner gave no amount: answer it.
                if (server?.intent == "ASK_QUERY" && intent.amount == null) return answer(KaiIntent.OpenQuestion(text), lang, ledger)
                decide(KaiUnderstanding.reconcile(intent, server, text, day), text, server, lang, ledger)
            }
            KaiIntent.Unclear -> KaiTurn.Clarify(null, KaiResponder.didNotUnderstand(lang))
            else -> answer(intent, lang, ledger)
        }
    }

    private fun decide(record: KaiIntent.Record, text: String, server: ParsedTransaction?, lang: KaiLang, ledger: BusinessSnapshot?): KaiTurn {
        // Use the ledger's spelling for a known person ("kumar" → "Kumar Stores" only when unambiguous).
        val known = record.person?.let { name -> ledger?.find(name)?.map { it.name }?.distinct()?.singleOrNull() }
        val r = record.copy(person = known ?: record.person)
        if (!r.complete || r.amountAmbiguous) {
            pending = r
            return KaiTurn.Clarify(r, KaiResponder.clarify(r, lang))
        }
        pending = null
        val transaction = ParsedTransaction(
            intent = if (r.direction == Direction.RECEIVABLE) "CREATE_CREDIT" else "CREATE_DEBIT",
            partyName = r.person,
            amount = r.amount,
            currency = "INR",
            dueDate = r.dueDate?.let { localDateToIsoInstant(it) },
            description = server?.description?.takeIf { it.isNotBlank() } ?: text.trim(),
            confidence = 1.0,
            rawText = text,
        )
        return KaiTurn.Proposal(r, transaction, KaiResponder.confirm(r, lang, today()))
    }

    private suspend fun answer(intent: KaiIntent, lang: KaiLang, ledger: BusinessSnapshot?): KaiTurn {
        val day = today()
        if (intent is KaiIntent.OpenQuestion) {
            val text = runCatching { insights.askMyBusiness(intent.text).answer }.getOrNull()
            return KaiTurn.Answer(text?.let { KaiResponder.serverAnswer(it, lang) } ?: KaiResponder.couldNotLoad(lang))
        }
        val s = ledger ?: return KaiTurn.Answer(KaiResponder.couldNotLoad(lang))
        val reply = when (intent) {
            is KaiIntent.PersonBalance -> KaiResponder.personBalance(intent.person, s.find(intent.person), lang, day)
            is KaiIntent.PersonHistory -> history(intent, s, lang, day)
            KaiIntent.WhoOwesMe -> KaiResponder.whoOwesMe(s, lang, day)
            KaiIntent.WhomDoIOwe -> KaiResponder.whomDoIOwe(s, lang, day)
            KaiIntent.DueToday -> KaiResponder.dueToday(s, lang, day)
            KaiIntent.TotalPending -> KaiResponder.totalPending(s, lang)
            KaiIntent.WeekCollection -> KaiResponder.weekCollection(s, lang, day)
            KaiIntent.Briefing -> KaiResponder.briefing(s, lang, day)
            else -> KaiResponder.didNotUnderstand(lang)
        }
        return KaiTurn.Answer(reply)
    }

    private suspend fun history(q: KaiIntent.PersonHistory, s: BusinessSnapshot, lang: KaiLang, day: LocalDate): KaiReply {
        val matches = s.find(q.person)
        val party = matches.singleOrNull() ?: return KaiResponder.personBalance(q.person, matches, lang, day)
        val history = runCatching {
            if (party.side == Direction.RECEIVABLE) PartyHistory.ofCustomer(party, parties.getCustomer(party.id).transactions)
            else PartyHistory.ofSupplier(party, parties.getSupplier(party.id).transactions)
        }.getOrNull() ?: return KaiResponder.couldNotLoad(lang)
        return KaiResponder.history(history, q.lastPaymentOnly, lang, day)
    }

    /** The owner cancelled: forget the half-finished entry. */
    fun reset() {
        pending = null
    }

    // ------------------------------------------------------- after save

    /**
     * An entry was saved (from voice, the manual form, a bill or a note).
     * Kai re-reads the ledger and says what was saved — the saved values,
     * never re-interpreted — and mentions a reminder only if one exists.
     */
    suspend fun saved(name: String, amount: Double, direction: Direction, dueDate: LocalDate?, lang: KaiLang): KaiReply {
        forget()
        // Respond right away: wait for the ledger at most briefly (only to confirm a reminder).
        val ledger = kotlinx.coroutines.withTimeoutOrNull(1_500) { runCatching { memory(fresh = true) }.getOrNull() }
        val reminderSet = dueDate != null && ledger?.hasReminderFor(amount, dueDate) == true
        return KaiResponder.recorded(name, amount, direction, dueDate, reminderSet, lang, today())
    }

    /** Home: today's brief from the real ledger. */
    suspend fun briefing(lang: KaiLang): KaiReply? =
        runCatching { memory(fresh = true) }.getOrNull()?.let { KaiResponder.briefing(it, lang, today()) }

    // -------------------------------------------------- announcements

    /** One reply shown app-wide; [id] makes every announcement distinct, even with the same words. */
    data class Announcement(val id: Long, val reply: KaiReply, val startedAt: Long)

    private var nextAnnouncementId = 1L
    private val _announcement = MutableStateFlow<Announcement?>(null)

    /** A reply Kai gives outside a conversation (after a manual save, a photo…), shown app-wide. */
    val announcement: StateFlow<Announcement?> = _announcement.asStateFlow()

    /** Shows [reply] with Kai right away and speaks it; a newer reply replaces an older one. */
    fun announce(reply: KaiReply, speak: Boolean = true) {
        _announcement.value = Announcement(nextAnnouncementId++, reply, System.currentTimeMillis())
        if (speak) say(reply)
    }

    fun dismissAnnouncement(id: Long) {
        _announcement.value?.takeIf { it.id == id }?.let { _announcement.value = null }
    }

    /** Speaks a reply with Kai's voice (the same words as shown). */
    fun say(reply: KaiReply, onDone: (() -> Unit)? = null) {
        speaker.speakNatural(
            text = reply.speech,
            languageCode = reply.speechLanguage,
            fallbackText = reply.speech,
            fallbackLanguage = reply.speechLanguage,
            onDone = onDone,
        )
    }
}
