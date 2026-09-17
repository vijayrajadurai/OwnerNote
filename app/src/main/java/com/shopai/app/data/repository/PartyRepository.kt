package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.PartySummary

class PartyRepository(private val api: ShopAiApi) {
    suspend fun getCustomers(): List<PartySummary> = api.getCustomers().data

    suspend fun getSuppliers(): List<PartySummary> = api.getSuppliers().data
}
