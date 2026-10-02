package com.shopai.app.data.tts

data class TtsRequest(
    val text: String,
    val languageCode: String = "ta-IN",
)

data class TtsResponse(
    val audioBase64: String?,
    val format: String? = null,
)
