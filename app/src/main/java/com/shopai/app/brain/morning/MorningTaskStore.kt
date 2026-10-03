package com.shopai.app.brain.morning

/**
 * Where morning tasks are kept, per business and per business date. Tasks
 * are never deleted: a finished day stays as history. Every call is scoped
 * by business id, so one business can never read another's tasks.
 */
interface MorningTaskStore {
    suspend fun load(businessId: String, businessDate: Long): List<MorningTask>

    suspend fun save(businessId: String, businessDate: Long, tasks: List<MorningTask>)

    /** Every task of the business that is waiting to create its reminder (postponed offline). */
    suspend fun queuedReminders(businessId: String): List<MorningTask>

    /** Reminder ids Morning Work created for this business (any day). */
    suspend fun ownReminderIds(businessId: String): Set<String>
}

/** In-memory store: tests, and a fallback while the phone store can't be used. */
class InMemoryMorningTaskStore : MorningTaskStore {
    private val days = LinkedHashMap<String, LinkedHashMap<Long, List<MorningTask>>>()

    override suspend fun load(businessId: String, businessDate: Long): List<MorningTask> =
        days[businessId]?.get(businessDate).orEmpty()

    override suspend fun save(businessId: String, businessDate: Long, tasks: List<MorningTask>) {
        // A task of another business is never stored under this one.
        days.getOrPut(businessId) { LinkedHashMap() }[businessDate] = tasks.filter { it.businessId == businessId }
    }

    override suspend fun queuedReminders(businessId: String): List<MorningTask> =
        days[businessId]?.values?.flatten().orEmpty().filter { it.pendingReminderAt != null && it.reminderId == null }

    override suspend fun ownReminderIds(businessId: String): Set<String> =
        days[businessId]?.values?.flatten().orEmpty().mapNotNull { it.reminderId }.toSet()

    /** Everything stored for a business (tests: history is never deleted). */
    fun all(businessId: String): List<MorningTask> = days[businessId]?.values?.flatten().orEmpty()
}
