package com.shopai.app.data

import android.content.Context
import com.shopai.app.BuildConfig
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.network.ApiErrorHandler
import com.shopai.app.data.auth.FirebasePhoneAuthClient
import com.shopai.app.data.local.ProfilePhotoStore
import com.shopai.app.data.local.TokenStore
import com.shopai.app.data.local.room.ShopAiLocalDatabase
import com.shopai.app.data.local.UserPreferencesStore
import com.shopai.app.data.repository.DailyCashRepository
import com.shopai.app.data.repository.AuthRepository
import com.shopai.app.data.repository.PreferencesRepository
import com.shopai.app.data.repository.BusinessRepository
import com.shopai.app.data.repository.DiscoverRepository
import com.shopai.app.data.repository.FundingRepository
import com.shopai.app.data.repository.GroupBuyingRepository
import com.shopai.app.data.repository.InsightsRepository
import com.shopai.app.data.repository.InventoryRepository
import com.shopai.app.data.repository.OffersRepository
import com.shopai.app.data.repository.PartyRepository
import com.shopai.app.data.repository.PushTokenRepository
import com.shopai.app.data.repository.CapturedDocumentRepository
import com.shopai.app.data.repository.HandwrittenNotesRepository
import com.shopai.app.data.repository.ReminderRepository
import com.shopai.app.notifications.ReminderAlarms
import com.shopai.app.data.repository.TransactionRepository
import com.shopai.app.data.repository.VoiceCheckinRepository
import com.shopai.app.data.repository.VoiceRepository
import com.shopai.app.data.tts.NaturalTtsSpeaker
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tokenStore = TokenStore(appContext)
    private val userPreferencesStore = UserPreferencesStore(appContext)
    val profilePhotoStore = ProfilePhotoStore(appContext)
    val apiErrorHandler = ApiErrorHandler(appContext)

    private val authInterceptor = Interceptor { chain ->
        val token = runCatching {
            // Blocking read is acceptable here for OkHttp interceptor thread.
            kotlinx.coroutines.runBlocking { tokenStore.getToken() }
        }.getOrNull()

        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    private val okHttp = OkHttpClient.Builder()
        // Render free tier can take 30–60s to wake from sleep.
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                    },
                )
            }
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(ensureTrailingSlash(BuildConfig.API_BASE_URL))
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: ShopAiApi = retrofit.create(ShopAiApi::class.java)

    /** OwnerNote Books: the on-device accounting engine (source of truth after the one-time import). */
    val books = com.shopai.app.books.integration.BooksModule(appContext)

    val preferencesRepository = PreferencesRepository(userPreferencesStore)
    val pushTokenRepository = PushTokenRepository(api, tokenStore)
    val authRepository = AuthRepository(api, tokenStore, apiErrorHandler, FirebasePhoneAuthClient(), pushTokenRepository, appScope, onSignedOut = {
        books.signOut()
        morningWork.reset()
        morningSources.clear()
    })
    val businessRepository = BusinessRepository(api)
    val insightsRepository = InsightsRepository(api, books)
    val discoverRepository = DiscoverRepository(api)
    val partyRepository = PartyRepository(api, books)
    val transactionRepository = TransactionRepository(api, books)
    val voiceRepository = VoiceRepository(api)
    val reminderAlarms = ReminderAlarms(appContext)
    val reminderRepository = ReminderRepository(api, reminderAlarms)
    val fundingRepository = FundingRepository(api)
    val groupBuyingRepository = GroupBuyingRepository(api)
    val inventoryRepository = InventoryRepository(api, books = books)
    val offersRepository = OffersRepository(api, businessRepository)
    val naturalTtsSpeaker = NaturalTtsSpeaker(appContext)

    /** One-time move of the backend customers / suppliers / products into the books. */
    val booksImporter by lazy {
        com.shopai.app.books.integration.BooksImporter(books, com.shopai.app.books.integration.ApiLegacyLedgerSource(api, businessRepository))
    }

    /** KAI — the one Business Brain every screen talks to. */
    val kaiBrain = com.shopai.app.brain.KaiBrain(partyRepository, reminderRepository, insightsRepository, voiceRepository, naturalTtsSpeaker)
    private val localDatabase = ShopAiLocalDatabase.get(appContext)
    val dailyCashRepository = DailyCashRepository(localDatabase.dailyCashDao(), api)
    val voiceCheckinRepository = VoiceCheckinRepository(appContext, localDatabase.voiceCheckinDao())
    val capturedDocumentRepository = CapturedDocumentRepository(localDatabase.capturedDocumentDao())
    val handwrittenNotesRepository = HandwrittenNotesRepository(appContext, localDatabase.handwrittenNotesDao(), transactionRepository)

    /** Kai Chat's books: the existing ledger, party details and Daily Cash Note (no AI service). */
    val kaiBooks by lazy { com.shopai.app.brain.chat.RepositoryKaiBooks(kaiBrain, partyRepository, dailyCashRepository) }

    /** Kai's personal / task reminders (phone alarms) and his action log. */
    val kaiReminders = com.shopai.app.notifications.KaiReminderEngine(appContext)
    val kaiActionLog by lazy { com.shopai.app.data.kai.KaiActionLog(appContext) }

    /** Kai's tools: the books engine (reads, drafts, confirmed posts), reminders, the action log. */
    val kaiTools by lazy { com.shopai.app.data.kai.AppKaiTools(appContext, books, transactionRepository, kaiReminders, kaiActionLog) }

    /** Kai — Do My Morning Work: one engine for voice and text, over the existing data (read only). */
    val morningWork by lazy { com.shopai.app.brain.morning.MorningWorkEngine(com.shopai.app.data.morning.MorningTaskFileStore(appContext)) }

    /** Morning Work's read-only view of the books, parties, stock and reminders. */
    val morningSources by lazy {
        com.shopai.app.data.morning.MorningWorkSources(
            appContext, books, partyRepository, reminderRepository, reminderAlarms, inventoryRepository, businessRepository,
        )
    }

    /** Kai Chat conversation for this app session — KAI's agent over his Business Brain and tools (no paid AI). */
    val kaiChat by lazy {
        com.shopai.app.ui.kaichat.KaiChatSession(
            com.shopai.app.brain.chat.KaiAgent(com.shopai.app.brain.chat.KaiBusinessBrain(kaiBooks), kaiBooks, kaiTools),
            morningWork = { text ->
                // Typed in Kai Chat → Morning Work answers in text (the same engine as voice).
                morningSources.snapshot()?.let { snap ->
                    morningWork.role = morningSources.role()
                    val first = !morningWork.state.started
                    morningWork.prepare(
                        snap,
                        mode = if (first) com.shopai.app.brain.morning.ResponseMode.TEXT else null,
                        lang = com.shopai.app.brain.morning.MorningCommands.language(text),
                    ).line.display
                }
            },
        )
    }

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
