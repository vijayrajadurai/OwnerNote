package com.shopai.app.live

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shopai.app.books.data.BooksDatabase
import com.shopai.app.books.engine.BusinessSetup
import com.shopai.app.books.engine.MasterResult
import com.shopai.app.books.engine.OpeningStockInput
import com.shopai.app.books.engine.PartyInput
import com.shopai.app.books.engine.PartyOpeningInput
import com.shopai.app.books.engine.PostMeta
import com.shopai.app.books.engine.PostResult
import com.shopai.app.books.engine.ProductInput
import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.BooksSession
import com.shopai.app.books.model.BusinessType
import com.shopai.app.books.model.Money
import com.shopai.app.books.model.PartyKind
import com.shopai.app.books.model.Qty
import com.shopai.app.brain.BusinessSnapshot
import com.shopai.app.brain.KaiLang
import com.shopai.app.brain.PartyFacts
import com.shopai.app.brain.PartyHistory
import com.shopai.app.brain.chat.KaiAction
import com.shopai.app.brain.chat.KaiAgent
import com.shopai.app.brain.chat.KaiBooks
import com.shopai.app.brain.chat.KaiBusinessBrain
import com.shopai.app.brain.chat.KaiMorningAccess
import com.shopai.app.brain.chat.KaiTurn
import com.shopai.app.brain.memory.KaiPrivateMemory
import com.shopai.app.brain.morning.MorningFire
import com.shopai.app.brain.morning.MorningNotificationSetting
import com.shopai.app.brain.morning.MorningOwner
import com.shopai.app.brain.morning.MorningRoutines
import com.shopai.app.brain.morning.MorningScheduler
import com.shopai.app.brain.morning.MorningWorkEngine
import com.shopai.app.brain.tools.ActionStatus
import com.shopai.app.brain.tools.KaiIntents
import com.shopai.app.brain.tools.ScheduleResult
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.kai.AppKaiMemoryAccess
import com.shopai.app.data.kai.AppKaiTools
import com.shopai.app.data.kai.KaiActionLog
import com.shopai.app.data.kai.KaiMemoryFileStore
import com.shopai.app.data.morning.MorningTaskFileStore
import com.shopai.app.data.morning.MorningWorkSources
import com.shopai.app.data.repository.BusinessRepository
import com.shopai.app.data.repository.InventoryRepository
import com.shopai.app.data.repository.PartyRepository
import com.shopai.app.data.repository.ReminderRepository
import com.shopai.app.data.repository.TransactionRepository
import com.shopai.app.notifications.KaiReminderEngine
import com.shopai.app.notifications.MorningWorkAlarms
import com.shopai.app.notifications.PrefsMorningScheduleStore
import com.shopai.app.notifications.ReminderAlarms
import com.shopai.app.receiver.MorningWorkReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.time.LocalDate

/**
 * LIVE test on the emulator / phone: the real Kai brain over the real Android
 * pieces — books Room database (SQLite on the device), inventory engine,
 * AppKaiTools, Kai memory files, action log, reminder alarms, Morning Work
 * sources / scheduler / notification. Covers the 3 Oct 2026 work: Kai stock
 * fixes, personal learning, unit conversion, Morning Work phase 2.
 *
 * Isolated from the app's own data: an in-memory books database, and every
 * file / preference under a "kai_live_test" name. (The phone's one Morning Work
 * alarm is shared: the app re-arms it when it opens.)
 *
 * A failing check says "BUG: …" with what was expected.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class KaiLiveDeviceTest {

    /** Files and preferences of this test only — never the signed-in owner's real ones. */
    private class LiveContext(base: Context) : ContextWrapper(base) {
        val root = File(base.filesDir, "kai_live_test")
        val prefNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root.apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            prefNames += name
            return super.getSharedPreferences("kai_live_test_$name", mode)
        }
    }

    /** Kai Chat's read-only books are not part of these checks. */
    private class NoBooks : KaiBooks {
        override suspend fun snapshot() = BusinessSnapshot(customers = emptyList(), suppliers = emptyList())
        override suspend fun history(party: PartyFacts): PartyHistory? = null
        override suspend fun cashBook(from: LocalDate, to: LocalDate) = null
    }

    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var ctx: LiveContext
    private lateinit var db: BooksDatabase
    private lateinit var books: BooksModule
    private lateinit var s: BooksSession
    private lateinit var api: ShopAiApi
    private lateinit var inventory: InventoryRepository
    private lateinit var parties: PartyRepository
    private lateinit var actionLog: KaiActionLog
    private lateinit var reminders: KaiReminderEngine
    private lateinit var tools: AppKaiTools
    private val createdReminders = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        ctx = LiveContext(target)
        ctx.root.deleteRecursively()
        // Leftovers of an earlier run that stopped half way.
        for (name in listOf("owner_books", "kai_action_log", "morning_work")) ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.inMemoryDatabaseBuilder(target, BooksDatabase::class.java).build()
        books = BooksModule(ctx) { db }
        s = books.openFor(BIZ, OWNER_A)
        s.masters.setupBusiness(BusinessSetup("Live Test Shop", BusinessType.RETAILER, ownerName = "Owner", stateCode = "33")).ok()
        s.masters.markImported("kai live test")
        assertNotNull("SETUP: books session open aagala", books.session())

        // No network in these checks: the books are the source of truth after the import.
        api = Retrofit.Builder().baseUrl("http://127.0.0.1:9/").addConverterFactory(GsonConverterFactory.create()).build()
            .create(ShopAiApi::class.java)
        inventory = InventoryRepository(api, books = books)
        parties = PartyRepository(api, books)
        actionLog = KaiActionLog(ctx) { OWNER_A }
        reminders = KaiReminderEngine(ctx)
        tools = AppKaiTools(ctx, books, TransactionRepository(api, books), reminders, actionLog, inventory)
    }

    @After
    fun tearDown() {
        createdReminders.forEach { runCatching { reminders.cancel(it) } }
        runCatching { db.close() }
        ctx.prefNames.toList().forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        ctx.root.deleteRecursively()
    }

    // ------------------------------------------------------------ helpers

    private fun <T> MasterResult<T>.ok(): T = when (this) {
        is MasterResult.Ok -> value
        is MasterResult.Rejected -> throw AssertionError("SETUP: books rejected: $errors")
    }

    private fun PostResult.ok() {
        if (this is PostResult.Rejected) throw AssertionError("SETUP: books rejected: $errors")
    }

    /** A product in the books (PCS), optionally with 1 BOX = [box] PCS, opening [stock] and a reorder level. */
    private suspend fun product(name: String, stock: Long = 0, box: Long? = null, reorder: Long? = null): String {
        val p = s.masters.createProduct(
            ProductInput(
                name = name,
                primaryUnit = "PCS",
                secondaryUnit = box?.let { "BOX" },
                conversionMilli = box?.let { Qty.of(it) },
                purchasePricePaise = Money.ofRupees(10),
                reorderLevelMilli = reorder?.let { Qty.of(it) },
            ),
        ).ok()
        if (stock > 0) s.engine.postOpeningStock(OpeningStockInput(p.id, LocalDate.now(), Qty.of(stock), meta = PostMeta("os-$name"))).ok()
        return p.id
    }

    private suspend fun stockOf(productId: String): Long = s.ledger.stock(productId)

    private fun memoryAccess(): AppKaiMemoryAccess =
        AppKaiMemoryAccess(KaiPrivateMemory(KaiMemoryFileStore(ctx)), books, { BIZ }, parties, inventory)

    private fun kai(memory: AppKaiMemoryAccess? = memoryAccess(), morning: KaiMorningAccess? = null) =
        KaiAgent(KaiBusinessBrain(NoBooks()), NoBooks(), tools, memory = memory, morning = morning)

    private fun KaiTurn.actions(): List<KaiAction> = card?.buttons.orEmpty().map { it.action }
    private fun KaiTurn.labels(): List<String> = card?.buttons.orEmpty().map { it.label }
    private fun KaiTurn.button(label: String): KaiAction = card?.buttons.orEmpty().firstOrNull { it.label == label }?.action
        ?: throw AssertionError("BUG: '$label' button illa. Buttons: ${labels()} · Reply: ${reply.text}")

    private suspend fun KaiAgent.confirmStock(t: KaiTurn): KaiTurn {
        val confirm = t.actions().filterIsInstance<KaiAction.ConfirmStock>().firstOrNull()
            ?: throw AssertionError("BUG: Confirm Stock button illa. Reply: ${t.reply.text} · Buttons: ${t.labels()}")
        return act(confirm, KaiLang.TANGLISH) ?: throw AssertionError("BUG: Confirm-ku Kai reply pannala")
    }

    private fun says(t: KaiTurn, part: String, bug: String) = assertTrue("BUG: $bug\nExpected: \"$part\"\nKai said: ${t.reply.text}", t.reply.text.contains(part))

    // ------------------------------------------------------------ unit conversion (stock in / out)

    @Test
    fun t01_stockIn_5box_with_saved_box12_adds_60_pieces() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val k = kai()
        val d = k.ask("Colgate 5 box add pannu")
        says(d, "5 boxes = 60 pieces", "5 box draft 60 pieces-ah kaattanum")
        assertEquals("BUG: Confirm munnadi stock maaraadhu", 0L, stockOf(colgate))
        val done = k.confirmStock(d)
        says(done, "Inventory +60 pieces", "Confirm reply-la +60 pieces varanum")
        assertEquals("BUG: 5 box = 60 pieces dhaan inventory-la sera vendum (5 illa)", Qty.of(60), stockOf(colgate))
    }

    @Test
    fun t02_stockIn_pieces_needs_no_conversion() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val k = kai()
        val d = k.ask("Colgate 5 pieces add pannu")
        k.confirmStock(d)
        assertEquals("BUG: 5 pieces = 5 pieces dhaan", Qty.of(5), stockOf(colgate))
    }

    @Test
    fun t03_unknown_box_is_asked_then_saved_to_product() = runBlocking {
        val pepsodent = product("Pepsodent")
        val k = kai()
        val ask = k.ask("Pepsodent 5 box add pannu")
        assertEquals("BUG: box size theriyaama guess pannakoodaadhu — kekkanum", "Owner, 1 box-la evlo pieces irukku?", ask.reply.text)
        assertFalse("BUG: kekkum podhe Confirm button varakoodaadhu", ask.actions().any { it is KaiAction.ConfirmStock })
        assertEquals("BUG: kekkum podhe stock maaraadhu", 0L, stockOf(pepsodent))

        val d = k.ask("12")
        says(d, "5 boxes = 60 pieces", "'12' sonna appuram 60 pieces draft varanum")
        assertTrue("BUG: [Save: 1 box = 12 pieces] button varanum · ${d.labels()}", d.labels().contains("Save: 1 box = 12 pieces"))
        k.confirmStock(d)
        assertEquals("BUG: confirm-ku appuram 60 pieces", Qty.of(60), stockOf(pepsodent))

        val save = k.act(d.button("Save: 1 box = 12 pieces"), KaiLang.TANGLISH)!!
        says(save, "1 box = 12 pieces save panniten", "Save reply")
        val row = s.dao.product(pepsodent)!!
        assertEquals("BUG: books product-la second unit BOX save aaganum", "BOX", row.secondaryUnit)
        assertEquals("BUG: books product-la 1 box = 12 pieces (12000 milli) save aaganum", Qty.of(12), row.conversionMilli)

        // A new chat reads the saved conversion from the books — no question again.
        says(kai().ask("Pepsodent 1 box add pannu"), "1 box = 12 pieces", "save panna conversion adutha chat-la use aaganum (kekka koodaadhu)")
    }

    @Test
    fun t04_save_never_overwrites_a_different_second_unit() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val k = kai()
        k.ask("Colgate 2 packet add pannu")
        val d = k.ask("4")
        val res = k.act(d.button("Save: 1 packet = 4 pieces"), KaiLang.TANGLISH)!!
        says(res, "indha conversation-ku mattum", "box already irukkura product-la packet save pannakoodaadhu — indha chat-ku mattum nu sollanum")
        val row = s.dao.product(colgate)!!
        assertEquals("BUG: Colgate box unit maara koodaadhu", "BOX", row.secondaryUnit)
        assertEquals("BUG: Colgate 1 box = 12 maara koodaadhu", Qty.of(12), row.conversionMilli)
    }

    @Test
    fun t05_stockOut_in_boxes_and_short_stock_blocked() = runBlocking {
        val colgate = product("Colgate", stock = 24, box = 12)
        val k = kai()
        val d = k.ask("2 box Colgate pochu")
        says(d, "24 pieces", "2 box stock-out = 24 pieces")
        k.confirmStock(d)
        assertEquals("BUG: 24 - 24 = 0 aaganum", 0L, stockOf(colgate))
        val tooMany = k.ask("1 box Colgate pochu")
        assertFalse("BUG: stock illaama stock-out Confirm enable-ah irukka koodaadhu", tooMany.card?.buttons?.firstOrNull()?.enabled ?: false)
        assertEquals("BUG: short stock-la stock maaraadhu", 0L, stockOf(colgate))
    }

    @Test
    fun t06_mixed_units_1box_3pieces_is_15() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val k = kai()
        val d = k.ask("1 box 3 pieces Colgate add pannu")
        says(d, "1 box + 3 pieces = 15 pieces", "mixed units")
        k.confirmStock(d)
        assertEquals("BUG: 1 box + 3 pieces = 15", Qty.of(15), stockOf(colgate))
    }

    @Test
    fun t07_edit_recalculates_and_cancel_changes_nothing() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val k = kai()
        val d = k.ask("Colgate 5 box add pannu")
        val key = d.actions().filterIsInstance<KaiAction.EditStock>().firstOrNull()?.key
            ?: throw AssertionError("BUG: Edit button illa · ${d.labels()}")
        val edited = k.reviseStock(key, java.math.BigDecimal("4"), "BOX", KaiLang.TANGLISH)
            ?: throw AssertionError("BUG: Edit-ku appuram draft varala")
        says(edited, "4 boxes = 48 pieces", "Edit 4 box = 48 pieces recalculate aaganum")
        k.act(edited.actions().filterIsInstance<KaiAction.CancelStock>().first(), KaiLang.TANGLISH)
        assertEquals("BUG: Cancel pannina stock maaraadhu", 0L, stockOf(colgate))
    }

    // ------------------------------------------------------------ personal learning (owner's words)

    @Test
    fun t08_teach_potti_confirm_first_then_survives_app_restart() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val access = memoryAccess()
        val k = kai(access)
        val teach = k.ask("Kai, enga kadaiyila 'potti' na 1 box.")
        says(teach, "Save pannava?", "teaching-ku munnadi Save pannava nu kekkanum")
        assertNull("BUG: owner 'aama' sollaama save aaga koodaadhu", access.current()!!.find("potti"))
        k.act(teach.button("Save"), KaiLang.TANGLISH)
        assertEquals("BUG: 'potti' = box save aaganum", "box", access.current()!!.find("potti")?.meaningValue)

        // New memory objects read the phone's file again — like opening the app tomorrow.
        val k2 = kai(memoryAccess())
        val d = k2.ask("Colgate 2 potti vandhudhu")
        says(d, "2 boxes = 24 pieces", "app restart-ku appuram 'potti' nyabagam irukkanum; 2 potti = 24 pieces")
        k2.confirmStock(d)
        assertEquals("BUG: 2 potti = 24 pieces (2 illa)", Qty.of(24), stockOf(colgate))
    }

    @Test
    fun t09_unknown_word_after_quantity_is_asked_not_guessed() = runBlocking {
        val colgate = product("Colgate", box = 12)
        val k = kai()
        val ask = k.ask("Colgate 2 petti vandhiruku")
        says(ask, "`petti` na box-ah?", "theriyaadha word-ku kekkanum")
        assertFalse("BUG: theriyaadha word-la Confirm varakoodaadhu", ask.actions().any { it is KaiAction.ConfirmStock })
        k.ask("Box")
        val done = k.ask("Aama")
        says(done, "2 boxes = 24 pieces", "Box + Aama sonna appuram 24 pieces draft")
        assertEquals("BUG: Confirm munnadi stock maaraadhu", 0L, stockOf(colgate))
    }

    @Test
    fun t10_owner_B_never_sees_owner_A_words() = runBlocking {
        val kA = kai()
        kA.ask("'potti' na box")
        kA.ask("aama")
        says(kA.ask("potti na enna?"), "box", "Owner A-ku potti theriyanum")

        // Same shop, another login.
        books.openFor(BIZ, OWNER_B)
        val accessB = memoryAccess()
        val q = kai(accessB).ask("potti na enna?")
        assertFalse("BUG: Owner B-ku Owner A-voda word theriya koodaadhu: ${q.reply.text}", q.reply.text.contains("box"))
        assertNull("BUG: Owner B memory-la potti irukka koodaadhu", accessB.current()?.find("potti"))
        // Files are per owner.
        val files = File(ctx.root, "kai_memory").listFiles().orEmpty().map { it.name }
        assertTrue("BUG: Owner A file irukkanum · $files", files.any { it.contains(OWNER_A) })
    }

    @Test
    fun t11_change_and_forget_through_chat() = runBlocking {
        val access = memoryAccess()
        val k = kai(access)
        k.ask("'potti' na box")
        k.ask("aama")
        k.ask("potti meaning change pannu")
        k.ask("carton")
        k.ask("aama")
        assertEquals("BUG: potti meaning carton-ah maaranum", "carton", access.current()!!.find("potti")?.meaningValue)
        val forget = k.ask("potti meaning forget")
        says(forget, "remove pannava?", "forget-ku munnadi kekkanum")
        assertNotNull("BUG: kekkum podhe azhiya koodaadhu", access.current()!!.find("potti"))
        k.act(forget.button("Forget"), KaiLang.TANGLISH)
        assertNull("BUG: Forget pannina appuram potti irukka koodaadhu", access.current()!!.find("potti"))
        assertNull("BUG: restart-ku appuram-um potti varakoodaadhu", memoryAccess().current()!!.find("potti"))
    }

    @Test
    fun t12_action_log_records_learning_without_conversation_text() = runBlocking {
        val k = kai()
        k.ask("'potti' na box")
        k.ask("aama")
        val log = actionLog.records().filter { it.intent == KaiIntents.LEARN_PERSONAL_TERM }
        assertEquals("BUG: LEARN_PERSONAL_TERM log onnu dhaan irukkanum · $log", 1, log.size)
        assertEquals("potti = box", log.single().result)
        assertEquals(ActionStatus.CONFIRMED, log.single().status)
        assertNull("BUG: log-la owner pesuna text save aaga koodaadhu", log.single().input)
        assertEquals("BUG: log-la owner id varanum", OWNER_A, log.single().ownerId)
    }

    // ------------------------------------------------------------ Morning Work (phase 2)

    private suspend fun morningSetup(): Pair<AppKaiMemoryAccess, KaiMorningAccess> {
        val kumar = s.masters.createParty(PartyInput(PartyKind.CUSTOMER, "Kumar", mobile = "9000000001", stateCode = "33")).ok()
        s.engine.postPartyOpening(
            PartyOpeningInput(kumar.id, Money.ofRupees(8000), date = LocalDate.now().minusDays(10), dueDate = LocalDate.now().minusDays(2), meta = PostMeta("po-kumar")),
        ).ok()
        product("Lux Soap", stock = 2, reorder = 10)
        val access = memoryAccess()
        val sources = MorningWorkSources(
            ctx, books, parties, ReminderRepository(api, ReminderAlarms(ctx)), ReminderAlarms(ctx), inventory, BusinessRepository(api),
            routine = { access.current()?.let { MorningRoutines.load(it)?.orderedSections } },
        )
        val engine = MorningWorkEngine(MorningTaskFileStore(ctx))
        return access to KaiMorningAccess { lang -> sources.snapshot()?.let { engine.generate(it, lang) } }
    }

    @Test
    fun t13_morning_brief_reads_real_dues_and_low_stock() = runBlocking {
        val (access, morning) = morningSetup()
        val t = kai(access, morning).ask("morning brief kudu")
        says(t, "Kumar", "Morning brief-la overdue customer Kumar varanum")
        says(t, "8,000", "Morning brief-la Kumar ₹8,000 varanum")
        says(t, "Lux Soap", "Morning brief-la low stock Lux Soap varanum")
        assertEquals("BUG: Morning brief edhuvum stock maatha koodaadhu", Qty.of(2), stockOf(s.dao.products(BIZ, false, 10, 0).first { it.name == "Lux Soap" }.id))
    }

    @Test
    fun t14_morning_routine_saved_and_used_by_brief() = runBlocking {
        val (access, morning) = morningSetup()
        val k = kai(access, morning)
        k.ask("Stock first, collection next")
        val done = k.ask("aama")
        says(done, "Done Owner", "Routine save aaganum")
        val text = k.ask("morning brief kudu").reply.text
        val stockAt = text.indexOf("📦")
        val collectAt = text.indexOf("🔴")
        assertTrue("BUG: brief-la stock & collections rendum varanum: $text", stockAt >= 0 && collectAt >= 0)
        assertTrue("BUG: routine padi Stock first varanum, appuram Collections: $text", stockAt < collectAt)
    }

    @Test
    fun t15_morning_notification_schedule_per_owner() {
        val alarms = MorningWorkAlarms(ctx)
        val scheduler = MorningScheduler(PrefsMorningScheduleStore(ctx), alarms)
        val a = MorningOwner(BIZ, OWNER_A)
        val b = MorningOwner(BIZ, OWNER_B)
        try {
            assertFalse("BUG: Morning notification default-ah OFF-ah irukkanum", scheduler.setting(a).enabled)
            val r = scheduler.update(a, MorningNotificationSetting(enabled = true, hour = 7, minute = 30))
            assertNotNull("BUG: ON pannina alarm set aaganum", r)
            assertNotEquals("BUG: alarm set FAILED", ScheduleResult.FAILED, r)
            val pending = PendingIntent.getBroadcast(
                ctx, MorningScheduler.ALARM_ID,
                Intent(ctx, MorningWorkReceiver::class.java).setAction(MorningWorkAlarms.ACTION_FIRE),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            assertNotNull("BUG: phone-la morning alarm register aagala", pending)
            assertFalse("BUG: Owner A setting Owner B-ku poga koodaadhu", scheduler.setting(b).enabled)
            assertTrue("BUG: first ring-la notification kaattanum", scheduler.onFire(a, a) is MorningFire.Show)
            assertTrue("BUG: same day rendaavadhu thadavai kaatta koodaadhu", scheduler.onFire(a, a) is MorningFire.Skip)
            assertTrue("BUG: vera owner login-la Owner A notification kaatta koodaadhu", scheduler.onFire(a, b) is MorningFire.Skip)
            alarms.show("Good morning Owner ☀️", "Kai live test — Morning Work notification")
        } finally {
            scheduler.restore(null)
        }
    }

    // ------------------------------------------------------------ yesterday's Kai fixes

    @Test
    fun t16_spoken_relative_reminder_sets_a_real_alarm() = runBlocking {
        val before = System.currentTimeMillis()
        val k = kai()
        val ask = k.ask("2 minutes la Ruthran-ku call pannanum reminder pannu")
        says(ask, "set pannalama?", "reminder set panna munnadi Kai confirm kekkanum")
        assertTrue("BUG: Confirm munnadi reminder save aaga koodaadhu", reminders.open().none { (it.person ?: "").contains("Ruthran", true) })
        val t = k.act(ask.button("Confirm"), KaiLang.TANGLISH) ?: throw AssertionError("BUG: Confirm-ku reply illa")
        says(t, "Ruthran", "reminder reply-la Ruthran varanum")
        val r = reminders.open().firstOrNull { it.task.contains("Ruthran", true) || it.title.contains("Ruthran", true) || (it.person ?: "").contains("Ruthran", true) }
            ?: throw AssertionError("BUG: reminder save aagala · open=${reminders.open().map { it.title }}")
        createdReminders += r.id
        val inSec = (r.triggerAt - before) / 1000
        assertTrue("BUG: 2 minutes reminder ~120 sec-la ring aaganum, ippo $inSec sec", inSec in 100..150)
    }

    @Test
    fun t17_bill_scan_opens_camera_and_small_talk_writes_nothing() = runBlocking {
        val k = kai()
        val bill = k.ask("bill scan pannu")
        assertEquals("BUG: 'bill scan pannu' camera open pannanum", KaiAction.OpenScanner, bill.direct)
        val colgate = product("Colgate", stock = 5)
        val hi = k.ask("hi Kai, epdi irukeenga?")
        assertTrue("BUG: small talk-ku reply varanum", hi.reply.text.isNotBlank())
        assertFalse("BUG: small talk-la Confirm button varakoodaadhu", hi.actions().any { it is KaiAction.ConfirmStock || it is KaiAction.ConfirmPlan })
        assertEquals("BUG: small talk stock maatha koodaadhu", Qty.of(5), stockOf(colgate))
    }

    @Test
    fun t18_time_words_are_not_stock_changes() = runBlocking {
        val colgate = product("Colgate", stock = 5, box = 12)
        val k = kai()
        val t = k.ask("2 mani pochu")
        assertFalse("BUG: '2 mani pochu' stock-out illa: ${t.reply.text}", t.actions().any { it is KaiAction.ConfirmStock })
        assertEquals("BUG: neram sonna stock maara koodaadhu", Qty.of(5), stockOf(colgate))
    }

    private companion object {
        const val BIZ = "kai-live-biz"
        const val OWNER_A = "kai-live-owner-a"
        const val OWNER_B = "kai-live-owner-b"
    }
}
