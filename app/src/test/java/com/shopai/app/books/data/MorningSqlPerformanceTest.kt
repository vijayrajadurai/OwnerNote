package com.shopai.app.books.data

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.random.Random

/**
 * Morning Work's bounded SQL ([MorningSql]) on a real SQLite with a large
 * business: 10,000 customers, 50,000 transactions, 10,000 products (plus a
 * second business that must never show up). The tables and indexes are the
 * books' own (BooksEntities). Checks:
 *  - every list query returns at most its LIMIT rows;
 *  - the bounded answer is EXACTLY the top of the full calculation the app
 *    uses today (open bills via openDocsAll, stock via the stock movements) —
 *    same balances, same due days, same order;
 *  - a fixed number of queries, and how long each takes;
 *  - rows the app receives, bounded vs. the old full read.
 */
class MorningSqlPerformanceTest {
    companion object {
        private const val BIZ = "biz-A"
        private const val OTHER = "biz-B"
        private const val LIMIT = 15
        private val today = LocalDate.of(2026, 10, 3).toEpochDay().toInt()
        private lateinit var db: Connection

        @BeforeClass
        @JvmStatic
        fun seed() {
            Class.forName("org.sqlite.JDBC")
            db = DriverManager.getConnection("jdbc:sqlite::memory:")
            db.createStatement().use { st -> SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { st.execute(it) } }
            db.autoCommit = false
            val rnd = Random(42)
            fun party(biz: String, id: String, kind: String, i: Int) = db.prepareStatement(
                "INSERT INTO parties (id, businessId, kind, name, nameKey, mobile, active) VALUES (?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id); ps.setString(2, biz); ps.setString(3, kind); ps.setString(4, "$kind $i"); ps.setString(5, "${kind.lowercase()} $i")
                ps.setString(6, "9${(100000000 + i)}"); ps.setInt(7, if (i % 97 == 0) 0 else 1); ps.executeUpdate()
            }
            for (i in 0 until 10_000) party(BIZ, "c$i", "CUSTOMER", i)
            for (i in 0 until 1_000) party(BIZ, "s$i", "SUPPLIER", i)
            for (i in 0 until 500) party(OTHER, "oc$i", "CUSTOMER", i)

            val txn = db.prepareStatement(
                "INSERT INTO txns (id, businessId, type, number, date, dueDate, partyId, status, totalPaise, isAdvance, createdAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            )
            val alloc = db.prepareStatement("INSERT INTO allocations (businessId, fromTxnId, toTxnId, amountPaise, active, createdAt) VALUES (?,?,?,?,?,0)")
            fun addTxn(biz: String, id: String, type: String, partyId: String, n: Int) {
                val date = today - rnd.nextInt(0, 150)
                val due: Int? = if (rnd.nextInt(2) == 0) null else date + rnd.nextInt(0, 40)
                val total = rnd.nextLong(10_000, 5_000_000)
                val status = if (rnd.nextInt(20) == 0) "DRAFT" else "CONFIRMED"
                txn.setString(1, id); txn.setString(2, biz); txn.setString(3, type); txn.setString(4, "N$n"); txn.setInt(5, date)
                if (due == null) txn.setNull(6, java.sql.Types.INTEGER) else txn.setInt(6, due)
                txn.setString(7, partyId); txn.setString(8, status); txn.setLong(9, total); txn.setInt(10, if (rnd.nextInt(50) == 0) 1 else 0); txn.setLong(11, n.toLong())
                txn.addBatch()
                when (rnd.nextInt(10)) {
                    in 0..3 -> { // fully paid
                        alloc.setString(1, biz); alloc.setString(2, "pay-$id"); alloc.setString(3, id); alloc.setLong(4, total); alloc.setInt(5, 1); alloc.addBatch()
                    }
                    in 4..5 -> { // part paid (+ an inactive allocation that must not count)
                        alloc.setString(1, biz); alloc.setString(2, "pay-$id"); alloc.setString(3, id); alloc.setLong(4, total / 3); alloc.setInt(5, 1); alloc.addBatch()
                        alloc.setString(1, biz); alloc.setString(2, "void-$id"); alloc.setString(3, id); alloc.setLong(4, total); alloc.setInt(5, 0); alloc.addBatch()
                    }
                    else -> Unit
                }
            }
            for (n in 0 until 45_000) addTxn(BIZ, "t$n", if (n % 30 == 0) "OPENING_BALANCE" else "SALE", "c${rnd.nextInt(10_000)}", n)
            for (n in 45_000 until 50_000) addTxn(BIZ, "t$n", "PURCHASE", "s${rnd.nextInt(1_000)}", n)
            for (n in 0 until 2_000) addTxn(OTHER, "o$n", "SALE", "oc${rnd.nextInt(500)}", n)
            txn.executeBatch(); alloc.executeBatch()

            val prod = db.prepareStatement(
                "INSERT INTO products (id, businessId, name, nameKey, isService, primaryUnit, minStockMilli, reorderLevelMilli, active, archived) VALUES (?,?,?,?,?,?,?,?,?,?)",
            )
            val move = db.prepareStatement("INSERT INTO stock_movements (txnId, lineNo, businessId, warehouseId, productId, batchId, date, qtyMilli, active) VALUES (?,?,?,?,?,?,?,?,?)")
            val batch = db.prepareStatement("INSERT INTO batches (id, businessId, productId, batchNo, expiryDay, createdAt) VALUES (?,?,?,?,?,0)")
            var line = 0
            fun addProduct(biz: String, id: String, i: Int) {
                val level = rnd.nextInt(5) == 0
                prod.setString(1, id); prod.setString(2, biz); prod.setString(3, "Product $i"); prod.setString(4, "product $i"); prod.setInt(5, if (i % 200 == 0) 1 else 0)
                prod.setString(6, "PCS")
                if (level && rnd.nextBoolean()) prod.setLong(7, rnd.nextLong(1_000, 20_000)) else prod.setNull(7, java.sql.Types.INTEGER)
                if (level) prod.setLong(8, rnd.nextLong(1_000, 30_000)) else prod.setNull(8, java.sql.Types.INTEGER)
                prod.setInt(9, 1); prod.setInt(10, if (i % 150 == 0) 1 else 0); prod.addBatch()
                val batchId = if (i % 4 == 0) "b-$id" else null
                if (batchId != null) {
                    batch.setString(1, batchId); batch.setString(2, biz); batch.setString(3, id); batch.setString(4, "B$i"); batch.setInt(5, today + rnd.nextInt(-20, 60)); batch.addBatch()
                }
                repeat(rnd.nextInt(0, 6)) {
                    move.setString(1, "m${line}"); move.setInt(2, line++); move.setString(3, biz); move.setString(4, "w1"); move.setString(5, id)
                    if (batchId != null) move.setString(6, batchId) else move.setNull(6, java.sql.Types.VARCHAR)
                    move.setInt(7, today - rnd.nextInt(90)); move.setLong(8, rnd.nextLong(-15_000, 25_000)); move.setInt(9, if (rnd.nextInt(30) == 0) 0 else 1); move.addBatch()
                }
            }
            for (i in 0 until 10_000) addProduct(BIZ, "p$i", i)
            for (i in 0 until 300) addProduct(OTHER, "op$i", i)
            prod.executeBatch(); move.executeBatch(); batch.executeBatch()
            db.prepareStatement("INSERT INTO drafts (id, businessId, kind, source, payloadJson, status, createdAt, updatedAt) VALUES (?,?,?,?,?,?,?,?)").use { ps ->
                for (i in 0 until 120) {
                    ps.setString(1, "d$i"); ps.setString(2, if (i < 100) BIZ else OTHER); ps.setString(3, "PURCHASE"); ps.setString(4, "VOICE"); ps.setString(5, "{}")
                    ps.setString(6, if (i % 5 == 0) "DONE" else "OPEN"); ps.setLong(7, i.toLong()); ps.setLong(8, i.toLong()); ps.addBatch()
                }
                ps.executeBatch()
            }
            db.commit()
            db.createStatement().use { it.execute("ANALYZE") }
        }

        @AfterClass
        @JvmStatic
        fun close() = db.close()

        /** The books' tables and indexes (BooksEntities), with the columns these queries touch. */
        private val SCHEMA = """
            CREATE TABLE parties (id TEXT PRIMARY KEY NOT NULL, businessId TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL,
              mobile TEXT, backendId TEXT, active INTEGER NOT NULL);
            CREATE INDEX index_parties_businessId_kind_nameKey ON parties (businessId, kind, nameKey);
            CREATE INDEX index_parties_businessId_mobile ON parties (businessId, mobile);
            CREATE INDEX index_parties_backendId ON parties (backendId);
            CREATE TABLE txns (id TEXT PRIMARY KEY NOT NULL, businessId TEXT NOT NULL, type TEXT NOT NULL, number TEXT NOT NULL, numberScope TEXT NOT NULL DEFAULT '',
              date INTEGER NOT NULL, dueDate INTEGER, partyId TEXT, status TEXT NOT NULL, totalPaise INTEGER NOT NULL, linkedTxnId TEXT,
              isAdvance INTEGER NOT NULL, clientKey TEXT, createdAt INTEGER NOT NULL);
            CREATE INDEX index_txns_businessId_date ON txns (businessId, date);
            CREATE INDEX index_txns_businessId_type_date ON txns (businessId, type, date);
            CREATE INDEX index_txns_partyId ON txns (partyId);
            CREATE INDEX index_txns_linkedTxnId ON txns (linkedTxnId);
            CREATE TABLE allocations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, businessId TEXT NOT NULL, fromTxnId TEXT NOT NULL, toTxnId TEXT NOT NULL,
              amountPaise INTEGER NOT NULL, active INTEGER NOT NULL, createdAt INTEGER NOT NULL);
            CREATE INDEX index_allocations_fromTxnId ON allocations (fromTxnId);
            CREATE INDEX index_allocations_toTxnId ON allocations (toTxnId);
            CREATE TABLE products (id TEXT PRIMARY KEY NOT NULL, businessId TEXT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, isService INTEGER NOT NULL,
              primaryUnit TEXT NOT NULL, minStockMilli INTEGER, reorderLevelMilli INTEGER, active INTEGER NOT NULL, archived INTEGER NOT NULL, supplierId TEXT);
            CREATE INDEX index_products_businessId_nameKey ON products (businessId, nameKey);
            CREATE TABLE batches (id TEXT PRIMARY KEY NOT NULL, businessId TEXT NOT NULL, productId TEXT NOT NULL, batchNo TEXT NOT NULL, mfgDay INTEGER,
              expiryDay INTEGER, createdAt INTEGER NOT NULL);
            CREATE UNIQUE INDEX index_batches_productId_batchNo ON batches (productId, batchNo);
            CREATE INDEX index_batches_businessId_expiryDay ON batches (businessId, expiryDay);
            CREATE TABLE stock_movements (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, txnId TEXT NOT NULL, lineNo INTEGER NOT NULL, businessId TEXT NOT NULL,
              warehouseId TEXT NOT NULL, productId TEXT NOT NULL, batchId TEXT, date INTEGER NOT NULL, qtyMilli INTEGER NOT NULL, active INTEGER NOT NULL);
            CREATE UNIQUE INDEX index_stock_movements_txnId_lineNo ON stock_movements (txnId, lineNo);
            CREATE INDEX index_stock_movements_businessId_productId_warehouseId ON stock_movements (businessId, productId, warehouseId);
            CREATE INDEX index_stock_movements_businessId_productId_batchId ON stock_movements (businessId, productId, batchId);
            CREATE INDEX index_stock_movements_businessId_date ON stock_movements (businessId, date);
            CREATE TABLE drafts (id TEXT PRIMARY KEY NOT NULL, businessId TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, payloadJson TEXT NOT NULL,
              rawInput TEXT, status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL);
            CREATE INDEX index_drafts_businessId_status ON drafts (businessId, status)
        """

        /** The open-bill query the customer / supplier screens use today (BooksDao.openDocsAll) — the reference. */
        private const val OPEN_DOCS_ALL =
            """SELECT t.id, t.partyId, t.type, t.number, t.date, t.dueDate, t.totalPaise,
                  COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0) AS allocatedPaise
           FROM txns t
           WHERE t.businessId = :businessId AND t.status = 'CONFIRMED' AND t.partyId IS NOT NULL
             AND t.type IN (:types) AND t.isAdvance = 0 AND EXISTS (SELECT 1 FROM parties p WHERE p.id = t.partyId AND p.kind = :partyKind)
             AND t.totalPaise > COALESCE((SELECT SUM(a.amountPaise) FROM allocations a WHERE a.toTxnId = t.id AND a.active = 1), 0)
           ORDER BY COALESCE(t.dueDate, t.date)"""
    }

    private val customerTypes = listOf("SALE", "OPENING_BALANCE")
    private val supplierTypes = listOf("PURCHASE", "OPENING_BALANCE")
    private var queries = 0
    private var rowsRead = 0

    /** Runs Room-style SQL (:name and IN (:list) parameters) — the exact strings the DAO uses. */
    private fun rows(sql: String, params: Map<String, Any>): List<Map<String, Any?>> {
        val text = Regex(""":(\w+)""").replace(sql) { m ->
            when (val v = params[m.groupValues[1]] ?: error("missing :${m.groupValues[1]}")) {
                is List<*> -> v.joinToString(",") { "'" + it.toString().replace("'", "''") + "'" }
                is String -> "'" + v.replace("'", "''") + "'"
                else -> v.toString()
            }
        }
        queries++
        db.createStatement().use { st ->
            st.executeQuery(text).use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                val out = mutableListOf<Map<String, Any?>>()
                while (rs.next()) out += cols.associateWith { rs.getObject(it) }
                rowsRead += out.size
                return out
            }
        }
    }

    private fun plan(sql: String, params: Map<String, Any>): String {
        val text = Regex(""":(\w+)""").replace(sql) { m ->
            when (val v = params[m.groupValues[1]]!!) {
                is List<*> -> v.joinToString(",") { "'$it'" }
                is String -> "'$v'"
                else -> v.toString()
            }
        }
        return db.createStatement().use { st -> st.executeQuery("EXPLAIN QUERY PLAN $text").use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) }.joinToString(" | ") } }
    }

    private fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        println("  %-32s %6.1f ms".format(label, (System.nanoTime() - t0) / 1e6))
        return r
    }

    private fun Any?.long() = (this as Number).toLong()
    private fun Any?.int() = (this as Number).toInt()

    private data class Due(val partyId: String, val paise: Long, val nextDue: Int, val partBills: Int)

    /** What the app computes today from the full open-bill list (LegacyBridge: sum of outstanding, min(due ?: date)). */
    private fun reference(kind: String, types: List<String>): List<Due> {
        val active = rows("SELECT id FROM parties WHERE businessId = :b AND kind = :k AND active = 1", mapOf("b" to BIZ, "k" to kind)).map { it["id"] as String }.toSet()
        return rows(OPEN_DOCS_ALL, mapOf("businessId" to BIZ, "types" to types, "partyKind" to kind))
            .filter { it["partyId"] in active }
            .groupBy { it["partyId"] as String }
            .map { (id, docs) ->
                Due(
                    id,
                    docs.sumOf { it["totalPaise"].long() - it["allocatedPaise"].long() },
                    docs.minOf { (it["dueDate"] ?: it["date"]).int() },
                    docs.count { it["allocatedPaise"].long() > 0 },
                )
            }
            .sortedWith(compareBy<Due> { it.nextDue }.thenByDescending { it.paise }.thenBy { it.partyId })
    }

    private fun bounded(list: List<Map<String, Any?>>) = list.map { Due(it["partyId"] as String, it["outstandingPaise"].long(), it["nextDue"].int(), it["partBills"].int()) }

    @Test
    fun dueCustomersAndSuppliersAreTheTopOfTheFullLedgerCalculation() {
        val soon = today + 3
        for ((kind, types) in listOf("CUSTOMER" to customerTypes, "SUPPLIER" to supplierTypes)) {
            val withPartPaid = if (kind == "CUSTOMER") 1 else 0
            val p = mapOf("businessId" to BIZ, "types" to types, "partyKind" to kind, "untilDay" to soon, "withPartPaid" to withPartPaid, "limit" to LIMIT)
            val got = bounded(timed("DUE_PARTIES $kind") { rows(MorningSql.DUE_PARTIES, p) })
            val full = reference(kind, types)
            assertEquals(LIMIT, got.size)
            // Exactly MorningAnalyzer's candidates (due by the upcoming window; customers also part-paid) in its order.
            assertEquals(full.filter { it.nextDue <= soon || (withPartPaid == 1 && it.partBills > 0) }.take(LIMIT), got)
            assertTrue(got.all { it.partyId.startsWith(if (kind == "CUSTOMER") "c" else "s") })

            val sum = timed("PARTY_SUMMARY $kind") { rows(MorningSql.PARTY_SUMMARY, p.minus("limit").minus("withPartPaid") + ("today" to today)).single() }
            assertEquals(full.filter { it.nextDue <= today }.sumOf { it.paise }, sum["duePaise"].long())
            assertEquals(full.count { it.nextDue <= today }, sum["dueParties"].int())
            assertEquals(full.count { it.nextDue <= soon }, sum["soonParties"].int())
            assertEquals(full.count { it.nextDue > soon && it.partBills > 0 }, sum["partPaidLater"].int())
        }
        // Part-paid customers due later are in the customer list too (when they rank in the top).
        val later = reference("CUSTOMER", customerTypes).filter { it.nextDue > soon && it.partBills > 0 }
        assertTrue(later.isNotEmpty())
        println("  plan DUE_PARTIES: " + plan(MorningSql.DUE_PARTIES, mapOf("businessId" to BIZ, "types" to customerTypes, "partyKind" to "CUSTOMER", "untilDay" to soon, "withPartPaid" to 1, "limit" to LIMIT)))
    }

    @Test
    fun lowStockIsTheTopOfTheStockMovements() {
        val got = timed("LOW_STOCK") { rows(MorningSql.LOW_STOCK, mapOf("businessId" to BIZ, "limit" to LIMIT)) }
        // Reference: the old way — every product + one grouped stock query, judged in code like MorningAnalyzer.
        val stock = rows("SELECT productId, SUM(qtyMilli) AS q FROM stock_movements WHERE businessId = :b AND active = 1 GROUP BY productId", mapOf("b" to BIZ))
            .associate { it["productId"] as String to it["q"].long() }
        val all = rows("SELECT * FROM products WHERE businessId = :b AND archived = 0", mapOf("b" to BIZ)).filter { it["active"].int() == 1 && it["isService"].int() == 0 }
        val low = all.mapNotNull { p ->
            val q = stock[p["id"]] ?: 0L
            val reorder = (p["reorderLevelMilli"] as Number?)?.toLong()?.takeIf { it > 0 }
            val min = (p["minStockMilli"] as Number?)?.toLong()?.takeIf { it > 0 }
            if (reorder == null && min == null) return@mapNotNull null
            if (q <= 0 || (reorder != null && q < reorder) || (min != null && q < min)) Triple(p["id"] as String, (p["name"] as String).lowercase(), q) else null
        }.sortedWith(compareBy<Triple<String, String, Long>> { if (it.third <= 0) 0 else 1 }.thenBy { it.second }.thenBy { it.first })
        assertEquals(LIMIT, got.size)
        assertEquals(low.take(LIMIT).map { it.first to it.third }, got.map { it["id"] as String to it["qtyMilli"].long() })
        assertEquals(low.size, rows(MorningSql.LOW_STOCK_COUNT, mapOf("businessId" to BIZ)).single().values.single().int())
    }

    @Test
    fun expiringBatchesWithStockOnly() {
        val until = today + 7
        val got = timed("EXPIRING") { rows(MorningSql.EXPIRING, mapOf("businessId" to BIZ, "untilDay" to until, "limit" to LIMIT)) }
        assertTrue(got.size <= LIMIT)
        assertTrue(got.all { it["qtyMilli"].long() > 0 && it["expiryDay"].int() <= until && (it["productId"] as String).startsWith("p") })
        assertEquals(got.map { it["expiryDay"].int() }, got.map { it["expiryDay"].int() }.sorted())
    }

    @Test
    fun anotherBusinessNeverAppears() {
        val c = rows(MorningSql.DUE_PARTIES, mapOf("businessId" to OTHER, "types" to customerTypes, "partyKind" to "CUSTOMER", "untilDay" to today + 3, "withPartPaid" to 1, "limit" to LIMIT))
        assertTrue(c.isNotEmpty() && c.all { (it["partyId"] as String).startsWith("oc") })
        val drafts = rows(MorningSql.OPEN_DRAFTS, mapOf("businessId" to BIZ, "limit" to LIMIT))
        assertEquals(LIMIT, drafts.size)
        assertTrue(drafts.all { it["businessId"] == BIZ && it["status"] == "OPEN" })
        val ids = c.map { it["partyId"] as String }
        assertTrue(rows(MorningSql.PARTIES_BY_ID, mapOf("businessId" to BIZ, "ids" to ids)).isEmpty())
    }

    /** One whole morning read, as BooksMorningQueries + MorningSnapshotLoader do it: count, time, rows. */
    @Test
    fun wholeMorningReadIsAFixedSmallNumberOfQueries() {
        queries = 0; rowsRead = 0
        val soon = today + 3
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val partyIds = mutableListOf<String>()
        fun dues(kind: String, types: List<String>) {
            val d = rows(MorningSql.DUE_PARTIES, mapOf("businessId" to BIZ, "types" to types, "partyKind" to kind, "untilDay" to soon, "withPartPaid" to (if (kind == "CUSTOMER") 1 else 0), "limit" to LIMIT))
            val ids = d.map { it["partyId"] as String }
            partyIds += ids
            if (ids.isNotEmpty()) {
                rows(MorningSql.PARTIES_BY_ID, mapOf("businessId" to BIZ, "ids" to ids))
                rows(MorningSql.OPEN_BILLS_OF, mapOf("businessId" to BIZ, "partyIds" to ids, "types" to types))
            }
        }
        dues("CUSTOMER", customerTypes)
        dues("SUPPLIER", supplierTypes)
        rows(MorningSql.LOW_STOCK, mapOf("businessId" to BIZ, "limit" to LIMIT))
        rows(MorningSql.EXPIRING, mapOf("businessId" to BIZ, "untilDay" to today + 7, "limit" to LIMIT))
        rows(MorningSql.OPEN_DRAFTS, mapOf("businessId" to BIZ, "limit" to LIMIT))
        // totals
        for ((k, t) in listOf("CUSTOMER" to customerTypes, "SUPPLIER" to supplierTypes)) {
            rows(MorningSql.PARTY_SUMMARY, mapOf("businessId" to BIZ, "types" to t, "partyKind" to k, "today" to today, "untilDay" to soon))
        }
        rows(MorningSql.LOW_STOCK_COUNT, mapOf("businessId" to BIZ))
        rows(MorningSql.EXPIRING_COUNT, mapOf("businessId" to BIZ, "untilDay" to today + 7))
        rows(MorningSql.OPEN_DRAFTS_COUNT, mapOf("businessId" to BIZ))
        val ms = (System.nanoTime() - t0) / 1e6
        val bytes = (rt.totalMemory() - rt.freeMemory()) - before
        val boundedQueries = queries
        val boundedRows = rowsRead

        // The previous read (MorningWorkSources before this change): getCustomers / getSuppliers
        // (LegacyBridge: every party + every open bill), the open bills again for partial
        // payments, all stock + 5,000 products, expiring batches + one stock query per batch, all drafts.
        queries = 0; rowsRead = 0
        val t1 = System.nanoTime()
        for ((k, t) in listOf("CUSTOMER" to customerTypes, "SUPPLIER" to supplierTypes)) {
            rows(OPEN_DOCS_ALL, mapOf("businessId" to BIZ, "types" to t, "partyKind" to k))
            rows("SELECT * FROM parties WHERE businessId = :b AND kind = :k AND active = 1 ORDER BY nameKey", mapOf("b" to BIZ, "k" to k))
        }
        rows(OPEN_DOCS_ALL, mapOf("businessId" to BIZ, "types" to customerTypes, "partyKind" to "CUSTOMER"))
        rows(OPEN_DOCS_ALL, mapOf("businessId" to BIZ, "types" to supplierTypes, "partyKind" to "SUPPLIER"))
        rows("SELECT productId, SUM(qtyMilli) AS q FROM stock_movements WHERE businessId = :b AND active = 1 GROUP BY productId", mapOf("b" to BIZ))
        rows("SELECT * FROM products WHERE businessId = :b AND archived = 0 ORDER BY nameKey LIMIT 5000", mapOf("b" to BIZ))
        val expiring = rows("SELECT * FROM batches WHERE businessId = :b AND expiryDay IS NOT NULL AND expiryDay <= :u ORDER BY expiryDay LIMIT 200", mapOf("b" to BIZ, "u" to today + 7))
        for (b in expiring) {
            rows("SELECT COALESCE(SUM(qtyMilli), 0) FROM stock_movements WHERE businessId = :b AND productId = :p AND batchId = :i AND active = 1",
                mapOf("b" to BIZ, "p" to b["productId"] as String, "i" to b["id"] as String))
        }
        rows("SELECT * FROM drafts WHERE businessId = :b AND status = 'OPEN' ORDER BY createdAt DESC", mapOf("b" to BIZ))
        val oldMs = (System.nanoTime() - t1) / 1e6
        val oldRows = rowsRead

        println("MORNING READ (10,000 customers / 50,000 txns / 10,000 products):")
        println("  bounded: $boundedQueries queries, $boundedRows rows to the app, %.1f ms, ~%d KB heap".format(ms, bytes / 1024))
        println("  old full read: $queries queries, $oldRows rows to the app, %.1f ms".format(oldMs))
        assertTrue("fixed number of queries", boundedQueries <= 16)
        assertTrue("rows bounded", boundedRows < 400)
        assertTrue("old read loaded far more", oldRows > boundedRows * 20)
        assertTrue("bounded read took $ms ms", ms < 5_000)
        assertTrue(partyIds.isNotEmpty())
    }
}
