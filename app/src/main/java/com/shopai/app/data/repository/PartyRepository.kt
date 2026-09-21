package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.CreatePartyInput
import com.shopai.app.data.model.CustomerDetail
import com.shopai.app.data.model.PartyRecord
import com.shopai.app.data.model.PartySummary
import com.shopai.app.data.model.SupplierDetail

class PartyRepository(private val api: ShopAiApi) {
    suspend fun getCustomers(): List<PartySummary> = api.getCustomers().data

    suspend fun getSuppliers(): List<PartySummary> = api.getSuppliers().data

    suspend fun getCustomer(id: String): CustomerDetail = api.getCustomer(id).data

    suspend fun getSupplier(id: String): SupplierDetail = api.getSupplier(id).data

    suspend fun createCustomer(input: CreatePartyInput): PartyRecord =
        api.createCustomer(input).data

    suspend fun createSupplier(input: CreatePartyInput): PartyRecord =
        api.createSupplier(input).data
}
