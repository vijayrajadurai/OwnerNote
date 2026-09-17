package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput

class TransactionRepository(private val api: ShopAiApi) {
    suspend fun createCredit(input: CreateCreditInput) {
        api.createCredit(input)
    }

    suspend fun createDebit(input: CreateDebitInput) {
        api.createDebit(input)
    }
}
