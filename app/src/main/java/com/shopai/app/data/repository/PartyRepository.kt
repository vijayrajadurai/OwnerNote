package com.shopai.app.data.repository

import com.shopai.app.books.integration.BooksModule
import com.shopai.app.books.integration.LegacyBridge
import com.shopai.app.books.model.PartyKind
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.CreatePartyInput
import com.shopai.app.data.model.CustomerDetail
import com.shopai.app.data.model.PartyRecord
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.SupplierDetail

/** Customers and suppliers: the backend until the one-time import, the on-device books after it. */
class PartyRepository(private val api: ShopAiApi, private val books: BooksModule? = null) {
    private suspend fun bridge(): LegacyBridge? = books?.session()?.let(::LegacyBridge)

    suspend fun getCustomers(): List<PartySummary> = bridge()?.customers() ?: api.getCustomers().data

    suspend fun getSuppliers(): List<PartySummary> = bridge()?.suppliers() ?: api.getSuppliers().data

    suspend fun getCustomer(id: String): CustomerDetail = bridge()?.customer(id) ?: api.getCustomer(id).data

    suspend fun getSupplier(id: String): SupplierDetail = bridge()?.supplier(id) ?: api.getSupplier(id).data

    suspend fun createCustomer(input: CreatePartyInput): PartyRecord =
        bridge()?.createParty(PartyKind.CUSTOMER, input) ?: api.createCustomer(input).data

    suspend fun createSupplier(input: CreatePartyInput): PartyRecord =
        bridge()?.createParty(PartyKind.SUPPLIER, input) ?: api.createSupplier(input).data
}
