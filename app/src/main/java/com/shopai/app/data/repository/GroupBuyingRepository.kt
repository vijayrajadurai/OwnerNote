package com.shopai.app.data.repository

import com.shopai.app.BuildConfig
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.groupbuying.GroupBuyingLocalStore
import com.shopai.app.data.model.CreateGroupBuyingRequestInput
import com.shopai.app.data.model.GroupBuyingInboxItem
import com.shopai.app.data.model.GroupBuyingInviteRespondInput
import com.shopai.app.data.model.GroupBuyingInviteRespondResult
import com.shopai.app.data.model.GroupBuyingJoinResponse
import com.shopai.app.data.model.GroupBuyingMatchesResponse
import com.shopai.app.data.model.GroupBuyingRequest
import java.io.IOException

/**
 * Network Group Buying API with the same local-demo fallback shape as Expo
 * `groupBuyingApi.ts`: when no backend URL is configured, all calls stay on-device.
 */
class GroupBuyingRepository(
    private val api: ShopAiApi,
    private val local: GroupBuyingLocalStore = GroupBuyingLocalStore(),
    private val localBusinessId: String = LOCAL_BUSINESS_ID,
) {
    suspend fun createRequest(input: CreateGroupBuyingRequestInput): GroupBuyingRequest {
        if (isLocalMode()) return local.createRequest(localBusinessId, input)
        return runCatching { api.createGroupBuyingRequest(input).data }
            .recoverCatching { error ->
                if (error is IOException) local.createRequest(localBusinessId, input) else throw error
            }
            .getOrThrow()
    }

    suspend fun listRequests(): List<GroupBuyingRequest> {
        if (isLocalMode()) return local.listRequests(localBusinessId)
        return runCatching { api.listGroupBuyingRequests().data }
            .recoverCatching { error ->
                if (error is IOException) local.listRequests(localBusinessId) else throw error
            }
            .getOrThrow()
    }

    suspend fun listMatches(requestId: String): GroupBuyingMatchesResponse {
        if (isLocalMode()) return local.listMatches(localBusinessId, requestId)
        return runCatching { api.listGroupBuyingMatches(requestId).data }
            .recoverCatching { error ->
                if (error is IOException) local.listMatches(localBusinessId, requestId) else throw error
            }
            .getOrThrow()
    }

    suspend fun join(requestId: String): GroupBuyingJoinResponse {
        if (isLocalMode()) return local.join(localBusinessId, requestId)
        return runCatching { api.joinGroupBuyingRequest(requestId).data }
            .recoverCatching { error ->
                if (error is IOException) local.join(localBusinessId, requestId) else throw error
            }
            .getOrThrow()
    }

    suspend fun cancel(requestId: String): GroupBuyingRequest {
        if (isLocalMode()) return local.cancelRequest(localBusinessId, requestId)
        return runCatching { api.cancelGroupBuyingRequest(requestId).data }
            .recoverCatching { error ->
                if (error is IOException) local.cancelRequest(localBusinessId, requestId) else throw error
            }
            .getOrThrow()
    }

    suspend fun listInbox(): List<GroupBuyingInboxItem> {
        if (isLocalMode()) return emptyList()
        return runCatching { api.listGroupBuyingInbox().data }
            .recoverCatching { error ->
                if (error is IOException) emptyList() else throw error
            }
            .getOrThrow()
    }

    suspend fun respondToInvite(inviteId: String, interested: Boolean, quantity: Double? = null): GroupBuyingInviteRespondResult {
        return api.respondGroupBuyingInvite(
            inviteId,
            GroupBuyingInviteRespondInput(interested = interested, quantity = quantity),
        ).data
    }

    private fun isLocalMode(): Boolean {
        val base = BuildConfig.API_BASE_URL.trim()
        return base.isEmpty() || base == "local://"
    }

    companion object {
        const val LOCAL_BUSINESS_ID = "local-business"
    }
}
