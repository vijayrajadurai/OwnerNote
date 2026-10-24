package com.shopai.app.data.local.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.shopai.app.brain.tools.KeptItem
import com.shopai.app.brain.tools.KeptText
import java.time.LocalDateTime

/**
 * "Enga Vechen?" (owner, 24 Oct 2026): the things the owner told Kai where they kept — on this phone only, one login's rows
 * ([businessId] + [ownerId]). Never synced, never the books. [placeParts] and [history] are [KeptText]'s text forms.
 */
@Entity(tableName = "kept_items", indices = [Index("businessId", "ownerId")])
data class KeptItemEntity(
    @PrimaryKey val id: String,
    val businessId: String,
    val ownerId: String,
    val item: String,
    val itemKey: String?,
    val placeParts: String,
    val person: String?,
    val keptBy: String?,
    val photo: String?,
    val createdAt: String,
    val updatedAt: String,
    /** ACTIVE / CLOSED */
    val status: String,
    val history: String,
) {
    fun toItem() = KeptItem(
        id = id, item = item, itemKey = itemKey, placeParts = KeptText.parts(placeParts), person = person, keptBy = keptBy, photo = photo,
        createdAt = LocalDateTime.parse(createdAt), updatedAt = LocalDateTime.parse(updatedAt),
        status = runCatching { KeptItem.Status.valueOf(status) }.getOrDefault(KeptItem.Status.ACTIVE), history = KeptText.history(history),
    )

    companion object {
        fun of(k: KeptItem, businessId: String, ownerId: String) = KeptItemEntity(
            k.id, businessId, ownerId, k.item, k.itemKey, KeptText.parts(k.placeParts), k.person, k.keptBy, k.photo,
            k.createdAt.toString(), k.updatedAt.toString(), k.status.name, KeptText.history(k.history),
        )
    }
}

@Dao
interface KeptItemsDao {
    @Query("SELECT * FROM kept_items WHERE businessId = :businessId AND ownerId = :ownerId ORDER BY updatedAt DESC")
    suspend fun all(businessId: String, ownerId: String): List<KeptItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: KeptItemEntity): Long

    @Query("DELETE FROM kept_items WHERE id = :id AND businessId = :businessId AND ownerId = :ownerId")
    suspend fun delete(id: String, businessId: String, ownerId: String): Int
}

/** Kai's and the Enga Vechen screen's one door to the table — the signed-in login's rows only. */
class KeptItemsRepository(private val dao: KeptItemsDao, private val scope: () -> Pair<String?, String?>) {
    private fun login(): Pair<String, String> = scope().let { (b, o) -> b.orEmpty() to o.orEmpty() }

    suspend fun all(): List<KeptItem> = login().let { (b, o) -> dao.all(b, o).mapNotNull { runCatching { it.toItem() }.getOrNull() } }

    /** True only when the row is in the table. */
    suspend fun save(item: KeptItem): Boolean = runCatching { login().let { (b, o) -> dao.upsert(KeptItemEntity.of(item, b, o)) } >= 0 }.getOrDefault(false)

    suspend fun delete(id: String): Boolean = runCatching { login().let { (b, o) -> dao.delete(id, b, o) } > 0 }.getOrDefault(false)
}
