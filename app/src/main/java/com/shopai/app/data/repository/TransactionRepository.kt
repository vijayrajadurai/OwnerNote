package com.shopai.app.data.repository

import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.LegacyBridge
import com.shopai.app.books.model.TxnSource
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.AddPaymentInput
import com.shopai.app.data.model.CreateCreditInput
import com.shopai.app.data.model.CreateDebitInput
import com.shopai.app.data.model.CreditTransactionDetail
import com.shopai.app.data.model.DebitTransactionDetail

/**
 * Credit / debit entries and their payments. After the one-time import they
 * are posted through the books engine (sale / purchase / payment) — the
 * backend ledger is no longer written. [source] marks voice / scanned entries.
 */
class TransactionRepository(private val api: ShopAiApi, private val books: BooksModule? = null) {
    private suspend fun bridge(): LegacyBridge? = books?.session()?.let(::LegacyBridge)

    suspend fun createCredit(input: CreateCreditInput, source: TxnSource = TxnSource.MANUAL): CreditTransactionDetail =
        bridge()?.createCredit(input, source) ?: api.createCredit(input).data

    suspend fun createDebit(input: CreateDebitInput, source: TxnSource = TxnSource.MANUAL): DebitTransactionDetail =
        bridge()?.createDebit(input, source) ?: api.createDebit(input).data

    suspend fun addCreditPayment(transactionId: String, amount: Double, note: String? = null): CreditTransactionDetail =
        bridge()?.addCreditPayment(transactionId, amount, note) ?: api.addCreditPayment(transactionId, AddPaymentInput(amount, note)).data

    suspend fun markCreditPaid(transactionId: String): CreditTransactionDetail =
        bridge()?.markCreditPaid(transactionId) ?: api.markCreditPaid(transactionId).data

    suspend fun addDebitPayment(transactionId: String, amount: Double, note: String? = null): DebitTransactionDetail =
        bridge()?.addDebitPayment(transactionId, amount, note) ?: api.addDebitPayment(transactionId, AddPaymentInput(amount, note)).data

    suspend fun markDebitPaid(transactionId: String): DebitTransactionDetail =
        bridge()?.markDebitPaid(transactionId) ?: api.markDebitPaid(transactionId).data
}
