package com.shopai.app.books.integration

import android.content.Context
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.data.BusinessEntity
import com.shopai.app.books.engine.BooksContext
import com.shopai.app.books.engine.BooksEngine
import com.shopai.app.books.engine.BooksError
import com.shopai.app.books.engine.BooksLedger
import com.shopai.app.books.engine.BooksMasters

/** The open books of one business: engine (writes), masters, ledger (reads). */
class BooksSession(val db: BooksDatabase, val ctx: BooksContext) {
    val engine = BooksEngine(db, ctx)
    val masters = BooksMasters(db, ctx)
    val ledger = BooksLedger(db, ctx.businessId)
    val dao get() = db.dao()
    val cashAccountId: String get() = masters.cashAccountId()
}

/** An engine rejection surfaced to the existing screens with its own message. */
class BooksRejectedException(val errors: List<BooksError>) :
    IllegalArgumentException(errors.joinToString("\n") { it.message })

/**
 * Decides where customers, suppliers, credit/debit and inventory live:
 * until the one-time import has finished they stay on the OwnerNote backend
 * exactly as before; from then on the on-device books are the only source of
 * truth and the old backend ledger is never written again.
 */
class BooksModule(
    context: Context,
    private val databaseProvider: () -> BooksDatabase = { BooksDatabase.get(context) },
) {
    private val prefs = context.applicationContext.getSharedPreferences("owner_books", Context.MODE_PRIVATE)
    val db: BooksDatabase by lazy(databaseProvider)

    @Volatile
    private var cached: BooksSession? = null

    /** Books of the signed-in business, or null while the backend ledger is still in use. */
    suspend fun session(): BooksSession? {
        cached?.let { return it }
        val businessId = prefs.getString(KEY_BUSINESS, null) ?: return null
        val userId = prefs.getString(KEY_USER, null) ?: return null
        val business = db.dao().business(businessId)?.takeIf { it.importedAt != null } ?: return null
        return open(business, userId).also { cached = it }
    }

    /** The signed-in business's books, imported or not (used by the importer). */
    internal suspend fun openFor(businessId: String, userId: String): BooksSession {
        prefs.edit().putString(KEY_BUSINESS, businessId).putString(KEY_USER, userId).apply()
        cached = null
        return BooksSession(db, contextFor(businessId, userId))
    }

    /** True when the signed-in business has not been moved to the books yet. */
    suspend fun needsImport(): Boolean = session() == null

    /** Logout: the next account on this phone gets its own books. */
    fun signOut() {
        prefs.edit().clear().apply()
        cached = null
    }

    internal fun invalidate() {
        cached = null
    }

    private fun open(business: BusinessEntity, userId: String) = BooksSession(db, contextFor(business.id, userId))

    private fun contextFor(businessId: String, userId: String) =
        BooksContext(businessId = businessId, branchId = "$businessId-main", warehouseId = "$businessId-main", userId = userId)

    private companion object {
        const val KEY_BUSINESS = "business_id"
        const val KEY_USER = "user_id"
    }
}
