package com.shopai.app.data

import android.content.Context
import com.shopai.app.BuildConfig
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.network.ApiErrorHandler
import com.shopai.app.data.auth.FirebasePhoneAuthClient
import com.shopai.app.data.local.TokenStore
import com.shopai.app.data.local.room.ShopAiLocalDatabase
import com.shopai.app.data.local.UserPreferencesStore
import com.shopai.app.data.repository.DailyCashRepository
import com.shopai.app.data.repository.AuthRepository
import com.shopai.app.data.repository.PreferencesRepository
import com.shopai.app.data.repository.BusinessRepository
import com.shopai.app.data.repository.DiscoverRepository
import com.shopai.app.data.repository.FundingRepository
import com.shopai.app.data.repository.InsightsRepository
import com.shopai.app.data.repository.PartyRepository
import com.shopai.app.data.repository.ReminderRepository
import com.shopai.app.data.repository.TransactionRepository
import com.shopai.app.data.repository.VoiceRepository
import com.shopai.app.data.tts.NaturalTtsSpeaker
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val userPreferencesStore = UserPreferencesStore(appContext)
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

    val preferencesRepository = PreferencesRepository(userPreferencesStore)
    val authRepository = AuthRepository(api, tokenStore, apiErrorHandler, FirebasePhoneAuthClient())
    val businessRepository = BusinessRepository(api)
    val insightsRepository = InsightsRepository(api)
    val discoverRepository = DiscoverRepository(api)
    val partyRepository = PartyRepository(api)
    val transactionRepository = TransactionRepository(api)
    val voiceRepository = VoiceRepository(api)
    val reminderRepository = ReminderRepository(api)
    val fundingRepository = FundingRepository(api)
    val naturalTtsSpeaker = NaturalTtsSpeaker(appContext)
    private val localDatabase = ShopAiLocalDatabase.get(appContext)
    val dailyCashRepository = DailyCashRepository(localDatabase.dailyCashDao(), api)

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
