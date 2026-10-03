package com.shopai.app.data.kai

import android.content.Context
import com.google.gson.Gson
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.brain.chat.KaiMemoryAccess
import com.shopai.app.brain.memory.KaiMemoryBook
import com.shopai.app.brain.memory.KaiMemoryStore
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.memory.KnownEntity
import com.shopai.app.brain.memory.MemoryType
import com.shopai.app.data.repository.InventoryRepository
import com.shopai.app.data.repository.PartyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kai's private memory on the phone: one file per business
 * (`kai_memory/<business>.json`). A business's file holds only its own
 * records; loading checks every record's business id again. Nothing here is
 * uploaded, shared or used for any other business.
 */
class KaiMemoryFileStore(context: Context) : KaiMemoryStore {
    private val dir = File(context.applicationContext.filesDir, "kai_memory").apply { mkdirs() }
    private val gson = Gson()
    private val lock = Mutex()

    private fun file(businessId: String) = File(dir, businessId.replace(Regex("""[^A-Za-z0-9_-]"""), "_") + ".json")

    override suspend fun load(businessId: String): KaiMemoryBook = withContext(Dispatchers.IO) {
        lock.withLock {
            val book = runCatching { gson.fromJson(file(businessId).readText(), KaiMemoryBook::class.java) }.getOrNull()
                ?.takeIf { it.businessId == businessId }
                ?: return@withLock KaiMemoryBook(businessId)
            // Gson leaves missing lists null in older files (it doesn't run Kotlin defaults).
            val memories: List<com.shopai.app.brain.memory.KaiMemory>? = book.memories
            val observations: List<com.shopai.app.brain.memory.KaiObservation>? = book.observations
            KaiMemoryBook(
                businessId = businessId,
                memories = memories.orEmpty().filter { it.businessId == businessId }.map { m ->
                    val v: List<String>? = m.variants
                    if (v == null) m.copy(variants = emptyList()) else m
                },
                observations = observations.orEmpty().filter { it.businessId == businessId }.map { o ->
                    val r: List<String>? = o.rejected
                    if (r == null) o.copy(rejected = emptyList()) else o
                },
            )
        }
    }

    override suspend fun save(book: KaiMemoryBook) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val clean = book.copy(
                    memories = book.memories.filter { it.businessId == book.businessId },
                    observations = book.observations.filter { it.businessId == book.businessId },
                )
                val f = file(book.businessId)
                val tmp = File(dir, f.name + ".tmp")
                tmp.writeText(gson.toJson(clean))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }
}

/**
 * The signed-in business's Kai memory for the one Kai (voice and text).
 * Opens the memory of the current login's business on every use, so a
 * different login never sees the previous business's words.
 */
class AppKaiMemoryAccess(
    private val memory: KaiPrivateMemory,
    private val books: BooksModule,
    private val businessId: suspend () -> String?,
    private val parties: PartyRepository,
    private val inventory: InventoryRepository,
) : KaiMemoryAccess {
    private var cached: Pair<Long, List<KnownEntity>>? = null
    private var cachedFor: String? = null

    override suspend fun current(): KaiPrivateMemory? {
        val session = runCatching { books.session() }.getOrNull()
        val biz = session?.ctx?.businessId ?: runCatching { businessId() }.getOrNull() ?: return null
        memory.open(biz, session?.ctx?.userId)
        return memory
    }

    override suspend fun entities(): List<KnownEntity> {
        val biz = memory.businessId
        cached?.takeIf { cachedFor == biz && System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        val list = runCatching { inventory.listProducts() }.getOrDefault(emptyList()).map { KnownEntity(it.id, it.name, MemoryType.PRODUCT_ALIAS) } +
            runCatching { parties.getCustomers() }.getOrDefault(emptyList()).map { KnownEntity(it.id, it.name, MemoryType.CUSTOMER_ALIAS) } +
            runCatching { parties.getSuppliers() }.getOrDefault(emptyList()).map { KnownEntity(it.id, it.name, MemoryType.SUPPLIER_ALIAS) }
        cached = System.currentTimeMillis() to list
        cachedFor = biz
        return list
    }

    /** A new product / customer was added: read the names again. */
    fun refresh() {
        cached = null
    }

    /** Logout. */
    fun close() {
        cached = null
        memory.close()
    }
}
