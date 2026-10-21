package com.shopai.app.data.repository

import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.Business
import com.shopai.app.data.model.BusinessInput
import com.shopai.app.data.model.DashboardSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import retrofit2.HttpException

class BusinessRepository(private val api: ShopAiApi) {
    private val _current = MutableStateFlow<Business?>(null)

    /** The business last read or saved — the screens that show / hide features by business type follow it. Null = not read yet. */
    val current: StateFlow<Business?> = _current

    suspend fun getMyBusiness(): Business? {
        return try {
            api.getMyBusiness().data.also { _current.value = it }
        } catch (e: HttpException) {
            // Backend returns 404 when the owner hasn't completed business setup yet.
            if (e.code() == 404) null.also { _current.value = null } else throw e
        }
    }

    suspend fun saveBusiness(input: BusinessInput): Business =
        api.saveBusiness(input).data.also { _current.value = it }

    suspend fun getDashboard(): DashboardSnapshot =
        api.getDashboard().data
}
