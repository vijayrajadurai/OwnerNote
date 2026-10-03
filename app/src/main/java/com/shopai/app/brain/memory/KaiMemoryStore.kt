package com.shopai.app.brain.memory

/**
 * Where an owner's Kai memory is kept. Every call names the business and the
 * owner; an implementation must never return or write another owner's (or
 * another business's) records.
 */
interface KaiMemoryStore {
    suspend fun load(businessId: String, ownerId: String?): KaiMemoryBook

    suspend fun save(book: KaiMemoryBook)
}

/** Only [this] book's own business and owner's records. */
fun KaiMemoryBook.onlyOwn(): KaiMemoryBook = copy(
    memories = memories.filter { it.businessId == businessId && it.ownerId == ownerId },
    observations = observations.filter { it.businessId == businessId },
)

/** Tests, and a fallback when the phone store can't be used. */
class InMemoryKaiMemoryStore : KaiMemoryStore {
    private val books = LinkedHashMap<Pair<String, String?>, KaiMemoryBook>()

    override suspend fun load(businessId: String, ownerId: String?): KaiMemoryBook =
        // Defensive: only this owner's records ever leave the store.
        books[businessId to ownerId]?.onlyOwn() ?: KaiMemoryBook(businessId, ownerId)

    override suspend fun save(book: KaiMemoryBook) {
        books[book.businessId to book.ownerId] = book.onlyOwn()
    }

    fun businesses(): Set<String> = books.keys.map { it.first }.toSet()
}
