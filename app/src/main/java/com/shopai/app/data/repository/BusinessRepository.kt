package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.data.model.DashboardSnapshot
import retrofit2.HttpException

class BusinessRepository(private val api: ShopAiApi) {
    suspend fun getMyBusiness(): Business? {
        return try {
            api.getMyBusiness().data
        } catch (e: HttpException) {
            // Backend returns 404 when the owner hasn't completed business setup yet.
            if (e.code() == 404) null else throw e
        }
    }

    suspend fun saveBusiness(input: BusinessInput): Business =
        api.saveBusiness(input).data

    suspend fun getDashboard(): DashboardSnapshot =
        api.getDashboard().data
}
