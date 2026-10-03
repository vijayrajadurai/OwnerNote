package com.shopai.app.brain.memory

/**
 * Where a business's Kai memory is kept. Every call names the business;
 * an implementation must never return or write another business's records.
 */
interface KaiMemoryStore {
    suspend fun load(businessId: String): KaiMemoryBook

    suspend fun save(book: KaiMemoryBook)
}

/** Tests, and a fallback when the phone store can't be used. */
class InMemoryKaiMemoryStore : KaiMemoryStore {
    private val books = LinkedHashMap<String, KaiMemoryBook>()

    override suspend fun load(businessId: String): KaiMemoryBook {
        val book = books[businessId] ?: return KaiMemoryBook(businessId)
        // Defensive: only this business's records ever leave the store.
        return book.copy(
            memories = book.memories.filter { it.businessId == businessId },
            observations = book.observations.filter { it.businessId == businessId },
        )
    }

    override suspend fun save(book: KaiMemoryBook) {
        books[book.businessId] = book.copy(
            memories = book.memories.filter { it.businessId == book.businessId },
            observations = book.observations.filter { it.businessId == book.businessId },
        )
    }

    fun businesses(): Set<String> = books.keys
}
