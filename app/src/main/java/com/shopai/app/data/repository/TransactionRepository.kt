package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.AddPaymentInput
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.DebitTransactionDetail

class TransactionRepository(private val api: ShopAiApi) {
    suspend fun createCredit(input: CreateCreditInput): CreditTransactionDetail =
        api.createCredit(input).data

    suspend fun createDebit(input: CreateDebitInput): DebitTransactionDetail =
        api.createDebit(input).data

    suspend fun addCreditPayment(transactionId: String, amount: Double, note: String? = null): CreditTransactionDetail =
        api.addCreditPayment(transactionId, AddPaymentInput(amount, note)).data

    suspend fun markCreditPaid(transactionId: String): CreditTransactionDetail =
        api.markCreditPaid(transactionId).data

    suspend fun addDebitPayment(transactionId: String, amount: Double, note: String? = null): DebitTransactionDetail =
        api.addDebitPayment(transactionId, AddPaymentInput(amount, note)).data

    suspend fun markDebitPaid(transactionId: String): DebitTransactionDetail =
        api.markDebitPaid(transactionId).data
}
