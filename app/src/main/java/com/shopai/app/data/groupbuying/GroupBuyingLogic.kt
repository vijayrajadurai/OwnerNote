package com.shopai.app.data.groupbuying

/**
 * Faithful port of the Owner Note Group Buying matching engine.
 * Original (mobile, Phase 1): apps/mobile/src/storage/localAi/groupBuying.ts
 * Backend port: OwnerNote-Backend apps/backend/src/modules/groupBuying/groupBuying.logic.ts
 */
data class MatchableGroupBuyingRequest(
    val id: String,
    val businessId: String,
    val productId: String,
    val quantity: Double,
    val unit: String,
    val requiredDate: String,
    val latitude: Double,
    val longitude: Double,
    val areaLabel: String,
    val radiusKm: Double,
    val status: String,
)

data class NearbyMatch(
    val request: MatchableGroupBuyingRequest,
    val distanceKm: Double,
)

data class GroupBuyingTotals(
    val totalQuantity: Double,
    val businessCount: Int,
    val unit: String,
)

private const val EarthRadiusKm = 6371.0
private const val CompatibleDateWindowDays = 1
private val DuplicateBlockingStatuses = setOf("ACTIVE", "MATCHED", "JOINED")

fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * EarthRadiusKm * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

fun roundDistanceKm(km: Double): Double = Math.round(km * 10.0) / 10.0

fun isCompatibleRequiredDate(a: String, b: String): Boolean {
    val aParts = a.split("-").map { it.toInt() }
    val bParts = b.split("-").map { it.toInt() }
    val aUtc = java.time.LocalDate.of(aParts[0], aParts[1], aParts[2]).toEpochDay()
    val bUtc = java.time.LocalDate.of(bParts[0], bParts[1], bParts[2]).toEpochDay()
    return kotlin.math.abs(aUtc - bUtc) <= CompatibleDateWindowDays
}

fun findNearbyMatches(
    origin: MatchableGroupBuyingRequest,
    candidates: List<MatchableGroupBuyingRequest>,
): List<NearbyMatch> =
    candidates
        .asSequence()
        .filter { it.id != origin.id }
        .filter { it.businessId != origin.businessId }
        .filter { it.productId == origin.productId }
        .filter { it.status == "ACTIVE" }
        .filter { isCompatibleRequiredDate(origin.requiredDate, it.requiredDate) }
        .map { candidate ->
            NearbyMatch(
                request = candidate,
                distanceKm = haversineKm(
                    origin.latitude,
                    origin.longitude,
                    candidate.latitude,
                    candidate.longitude,
                ),
            )
        }
        .filter { it.distanceKm <= origin.radiusKm }
        .sortedBy { it.distanceKm }
        .toList()

fun computeGroupTotals(
    origin: MatchableGroupBuyingRequest,
    matches: List<NearbyMatch>,
): GroupBuyingTotals {
    val matchQuantity = matches.sumOf { it.request.quantity }
    return GroupBuyingTotals(
        totalQuantity = origin.quantity + matchQuantity,
        businessCount = matches.size + 1,
        unit = origin.unit,
    )
}

fun findDuplicateRequest(
    existing: List<MatchableGroupBuyingRequest>,
    businessId: String,
    productId: String,
    requiredDate: String,
): MatchableGroupBuyingRequest? =
    existing.firstOrNull {
        it.businessId == businessId &&
            it.productId == productId &&
            it.requiredDate == requiredDate &&
            it.status in DuplicateBlockingStatuses
    }
