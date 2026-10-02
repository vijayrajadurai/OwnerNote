package com.shopai.app.books.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

/** A document still open for allocation, with what is left on it. */
data class OpenDoc(
    val id: String,
    val partyId: String?,
    val type: String,
    val number: String,
    val date: Int,
    val dueDate: Int?,
    val totalPaise: Long,
    val allocatedPaise: Long,
) {
    val outstandingPaise: Long get() = totalPaise - allocatedPaise
}

data class PartyBalanceRow(val partyId: String, val balancePaise: Long)

data class StockRow(val qtyMilli: Long, val valuePaise: Long)

data class ProductStockRow(val productId: String, val qtyMilli: Long, val valuePaise: Long)

data class HsnSummaryRow(
    val hsnCode: String?,
    val qtyMilli: Long,
    val taxablePaise: Long,
    val cgstPaise: Long,
    val sgstPaise: Long,
    val igstPaise: Long,
    val cessPaise: Long,
)

/** A stock movement with the document it came from. */
data class MovementView(
    val txnId: String,
    val lineNo: Int,
    val productId: String,
    val batchId: String?,
    val date: Int,
    val type: String,
    val qtyMilli: Long,
    val valuePaise: Long,
    val reason: String?,
    val txnType: String,
    val number: String,
    val createdAt: Long,
)

/** Money applied to a document, with the payment / note it came from. */
data class AppliedPayment(
    val txnId: String,
    val type: String,
    val number: String,
    val date: Int,
    val createdAt: Long,
    val reference: String?,
    val notes: String?,
    val amountPaise: Long,
)

@Dao
interface BooksDao {
    // ---- business / setup ----
    @Upsert suspend fun upsertBusiness(b: BusinessEntity)
    @Query("SELECT * FROM businesses WHERE id = :id") suspend fun business(id: String): BusinessEntity?
    @Query("SELECT * FROM users WHERE businessId = :businessId AND role = 'OWNER' LIMIT 1") suspend fun owner(businessId: String): UserEntity?
    @Upsert suspend fun upsertBranch(b: BranchEntity)
    @Upsert suspend fun upsertWarehouse(w: WarehouseEntity)
    @Upsert suspend fun upsertUser(u: UserEntity)
    @Query("SELECT * FROM users WHERE id = :id") suspend fun user(id: String): UserEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertUnits(units: List<UnitEntity>)
    @Query("SELECT * FROM units WHERE businessId = :businessId ORDER BY custom, code") suspend fun units(businessId: String): List<UnitEntity>

    // ---- parties ----
    @Insert suspend fun insertParty(p: PartyEntity)
    @Update suspend fun updateParty(p: PartyEntity)
    @Query("SELECT * FROM parties WHERE id = :id") suspend fun party(id: String): PartyEntity?
    @Query("SELECT * FROM parties WHERE businessId = :businessId AND kind = :kind AND nameKey = :nameKey AND active = 1 LIMIT 1")
    suspend fun partyByName(businessId: String, kind: String, nameKey: String): PartyEntity?
    @Query("SELECT * FROM parties WHERE businessId = :businessId AND backendId = :backendId LIMIT 1")
    suspend fun partyByBackendId(businessId: String, backendId: String): PartyEntity?
    @Query(
        """SELECT * FROM parties WHERE businessId = :businessId AND kind = :kind AND active = 1
           AND (nameKey LIKE '%' || :q || '%' OR mobile LIKE '%' || :q || '%')
           ORDER BY nameKey LIMIT :limit OFFSET :offset""",
    )
    suspend fun searchParties(businessId: String, kind: String, q: String, limit: Int, offset: Int): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE businessId = :businessId AND kind = :kind AND active = 1 ORDER BY nameKey")
    suspend fun parties(businessId: String, kind: String): List<PartyEntity>
    @Query(
        """SELECT * FROM txns WHERE businessId = :businessId AND partyId = :partyId AND status = 'CONFIRMED'
           ORDER BY date DESC, createdAt DESC""",
    )
    suspend fun partyTxns(businessId: String, partyId: String): List<TxnEntity>
    @Query(
        """SELECT t.id AS txnId, t.type, t.number, t.date, t.createdAt, t.reference, t.notes, a.amountPaise
           FROM allocations a JOIN txns t ON t.id = a.fromTxnId
           WHERE a.toTxnId = :txnId AND a.active = 1 ORDER BY t.date, t.createdAt""",
    )
    suspend fun appliedTo(txnId: String): List<AppliedPayment>

    // ---- categories / brands ----
    @Insert suspend fun insertCategory(c: CategoryEntity)
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun category(id: String): CategoryEntity?
    @Query(
        """SELECT * FROM categories WHERE businessId = :businessId AND nameKey = :nameKey
           AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId) LIMIT 1""",
    )
    suspend fun categoryByName(businessId: String, nameKey: String, parentId: String?): CategoryEntity?
    @Query("SELECT * FROM categories WHERE businessId = :businessId ORDER BY nameKey") suspend fun categories(businessId: String): List<CategoryEntity>
    @Insert suspend fun insertBrand(b: BrandEntity)
    @Query("SELECT * FROM brands WHERE id = :id") suspend fun brand(id: String): BrandEntity?
    @Query("SELECT * FROM brands WHERE businessId = :businessId AND nameKey = :nameKey LIMIT 1")
    suspend fun brandByName(businessId: String, nameKey: String): BrandEntity?

    // ---- products ----
    @Query(
        """SELECT * FROM products WHERE businessId = :businessId AND archived = :archived
           ORDER BY nameKey LIMIT :limit OFFSET :offset""",
    )
    suspend fun products(businessId: String, archived: Boolean, limit: Int, offset: Int): List<ProductEntity>
    @Query("SELECT * FROM products WHERE businessId = :businessId AND backendId = :backendId LIMIT 1")
    suspend fun productByBackendId(businessId: String, backendId: String): ProductEntity?
    @Query(
        """SELECT m.txnId, m.lineNo, m.productId, m.batchId, m.date, m.type, m.qtyMilli, m.valuePaise, m.reason,
                  t.type AS txnType, t.number, t.createdAt
           FROM stock_movements m JOIN txns t ON t.id = m.txnId
           WHERE m.businessId = :businessId AND m.productId = :productId AND m.active = 1
           ORDER BY m.date, t.createdAt, m.lineNo""",
    )
    suspend fun productMovements(businessId: String, productId: String): List<MovementView>
    @Insert suspend fun insertProduct(p: ProductEntity)
    @Update suspend fun updateProduct(p: ProductEntity)
    @Query("SELECT * FROM products WHERE id = :id") suspend fun product(id: String): ProductEntity?
    @Query("SELECT * FROM products WHERE businessId = :businessId AND nameKey = :nameKey AND archived = 0 LIMIT 1")
    suspend fun productByName(businessId: String, nameKey: String): ProductEntity?
    @Query("SELECT * FROM products WHERE businessId = :businessId AND barcode = :barcode AND active = 1 AND archived = 0")
    suspend fun activeProductsByBarcode(businessId: String, barcode: String): List<ProductEntity>
    @Query("SELECT * FROM products WHERE businessId = :businessId AND sku = :sku AND archived = 0 LIMIT 1")
    suspend fun productBySku(businessId: String, sku: String): ProductEntity?
    @Query(
        """SELECT * FROM products WHERE businessId = :businessId AND archived = 0
           AND (nameKey LIKE '%' || :q || '%' OR sku = :q OR barcode = :q OR hsnCode = :q)
           ORDER BY nameKey LIMIT :limit OFFSET :offset""",
    )
    suspend fun searchProducts(businessId: String, q: String, limit: Int, offset: Int): List<ProductEntity>
    @Query("SELECT * FROM products WHERE businessId = :businessId AND hsnCode = :hsn AND archived = 0 ORDER BY nameKey")
    suspend fun productsByHsn(businessId: String, hsn: String): List<ProductEntity>
    @Upsert suspend fun upsertBatch(b: BatchEntity)
    @Query("SELECT * FROM batches WHERE id = :id") suspend fun batch(id: String): BatchEntity?
    @Query("SELECT * FROM batches WHERE productId = :productId AND batchNo = :batchNo LIMIT 1")
    suspend fun batchByNo(productId: String, batchNo: String): BatchEntity?

    // ---- HSN / SAC master ----
    @Upsert suspend fun upsertHsn(rows: List<HsnSacEntity>)
    @Query("SELECT * FROM hsn_sac WHERE code = :code AND kind = :kind") suspend fun hsn(code: String, kind: String): HsnSacEntity?
    @Query("SELECT * FROM hsn_sac WHERE code LIKE :q || '%' OR description LIKE '%' || :q || '%' ORDER BY code LIMIT :limit")
    suspend fun searchHsn(q: String, limit: Int): List<HsnSacEntity>

    // ---- money accounts ----
    @Upsert suspend fun upsertMoneyAccount(a: MoneyAccountEntity)
    @Query("SELECT * FROM money_accounts WHERE id = :id") suspend fun moneyAccount(id: String): MoneyAccountEntity?
    @Query("SELECT * FROM money_accounts WHERE businessId = :businessId AND active = 1 ORDER BY kind, name")
    suspend fun moneyAccounts(businessId: String): List<MoneyAccountEntity>

    // ---- documents ----
    @Insert suspend fun insertTxn(t: TxnEntity)
    @Update suspend fun updateTxn(t: TxnEntity)
    @Insert suspend fun insertItems(items: List<TxnItemEntity>)
    @Insert suspend fun insertPostings(p: List<PostingEntity>)
    @Insert suspend fun insertMovements(m: List<StockMovementEntity>)
    @Insert suspend fun insertAllocations(a: List<AllocationEntity>)
    @Query("SELECT * FROM txns WHERE id = :id") suspend fun txn(id: String): TxnEntity?
    @Query("SELECT * FROM txns WHERE clientKey = :key") suspend fun txnByClientKey(key: String): TxnEntity?
    @Query("SELECT * FROM txns WHERE businessId = :businessId AND type = :type AND numberScope = :scope AND number = :number LIMIT 1")
    suspend fun txnByNumber(businessId: String, type: String, scope: String, number: String): TxnEntity?
    @Query("SELECT * FROM txn_items WHERE txnId = :txnId ORDER BY lineNo") suspend fun items(txnId: String): List<TxnItemEntity>
    @Query("SELECT * FROM postings WHERE txnId = :txnId ORDER BY id") suspend fun postings(txnId: String): List<PostingEntity>
    @Query("SELECT * FROM stock_movements WHERE txnId = :txnId ORDER BY lineNo") suspend fun movements(txnId: String): List<StockMovementEntity>
    @Query("SELECT * FROM txns WHERE linkedTxnId = :txnId AND status = 'CONFIRMED'") suspend fun linkedTxns(txnId: String): List<TxnEntity>
    @Query(
        """SELECT * FROM txns WHERE businessId = :businessId AND date BETWEEN :from AND :to
           AND (:type IS NULL OR type = :type) ORDER BY date DESC, createdAt DESC LIMIT :limit OFFSET :offset""",
    )
    suspend fun txnsBetween(businessId: String, from: Int, to: Int, type: String?, limit: Int, offset: Int): List<TxnEntity>

    @Query(
        """SELECT * FROM txns WHERE businessId = :businessId AND type IN (:types)
           AND (:partyId IS NULL OR partyId = :partyId)
           AND (:q = '' OR number LIKE '%' || :q || '%' OR partyName LIKE '%' || :q || '%')
           ORDER BY date DESC, createdAt DESC LIMIT :limit OFFSET :offset""",
    )
    suspend fun txnsOfTypes(businessId: String, types: List<String>, partyId: String?, q: String, limit: Int, offset: Int): List<TxnEntity>

    @Query("SELECT * FROM number_series WHERE businessId = :businessId AND type = :type")
    suspend fun series(businessId: String, type: String): NumberSeriesEntity?
    @Upsert suspend fun upsertSeries(s: NumberSeriesEntity)

    // ---- void (soft) ----
    @Query("UPDATE postings SET active = 0 WHERE txnId = :txnId") suspend fun deactivatePostings(txnId: String)
    @Query("UPDATE stock_movements SET active = 0 WHERE txnId = :txnId") suspend fun deactivateMovements(txnId: String)
    @Query("UPDATE txn_items SET active = 0 WHERE txnId = :txnId") suspend fun deactivateItems(txnId: String)
    @Query("UPDATE allocations SET active = 0 WHERE fromTxnId = :txnId") suspend fun deactivateAllocationsFrom(txnId: String)

    // ---- allocations ----
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM allocations WHERE toTxnId = :txnId AND active = 1")
    suspend fun allocatedTo(txnId: String): Long
    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM allocations WHERE fromTxnId = :txnId AND active = 1")
    suspend fun allocatedFrom(txnId: String): Long
    @Query("SELECT * FROM allocations WHERE toTxnId = :txnId AND active = 1") suspend fun allocationsTo(txnId: String): List<AllocationEntity>
    @Query("SELECT * FROM allocations WHERE fromTxnId = :txnId AND active = 1") suspend fun allocationsFrom(txnId: String): List<AllocationEntity>

    /** Documents of [types] for a party that still have something left on them, oldest due first. */
    @Query(
        """SELECT t.id, t.partyId, t.type, t.number, t.date, t.dueDate, t.totalPaise,
                  COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0) AS allocatedPaise
           FROM txns t
           WHERE t.businessId = :businessId AND t.partyId = :partyId AND t.status = 'CONFIRMED'
             AND t.type IN (:types) AND t.isAdvance = 0
             AND t.totalPaise > COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0)
           ORDER BY COALESCE(t.dueDate, t.date), t.date, t.createdAt""",
    )
    suspend fun openDocs(businessId: String, partyId: String, types: List<String>): List<OpenDoc>

    /** Every party's open documents of [types] — for ageing. */
    @Query(
        """SELECT t.id, t.partyId, t.type, t.number, t.date, t.dueDate, t.totalPaise,
                  COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0) AS allocatedPaise
           FROM txns t
           WHERE t.businessId = :businessId AND t.status = 'CONFIRMED' AND t.partyId IS NOT NULL
             AND t.type IN (:types) AND t.isAdvance = 0 AND EXISTS (SELECT 1 FROM parties p WHERE p.id = t.partyId AND p.kind = :partyKind)
             AND t.totalPaise > COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0)
           ORDER BY COALESCE(t.dueDate, t.date)""",
    )
    suspend fun openDocsAll(businessId: String, types: List<String>, partyKind: String): List<OpenDoc>

    @Query(
        """SELECT * FROM txn_items WHERE txnId IN
           (SELECT id FROM txns WHERE linkedTxnId = :originalTxnId AND type = :returnType AND status = 'CONFIRMED')""",
    )
    suspend fun returnedItems(originalTxnId: String, returnType: String): List<TxnItemEntity>

    @Query("SELECT * FROM stock_movements WHERE txnId = :txnId AND lineNo = :lineNo AND active = 1 LIMIT 1")
    suspend fun movement(txnId: String, lineNo: Int): StockMovementEntity?

    @Query(
        """SELECT COUNT(*) FROM txns WHERE businessId = :businessId AND partyId = :partyId AND type = :type
           AND status = 'CONFIRMED' AND date = :date AND totalPaise = :amount AND COALESCE(paymentMode, '') = :mode
           AND COALESCE(reference, '') = :reference""",
    )
    suspend fun samePayments(businessId: String, partyId: String, type: String, date: Int, amount: Long, mode: String, reference: String): Int

    @Query(
        """SELECT COUNT(*) FROM txns WHERE businessId = :businessId AND partyId = :partyId AND type = 'OPENING_BALANCE'
           AND status = 'CONFIRMED'""",
    )
    suspend fun partyOpenings(businessId: String, partyId: String): Int

    /** Party statement lines (debit/credit on the party's account), oldest first. */
    @Query(
        """SELECT p.* FROM postings p WHERE p.businessId = :businessId AND p.account = :account AND p.partyId = :partyId
           AND p.active = 1 ORDER BY p.date, p.id""",
    )
    suspend fun partyPostings(businessId: String, account: String, partyId: String): List<PostingEntity>

    /** Payments, credit/debit notes and advance openings with money not yet applied. */
    @Query(
        """SELECT t.id, t.partyId, t.type, t.number, t.date, t.dueDate, t.totalPaise,
                  COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.fromTxnId = t.id AND a.active = 1), 0) AS allocatedPaise
           FROM txns t
           WHERE t.businessId = :businessId AND t.partyId = :partyId AND t.status = 'CONFIRMED'
             AND (t.type IN (:types) OR (t.type = 'OPENING_BALANCE' AND t.isAdvance = 1))
             AND t.totalPaise > COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.fromTxnId = t.id AND a.active = 1), 0)
           ORDER BY t.date, t.createdAt""",
    )
    suspend fun unappliedCredits(businessId: String, partyId: String, types: List<String>): List<OpenDoc>

    // ---- balances (the single source of truth) ----
    /** Debit − credit on an account head, optionally for one party / money account, up to a date. */
    @Query(
        """SELECT COALESCE(SUM(debitPaise - creditPaise), 0) FROM postings
           WHERE businessId = :businessId AND account = :account AND active = 1
             AND (:partyId IS NULL OR partyId = :partyId)
             AND (:moneyAccountId IS NULL OR moneyAccountId = :moneyAccountId)
             AND date <= :upTo""",
    )
    suspend fun netDebit(businessId: String, account: String, partyId: String?, moneyAccountId: String?, upTo: Int): Long

    @Query(
        """SELECT COALESCE(SUM(debitPaise), 0) FROM postings
           WHERE businessId = :businessId AND account = :account AND active = 1
             AND (:moneyAccountId IS NULL OR moneyAccountId = :moneyAccountId) AND date BETWEEN :from AND :to""",
    )
    suspend fun debitsBetween(businessId: String, account: String, moneyAccountId: String?, from: Int, to: Int): Long

    @Query(
        """SELECT COALESCE(SUM(creditPaise), 0) FROM postings
           WHERE businessId = :businessId AND account = :account AND active = 1
             AND (:moneyAccountId IS NULL OR moneyAccountId = :moneyAccountId) AND date BETWEEN :from AND :to""",
    )
    suspend fun creditsBetween(businessId: String, account: String, moneyAccountId: String?, from: Int, to: Int): Long

    @Query(
        """SELECT partyId, SUM(debitPaise - creditPaise) AS balancePaise FROM postings
           WHERE businessId = :businessId AND account = :account AND active = 1 AND partyId IS NOT NULL
           GROUP BY partyId""",
    )
    suspend fun partyNetDebits(businessId: String, account: String): List<PartyBalanceRow>

    @Query(
        """SELECT COALESCE(SUM(debitPaise), 0) FROM postings
           WHERE businessId = :businessId AND account = 'MONEY' AND moneyAccountId = :moneyAccountId AND active = 1""",
    )
    suspend fun moneyIn(businessId: String, moneyAccountId: String): Long

    @Query(
        """SELECT COALESCE(SUM(qtyMilli), 0) AS qtyMilli, COALESCE(SUM(valuePaise), 0) AS valuePaise FROM stock_movements
           WHERE businessId = :businessId AND productId = :productId AND active = 1
             AND (:warehouseId IS NULL OR warehouseId = :warehouseId)""",
    )
    suspend fun stock(businessId: String, productId: String, warehouseId: String?): StockRow

    @Query(
        """SELECT COALESCE(SUM(qtyMilli), 0) AS qtyMilli, COALESCE(SUM(valuePaise), 0) AS valuePaise FROM stock_movements
           WHERE businessId = :businessId AND productId = :productId AND batchId = :batchId AND active = 1""",
    )
    suspend fun batchStock(businessId: String, productId: String, batchId: String): StockRow

    @Query(
        """SELECT productId, SUM(qtyMilli) AS qtyMilli, SUM(valuePaise) AS valuePaise FROM stock_movements
           WHERE businessId = :businessId AND active = 1 GROUP BY productId""",
    )
    suspend fun allStock(businessId: String): List<ProductStockRow>

    @Query(
        """SELECT COALESCE(SUM(-valuePaise), 0) FROM stock_movements
           WHERE businessId = :businessId AND txnId = :txnId AND productId = :productId AND active = 1""",
    )
    suspend fun costOutOn(businessId: String, txnId: String, productId: String): Long

    // ---- tax ----
    @Query(
        """SELECT hsnCode, SUM(baseQtyMilli) AS qtyMilli, SUM(taxablePaise) AS taxablePaise, SUM(cgstPaise) AS cgstPaise,
                  SUM(sgstPaise) AS sgstPaise, SUM(igstPaise) AS igstPaise, SUM(cessPaise) AS cessPaise
           FROM txn_items WHERE businessId = :businessId AND txnType = :txnType AND active = 1 AND date BETWEEN :from AND :to
           GROUP BY hsnCode ORDER BY hsnCode""",
    )
    suspend fun hsnSummary(businessId: String, txnType: String, from: Int, to: Int): List<HsnSummaryRow>

    // ---- audit / sync / drafts ----
    @Insert suspend fun insertAudit(a: AuditEntity)
    @Query("SELECT * FROM audit_log WHERE entityId = :entityId ORDER BY at, id") suspend fun auditFor(entityId: String): List<AuditEntity>
    @Upsert suspend fun upsertOutbox(o: OutboxEntity)
    @Query("SELECT * FROM sync_outbox WHERE businessId = :businessId AND state = :state ORDER BY updatedAt LIMIT :limit")
    suspend fun outbox(businessId: String, state: String, limit: Int): List<OutboxEntity>
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE businessId = :businessId AND state = :state")
    suspend fun outboxCount(businessId: String, state: String): Int
    @Upsert suspend fun upsertDraft(d: DraftEntity)
    @Query("SELECT * FROM drafts WHERE id = :id") suspend fun draft(id: String): DraftEntity?
    @Query("SELECT * FROM drafts WHERE businessId = :businessId AND status = 'OPEN' ORDER BY createdAt DESC")
    suspend fun openDrafts(businessId: String): List<DraftEntity>
}
