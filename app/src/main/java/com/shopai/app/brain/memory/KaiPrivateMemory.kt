package com.shopai.app.brain.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID

/** The words of a message after the business's own language was applied. */
data class AppliedMemory(
    /** The message in words the global Kai core understands. */
    val text: String,
    /** Saved memories that were used (shown to the owner on request; usage counted). */
    val used: List<KaiMemory> = emptyList(),
    /** Action phrases found, with what they mean here. */
    val meanings: List<Pair<String, KaiMeaning>> = emptyList(),
    /** Nicknames found, resolved to the business's real product / customer / supplier. */
    val entities: List<Pair<String, KnownEntity>> = emptyList(),
    /** Meanings that came from a current-conversation instruction ("today pottudu means stock out"). */
    val fromThisConversation: List<String> = emptyList(),
) {
    val changed: Boolean get() = used.isNotEmpty() || fromThisConversation.isNotEmpty()
}

/**
 * One business's private Kai language — open for the signed-in business
 * only. Opening another business drops everything of the previous one;
 * nothing here is ever shared, merged or used for anyone else.
 *
 * Voice and text both go through the same instance (one Kai, one memory).
 */
class KaiPrivateMemory(
    private val store: KaiMemoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private var book: KaiMemoryBook? = null
    /** The owner whose memory is open (null: none, or a business without a signed-in user). */
    var ownerId: String? = null
        private set

    /** "Today pottudu means stock out": this conversation only, never stored. */
    private val conversation = LinkedHashMap<String, KaiMeaning>()

    val businessId: String? get() = book?.businessId

    /** Loads this owner's memory in [businessId] (and only it). Another owner or business forgets the previous one. */
    suspend fun open(businessId: String, ownerId: String?) = lock.withLock {
        require(businessId.isNotBlank()) { "Kai memory needs a business" }
        if (book?.businessId != businessId || book?.ownerId != ownerId) {
            book = store.load(businessId, ownerId).copy(businessId = businessId, ownerId = ownerId).onlyOwn()
            conversation.clear()
        }
        this.ownerId = ownerId
    }

    /** Logout: nothing of this business stays loaded. */
    fun close() {
        book = null
        ownerId = null
        conversation.clear()
    }

    fun clearConversation() = conversation.clear()

    /** Everything the owner can see and manage (deleted ones are gone). */
    fun list(): List<KaiMemory> = book?.memories.orEmpty().filter { it.status != MemoryStatus.DELETED }

    /** What Kai uses automatically. */
    fun usable(): List<KaiMemory> = book?.memories.orEmpty().filter { it.usable }

    fun find(phrase: String): KaiMemory? {
        val n = normalize(phrase)
        return usable().firstOrNull { n in it.phrases }
    }

    fun observation(phrase: String): KaiObservation? = book?.observations?.firstOrNull { it.normalizedPhrase == normalize(phrase) }

    // ------------------------------------------------------------ learning

    /**
     * The owner confirmed / told Kai what a phrase means here. One phrase has
     * one meaning per business: a new meaning replaces the old one.
     */
    suspend fun teachMeaning(phrase: String, meaning: KaiMeaning, source: MemorySource): KaiMemory =
        save(phrase, MemoryType.ACTION_ALIAS, meaning.name, meaning.en, null, source)

    /** A nickname for one of the business's own products / customers / suppliers (stored by id). */
    suspend fun teachEntity(phrase: String, entity: KnownEntity, source: MemorySource): KaiMemory =
        save(phrase, entity.type, entityKind(entity.type), entity.name, entity.id, source)

    /** "ramba" = "romba": the owner's own word for an everyday word (never an action — those are [teachMeaning]). */
    suspend fun teachWord(phrase: String, means: String, source: MemorySource): KaiMemory =
        save(phrase, MemoryType.WORD, MemoryType.WORD.name, means.trim(), null, source)

    /** An approved extra spelling for an existing meaning ("thooki kudunga" for "thooki kudu"). */
    suspend fun addVariant(memoryId: String, variant: String): KaiMemory? = lock.withLock {
        val b = book ?: return@withLock null
        val v = normalize(variant)
        var updated: KaiMemory? = null
        val list = b.memories.map { m ->
            if (m.id == memoryId && v !in m.phrases) m.copy(variants = m.variants + v, updatedAt = clock()).also { updated = it } else m
        }
        persist(b.copy(memories = list, observations = b.observations.filter { it.normalizedPhrase != v }))
        updated
    }

    /** "Idha marandhudu" / "forget thooki kudu": removed for this business. */
    suspend fun forget(phrase: String): KaiMemory? = lock.withLock {
        val b = book ?: return@withLock null
        val n = normalize(phrase)
        val target = b.memories.firstOrNull { it.status != MemoryStatus.DELETED && n in it.phrases } ?: return@withLock null
        val removed = target.copy(status = MemoryStatus.DELETED, learningState = LearningState.DISABLED, updatedAt = clock())
        persist(b.copy(memories = b.memories.map { if (it.id == target.id) removed else it }))
        conversation.remove(n)
        removed
    }

    /** Memory screen: switch a memory off / on, or delete it. */
    suspend fun setStatus(memoryId: String, status: MemoryStatus): KaiMemory? = lock.withLock {
        val b = book ?: return@withLock null
        var updated: KaiMemory? = null
        val list = b.memories.map { m ->
            if (m.id != memoryId) m else m.copy(
                status = status,
                learningState = if (status == MemoryStatus.ACTIVE) LearningState.OWNER_CONFIRMED else LearningState.DISABLED,
                updatedAt = clock(),
            ).also { updated = it }
        }
        persist(b.copy(memories = list))
        updated
    }

    /** Memory screen: change the phrase or the action meaning (still the owner's own entry). */
    suspend fun edit(memoryId: String, phrase: String?, meaning: KaiMeaning?): KaiMemory? = lock.withLock {
        val b = book ?: return@withLock null
        val current = b.memories.firstOrNull { it.id == memoryId } ?: return@withLock null
        val n = phrase?.let(::normalize)?.takeIf { it.isNotEmpty() }
        val edited = current.copy(
            triggerPhrase = phrase?.trim()?.takeIf { it.isNotEmpty() } ?: current.triggerPhrase,
            normalizedPhrase = n ?: current.normalizedPhrase,
            meaningType = meaning?.name ?: current.meaningType,
            meaningValue = meaning?.en ?: current.meaningValue,
            source = MemorySource.OWNER_CREATED,
            updatedAt = clock(),
        )
        // The new phrase replaces any other memory with the same words.
        val others = b.memories.map { m ->
            if (m.id != memoryId && m.status != MemoryStatus.DELETED && edited.normalizedPhrase in m.phrases) {
                m.copy(status = MemoryStatus.DELETED, learningState = LearningState.DISABLED, updatedAt = clock())
            } else m
        }
        persist(b.copy(memories = others.map { if (it.id == memoryId) edited else it }))
        edited
    }

    /** Kai noticed a phrase it does not know. Counted only — never used to act. */
    suspend fun observe(phrase: String, guess: String?): KaiObservation? = lock.withLock {
        val b = book ?: return@withLock null
        val n = normalize(phrase)
        if (n.isEmpty()) return@withLock null
        val now = clock()
        val existing = b.observations.firstOrNull { it.normalizedPhrase == n }
        val next = existing?.copy(count = existing.count + 1, lastSeenAt = now, guess = guess ?: existing.guess,
            confidence = if (existing.count + 1 >= 2) MemoryConfidence.MEDIUM else existing.confidence)
            ?: KaiObservation(b.businessId, n, 1, now, now, LearningState.OBSERVED, guess, MemoryConfidence.LOW)
        persist(b.copy(observations = b.observations.filter { it.normalizedPhrase != n } + next))
        next
    }

    /** Kai asked the owner about a phrase. */
    suspend fun suggested(phrase: String, guess: String?) = updateObservation(phrase) { it.copy(state = LearningState.SUGGESTED, guess = guess ?: it.guess) }

    /** The owner said "no": the phrase stays unknown and this guess is not asked again. */
    suspend fun rejected(phrase: String, guess: String?) = updateObservation(phrase) {
        it.copy(state = LearningState.OBSERVED, rejected = (it.rejected + listOfNotNull(guess)).distinct())
    }

    /** Current instruction for this conversation only (wins over saved memory, never stored). */
    fun overrideForConversation(phrase: String, meaning: KaiMeaning) {
        conversation[normalize(phrase)] = meaning
    }

    fun conversationMeaning(phrase: String): KaiMeaning? = conversation[normalize(phrase)]

    /** Counts uses of saved memories (for the memory screen). */
    suspend fun markUsed(used: List<KaiMemory>) {
        if (used.isEmpty()) return
        lock.withLock {
            val b = book ?: return@withLock
            val ids = used.map { it.id }.toSet()
            val now = clock()
            persist(b.copy(memories = b.memories.map { if (it.id in ids) it.copy(usageCount = it.usageCount + 1, lastUsedAt = now) else it }))
        }
    }

    // ------------------------------------------------------------ applying

    /**
     * Rewrites the owner's words into words the global core understands.
     * Priority: this conversation's instruction → the owner's confirmed
     * memory → (nothing here) global rules. Nicknames resolve only to records
     * that exist in [entities] now; anything else is left untouched.
     */
    fun apply(text: String, entities: List<KnownEntity>): AppliedMemory {
        if (book == null || text.isBlank()) return AppliedMemory(text)
        var out = text
        val used = mutableListOf<KaiMemory>()
        val meanings = mutableListOf<Pair<String, KaiMeaning>>()
        val found = mutableListOf<Pair<String, KnownEntity>>()
        val fromConversation = mutableListOf<String>()

        // 1. This conversation's instruction ("today pottudu means stock out").
        for ((phrase, meaning) in conversation.entries.sortedByDescending { it.key.length }) {
            val r = replace(out, phrase, meaning.canonical) ?: continue
            out = r
            meanings += phrase to meaning
            fromConversation += phrase
        }
        // 2. The owner's confirmed memory, longest phrase first.
        val byId = entities.associateBy { it.id }
        val candidates = usable().flatMap { m -> m.phrases.map { p -> p to m } }
            .filter { (p, _) -> p !in fromConversation }
            .sortedByDescending { it.first.length }
        for ((phrase, m) in candidates) {
            val replacement = when (m.memoryType) {
                MemoryType.PRODUCT_ALIAS, MemoryType.CUSTOMER_ALIAS, MemoryType.SUPPLIER_ALIAS -> {
                    val e = m.referenceEntityId?.let(byId::get) ?: continue // the record is gone: not used
                    found += phrase to e
                    e.name
                }
                MemoryType.ABBREVIATION, MemoryType.WORD -> m.meaningValue
                else -> m.meaning?.canonical ?: continue
            }
            val r = replace(out, phrase, replacement) ?: continue
            out = r
            used += m
            m.meaning?.let { meanings += phrase to it }
        }
        return AppliedMemory(out, used.distinctBy { it.id }, meanings, found, fromConversation)
    }

    /** A saved memory spelled almost like [phrase] ("thooki kudunga" ~ "thooki kudu"): only a reason to ASK. */
    fun similar(phrase: String): KaiMemory? {
        val n = normalize(phrase)
        if (n.isEmpty()) return null
        return usable().firstOrNull { m ->
            m.phrases.none { it == n } && m.phrases.any { p ->
                val a = p.split(' ')
                val b = n.split(' ')
                a.size == b.size && a.dropLast(1) == b.dropLast(1) && a.last().take(3) == b.last().take(3) && a.last() != b.last()
            }
        }
    }

    // ------------------------------------------------------------ internals

    private suspend fun save(phrase: String, type: MemoryType, meaningType: String, meaningValue: String, entityId: String?, source: MemorySource): KaiMemory =
        lock.withLock {
            val b = book ?: error("Kai memory is not open for a business")
            val n = normalize(phrase)
            require(n.isNotEmpty()) { "empty phrase" }
            val now = clock()
            val existing = b.memories.firstOrNull { it.status != MemoryStatus.DELETED && n in it.phrases }
            val memory = KaiMemory(
                id = existing?.id ?: newId(),
                businessId = b.businessId,
                ownerId = ownerId,
                memoryType = type,
                triggerPhrase = phrase.trim().trim('`', '\'', '"', '“', '”', '‘', '’'),
                normalizedPhrase = n,
                meaningType = meaningType,
                meaningValue = meaningValue,
                referenceEntityId = entityId,
                confidence = MemoryConfidence.HIGH,
                status = MemoryStatus.ACTIVE,
                source = source,
                learningState = LearningState.OWNER_CONFIRMED,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                lastUsedAt = existing?.lastUsedAt,
                usageCount = existing?.usageCount ?: 0,
                variants = if (existing?.meaningType == meaningType) existing.variants else emptyList(),
            )
            val list = if (existing != null) b.memories.map { if (it.id == existing.id) memory else it } else b.memories + memory
            persist(b.copy(memories = list, observations = b.observations.filter { it.normalizedPhrase != n }))
            conversation.remove(n)
            memory
        }

    private suspend fun updateObservation(phrase: String, change: (KaiObservation) -> KaiObservation) = lock.withLock {
        val b = book ?: return@withLock
        val n = normalize(phrase)
        val now = clock()
        val existing = b.observations.firstOrNull { it.normalizedPhrase == n } ?: KaiObservation(b.businessId, n, 1, now, now)
        persist(b.copy(observations = b.observations.filter { it.normalizedPhrase != n } + change(existing).copy(lastSeenAt = now)))
    }

    private suspend fun persist(b: KaiMemoryBook) {
        book = b
        store.save(b)
    }

    private fun entityKind(t: MemoryType) = when (t) {
        MemoryType.PRODUCT_ALIAS -> "PRODUCT"
        MemoryType.CUSTOMER_ALIAS -> "CUSTOMER"
        MemoryType.SUPPLIER_ALIAS -> "SUPPLIER"
        else -> t.name
    }

    companion object {
        /** Lower case, no quotes / punctuation, hyphens as spaces, single spaces. */
        fun normalize(s: String): String = s.lowercase(Locale.ROOT)
            .replace(Regex("""[`'"“”‘’!?.,;:()\[\]{}]"""), " ")
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()

        /** Replaces the whole-word [phrase] in [text] (any case, hyphen or space between words); null when absent. */
        fun replace(text: String, phrase: String, with: String): String? {
            val tokens = phrase.split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val pattern = Regex(
                """(?<![\p{L}\p{M}\p{N}])""" + tokens.joinToString("""[\s\-]+""") { Regex.escape(it) } + """(?![\p{L}\p{M}])""",
                RegexOption.IGNORE_CASE,
            )
            if (!pattern.containsMatchIn(text)) return null
            return pattern.replace(text) { with }
        }

        fun contains(text: String, phrase: String): Boolean = replace(text, phrase, "") != null
    }
}
