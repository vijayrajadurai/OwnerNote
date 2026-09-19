package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.ParseOcrRequest
import com.shopai.app.data.model.ParseVoiceRequest
import com.shopai.app.data.model.ParsedTransaction

class VoiceRepository(private val api: ShopAiApi) {
    suspend fun parseVoiceText(text: String): ParsedTransaction =
        api.parseVoice(ParseVoiceRequest(text)).data

    suspend fun parseOcrText(text: String): ParsedTransaction =
        api.parseOcr(ParseOcrRequest(text)).data
}
