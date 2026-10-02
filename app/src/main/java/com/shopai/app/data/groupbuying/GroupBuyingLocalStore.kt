package com.shopai.app.data.groupbuying

import com.shopai.app.data.model.CreateGroupBuyingRequestInput
import com.shopai.app.data.model.GroupBuyingGroupSummary
import com.shopai.app.data.model.GroupBuyingJoinResponse
import com.shopai.app.data.model.GroupBuyingMatchPublic
import com.shopai.app.data.model.GroupBuyingMatchTotals
import com.shopai.app.data.model.GroupBuyingMatchesResponse
import com.shopai.app.data.model.GroupBuyingMemberPublic
import com.shopai.app.data.model.GroupBuyingRequest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-device local demo store. Matches against other tenants are empty
 * unless those rows exist in this process — same limitation as Expo local mode.
 */
class GroupBuyingLocalStore {
    private val requests = ConcurrentHashMap<String, GroupBuyingRequest>()

    fun createRequest(businessId: String, input: CreateGroupBuyingRequestInput): GroupBuyingRequest {
        val existing = requests.values.filter { it.businessId == businessId }
        val duplicate = findDuplicateRequest(
            existing.map { it.toMatchable() },
            businessId,
            input.productId,
            input.requiredDate,
        )
        require(duplicate == null) { "An active group-buying request already exists for this product and date." }
        require(input.latitude != 0.0 || input.longitude != 0.0) {
            "A real shop location is required; (0,0) is not valid."
        }
        val now = Instant.now().toString()
        val row = GroupBuyingRequest(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            productId = input.productId,
            quantity = input.quantity,
            unit = input.unit,
            requiredDate = input.requiredDate,
            latitude = input.latitude,
            longitude = input.longitude,
            areaLabel = input.areaLabel,
            radiusKm = input.radiusKm,
            status = "ACTIVE",
            createdAt = now,
            updatedAt = now,
        )
        requests[row.id] = row
        return row
    }

    fun listRequests(businessId: String): List<GroupBuyingRequest> =
        requests.values.filter { it.businessId == businessId }.sortedByDescending { it.createdAt }

    fun listMatches(businessId: String, requestId: String): GroupBuyingMatchesResponse {
        val origin = requests[requestId] ?: error("Group buying request not found")
        require(origin.businessId == businessId) { "Group buying request not found" }
        val matches = findNearbyMatches(origin.toMatchable(), requests.values.map { it.toMatchable() })
        return GroupBuyingMatchesResponse(
            matches = matches.map { it.toPublicMatch() },
            totals = computeGroupTotals(origin.toMatchable(), matches).let {
                GroupBuyingMatchTotals(it.totalQuantity, it.businessCount, it.unit)
            },
            requestStatus = origin.status,
        )
    }

    fun join(businessId: String, requestId: String): GroupBuyingJoinResponse {
        val origin = requests[requestId] ?: error("Group buying request not found")
        require(origin.businessId == businessId) { "Group buying request not found" }
        val matchResult = listMatches(businessId, requestId)
        val now = Instant.now().toString()
        val joined = origin.copy(status = "JOINED", updatedAt = now)
        requests[requestId] = joined
        val group = GroupBuyingGroupSummary(
            id = UUID.randomUUID().toString(),
            productId = origin.productId,
            requiredDate = origin.requiredDate,
            areaLabel = origin.areaLabel,
            status = "ACTIVE",
        )
        return GroupBuyingJoinResponse(
            group = group,
            members = listOf(
                GroupBuyingMemberPublic(
                    id = UUID.randomUUID().toString(),
                    groupId = group.id,
                    requestId = joined.id,
                    businessId = joined.businessId,
                    quantity = joined.quantity,
                    status = "JOINED",
                    joinedAt = now,
                ),
            ),
            request = joined,
            matches = matchResult.matches,
            totals = matchResult.totals,
        )
    }

    fun cancelRequest(businessId: String, requestId: String): GroupBuyingRequest {
        val row = requests[requestId] ?: error("Group buying request not found")
        require(row.businessId == businessId) { "You can only cancel your own group-buying request." }
        val updated = row.copy(status = "CANCELLED", updatedAt = Instant.now().toString())
        requests[requestId] = updated
        return updated
    }

    private fun NearbyMatch.toPublicMatch(): GroupBuyingMatchPublic =
        GroupBuyingMatchPublic(
            requestId = request.id,
            businessId = request.businessId,
            shopName = request.areaLabel,
            ownerName = "",
            areaLabel = request.areaLabel,
            quantity = request.quantity,
            unit = request.unit,
            requiredDate = request.requiredDate,
            distanceKm = roundDistanceKm(distanceKm),
            interestStatus = "POSTED",
        )

    private fun GroupBuyingRequest.toMatchable(): MatchableGroupBuyingRequest =
        MatchableGroupBuyingRequest(
            id = id,
            businessId = businessId,
            productId = productId,
            quantity = quantity,
            unit = unit,
            requiredDate = requiredDate,
            latitude = latitude,
            longitude = longitude,
            areaLabel = areaLabel,
            radiusKm = radiusKm,
            status = status,
        )
}
