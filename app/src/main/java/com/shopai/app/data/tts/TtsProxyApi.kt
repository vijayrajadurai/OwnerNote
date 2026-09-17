package com.shopai.app.data.tts

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface TtsProxyApi {
    @POST("api/tts")
    suspend fun synthesize(
        @Body body: TtsRequest,
        @Header("x-tts-proxy-key") proxyKey: String?,
    ): TtsResponse
}
