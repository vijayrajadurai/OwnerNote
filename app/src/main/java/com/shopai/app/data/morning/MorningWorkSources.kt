package com.shopai.app.data.morning

import android.content.Context
import com.google.gson.Gson
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.Qty
import com.shopai.app.books.model.TxnType
import com.shopai.app.brain.morning.MorningBatch
import com.shopai.app.brain.morning.MorningDoc
import com.shopai.app.brain.morning.MorningDraft
import com.shopai.app.brain.morning.MorningParty
import com.shopai.app.brain.morning.MorningPartyKind
import com.shopai.app.brain.morning.MorningProduct
import com.shopai.app.brain.morning.MorningReminder
import com.shopai.app.brain.morning.MorningRole
import com.shopai.app.brain.morning.MorningSnapshot
import com.shopai.app.brain.morning.MorningTask
import com.shopai.app.brain.morning.MorningTaskStore
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.ReminderItem
import com.shopai.app.data.repository.BusinessRepository
import com.shopai.app.data.repository.InventoryRepository
import com.shopai.app.data.repository.PartyRepository
import com.shopai.app.data.repository.ReminderRepository
import com.shopai.app.notifications.ReminderAlarms
import com.shopai.app.util.parseIsoToLocalDate
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads Morning Work's inputs from the app's existing sources of truth —
 * read only, nothing is written to the books, the backend or the reminders:
 *  - customers / suppliers and their balances: [PartyRepository] (the books
 *    after the import, the backend ledger before it), plus the books' own
 *    open-bill query for partial payments;
 *  - stock: the books' stock movements (one grouped query) with each
 *    product's minimum / reorder level; the inventory repository before the import;
 *  - reminders: [ReminderRepository] (its saved copy when offline);
 *  - drafts and expiring batches: the books.
 * The last good snapshot is kept on the phone, so Morning Work still opens
 * offline and says it is showing last synced information.
 */
class MorningWorkSources(
    context: Context,
    private val books: BooksModule,
    private val parties: PartyRepository,
    private val reminders: ReminderRepository,
    private val alarms: ReminderAlarms,
    private val inventory: InventoryRepository,
    private val business: BusinessRepository,
) {
    private val dir = File(context.applicationContext.filesDir, "morning_work").apply { mkdirs() }
    private val prefs = context.applicationContext.getSharedPreferences("morning_work", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** The signed-in business (books first; the backend otherwise; the last known id offline). */
    suspend fun businessId(): String? {
        books.session()?.let { return it.ctx.businessId }
        val id = runCatching { business.getMyBusiness()?.id }.getOrNull()
        if (id != null) prefs.edit().putString(KEY_BUSINESS, id).apply()
        return id ?: prefs.getString(KEY_BUSINESS, null)
    }

    /** The signed-in user's role from the books (the backend login is always the owner). */
    suspend fun role(): MorningRole {
        val s = books.session() ?: return MorningRole.OWNER
        val user = runCatching { s.dao.user(s.ctx.userId) }.getOrNull() ?: return MorningRole.OWNER
        return MorningRole(user.role, user.permissions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    suspend fun snapshot(): MorningSnapshot? = withContext(Dispatchers.IO) {
        val biz = businessId() ?: return@withContext null
        val s = books.session()
        val today = LocalDate.now(zone)
        coroutineScope {
            val customers = async { runCatching { parties.getCustomers() } }
            val suppliers = async { runCatching { parties.getSuppliers() } }
            val reminderList = async { runCatching { reminders.listReminders() } }
            val c = customers.await()
            val sp = suppliers.await()
            val r = reminderList.await()
            val cached = loadCached(biz)
            // Party balances couldn't be read (offline before the import): the last synced copy.
            if (c.isFailure && sp.isFailure) {
                return@coroutineScope cached?.let { last ->
                    last.copy(offline = true, reminders = r.getOrNull()?.let { reminderFacts(it) } ?: last.reminders)
                }
            }
            val docsByParty = if (s != null) {
                runCatching {
                    val sale = s.dao.openDocsAll(biz, listOf(TxnType.SALE.name, TxnType.OPENING_BALANCE.name), PartyKind.CUSTOMER.name)
                    val purchase = s.dao.openDocsAll(biz, listOf(TxnType.PURCHASE.name, TxnType.OPENING_BALANCE.name), PartyKind.SUPPLIER.name)
                    (sale + purchase).groupBy { it.partyId }
                }.getOrDefault(emptyMap())
            } else {
                emptyMap()
            }
            fun party(p: PartySummary, kind: MorningPartyKind) = MorningParty(
                id = p.id,
                name = p.name,
                kind = kind,
                phone = p.phone,
                pending = p.pendingTotal,
                nextDueDay = parseIsoToLocalDate(p.nextDueDate)?.toEpochDay(),
                docs = docsByParty[p.id].orEmpty().map { d ->
                    MorningDoc(
                        id = d.id,
                        number = d.number,
                        total = Money.toRupees(d.totalPaise).toDouble(),
                        paid = Money.toRupees(d.allocatedPaise).toDouble(),
                        dueDay = d.dueDate?.toLong(),
                        dateDay = d.date.toLong(),
                    )
                },
            )
            val partyList = c.getOrDefault(emptyList()).map { party(it, MorningPartyKind.CUSTOMER) } +
                sp.getOrDefault(emptyList()).map { party(it, MorningPartyKind.SUPPLIER) }

            val products = if (s != null) {
                runCatching {
                    val stock = s.dao.allStock(biz).associateBy { it.productId }
                    s.dao.products(biz, archived = false, limit = PRODUCT_LIMIT, offset = 0)
                        .filter { it.active && !it.isService }
                        .map { p ->
                            MorningProduct(
                                id = p.id,
                                name = p.name,
                                unit = p.primaryUnit,
                                stock = Qty.toDecimal(stock[p.id]?.qtyMilli ?: 0L).toDouble(),
                                minimum = p.minStockMilli?.let { Qty.toDecimal(it).toDouble() },
                                reorderLevel = p.reorderLevelMilli?.let { Qty.toDecimal(it).toDouble() },
                            )
                        }
                }.getOrDefault(emptyList())
            } else {
                runCatching { inventory.listProducts() }.getOrNull()?.map {
                    MorningProduct(it.id, it.name, it.unit, it.currentStock, it.minimumStock, null)
                } ?: cached?.products.orEmpty()
            }

            val batches = if (s != null) {
                runCatching {
                    val names = products.associate { it.id to (it.name to it.unit) }
                    s.dao.batchesExpiringBy(biz, today.plusDays(EXPIRY_DAYS).toEpochDay().toInt(), BATCH_LIMIT).mapNotNull { b ->
                        val (name, unit) = names[b.productId] ?: return@mapNotNull null
                        MorningBatch(
                            id = b.id,
                            productId = b.productId,
                            productName = name,
                            batchNo = b.batchNo,
                            expiryDay = b.expiryDay!!.toLong(),
                            stock = Qty.toDecimal(s.dao.batchStock(biz, b.productId, b.id).qtyMilli).toDouble(),
                            unit = unit,
                        )
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            val drafts = if (s != null) {
                runCatching { s.dao.openDrafts(biz).take(DRAFT_LIMIT).map { MorningDraft(it.id, it.kind, it.createdAt) } }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            // Reminders offline: the reminder engine's own saved copy.
            val reminderItems = r.getOrNull() ?: alarms.cached()
            val snap = MorningSnapshot(
                businessId = biz,
                parties = partyList,
                products = products,
                batches = batches,
                reminders = reminderFacts(reminderItems),
                drafts = drafts,
                offline = r.isFailure || c.isFailure || sp.isFailure,
                syncedAtMillis = System.currentTimeMillis(),
            )
            if (!snap.offline) saveCached(snap)
            snap
        }
    }

    private fun reminderFacts(items: List<ReminderItem>): List<MorningReminder> = items.map { item ->
        val at = runCatching { Instant.parse(item.dueDate).toEpochMilli() }.getOrNull()
            ?: parseIsoToLocalDate(item.dueDate)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            ?: 0L
        MorningReminder(item.id, item.title, at, item.isDone, custom = item.kind.equals("CUSTOM", ignoreCase = true))
    }

    private fun cacheFile(biz: String) = File(dir, "snapshot_${safe(biz)}.json")

    private fun saveCached(s: MorningSnapshot) {
        runCatching { cacheFile(s.businessId).writeText(gson.toJson(s)) }
    }

    private fun loadCached(biz: String): MorningSnapshot? = runCatching {
        gson.fromJson(cacheFile(biz).readText(), MorningSnapshot::class.java)?.takeIf { it.businessId == biz }
    }.getOrNull()

    /** Logout: the next account on this phone starts clean (task history stays per business). */
    fun clear() {
        prefs.edit().clear().apply()
        dir.listFiles { f -> f.name.startsWith("snapshot_") }?.forEach { it.delete() }
    }

    private companion object {
        const val KEY_BUSINESS = "business_id"
        const val PRODUCT_LIMIT = 5_000
        const val BATCH_LIMIT = 200
        const val DRAFT_LIMIT = 20
        const val EXPIRY_DAYS = 7L
    }
}

private fun safe(id: String) = id.replace(Regex("""[^A-Za-z0-9_-]"""), "_")

/**
 * Morning tasks on the phone: one small JSON file per business, every day
 * kept (history is never deleted). Business ids never mix: each business has
 * its own file and every task is checked against it.
 */
class MorningTaskFileStore(context: Context) : MorningTaskStore {
    private val dir = File(context.applicationContext.filesDir, "morning_work").apply { mkdirs() }
    private val gson = Gson()
    private val lock = Mutex()
    private val type = object : TypeToken<LinkedHashMap<String, List<MorningTask>>>() {}.type

    private fun file(biz: String) = File(dir, "tasks_${safe(biz)}.json")

    private fun read(biz: String): LinkedHashMap<String, List<MorningTask>> = runCatching {
        gson.fromJson<LinkedHashMap<String, List<MorningTask>>>(file(biz).readText(), type)
    }.getOrNull() ?: LinkedHashMap()

    override suspend fun load(businessId: String, businessDate: Long): List<MorningTask> = withContext(Dispatchers.IO) {
        lock.withLock { read(businessId)[businessDate.toString()].orEmpty().filter { it.businessId == businessId } }
    }

    override suspend fun save(businessId: String, businessDate: Long, tasks: List<MorningTask>) {
        withContext(Dispatchers.IO) {
            lock.withLock { write(businessId, businessDate, tasks) }
        }
    }

    private fun write(businessId: String, businessDate: Long, tasks: List<MorningTask>) {
        val all = read(businessId)
        all[businessDate.toString()] = tasks.filter { it.businessId == businessId }
        val f = file(businessId)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(gson.toJson(all, type))
        if (!tmp.renameTo(f)) {
            f.writeText(tmp.readText())
            tmp.delete()
        }
    }

    override suspend fun queuedReminders(businessId: String): List<MorningTask> = withContext(Dispatchers.IO) {
        lock.withLock { read(businessId).values.flatten().filter { it.businessId == businessId && it.pendingReminderAt != null && it.reminderId == null } }
    }

    override suspend fun ownReminderIds(businessId: String): Set<String> = withContext(Dispatchers.IO) {
        lock.withLock { read(businessId).values.flatten().mapNotNull { it.reminderId }.toSet() }
    }
}
