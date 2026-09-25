package com.shopai.app.data.offers

import com.shopai.app.data.groupbuying.haversineKm
import java.util.Calendar
import java.util.Date

/**
 * Faithful port of the Owner Note Local Offers pure logic.
 * Original (mobile): apps/mobile/src/storage/localAi/offers.ts.
 * Backend port: OwnerNote-Backend apps/backend/src/modules/offers/offers.logic.ts.
 *
 * Deliberately separate from DiscoverItem (the shop's own product/event news
 * feed) — this is the geo-radius, expiring, consumer-facing offer model with
 * its own distance-ranked discovery.
 */

val OFFER_CATEGORIES = listOf(
    "FOOD", "GROCERY", "FASHION", "BEAUTY", "AUTOMOBILE", "ELECTRONICS", "PHARMACY",
    "HOME", "HARDWARE", "JEWELLERY", "CAFE", "BAKERY", "FITNESS", "EDUCATION", "PET", "SERVICES",
)

val OFFER_TYPES = listOf(
    "TODAY_OFFER", "NEW_ARRIVAL", "SPECIAL", "FESTIVAL", "STOCK_CLEARANCE", "TODAYS_SPECIAL", "LIMITED_TIME",
)

const val DEFAULT_OFFER_TYPE = "TODAY_OFFER"

data class TimeWindow(val startHour: Int, val endHour: Int)

val OFFER_HOUR_OPTIONS: List<Int> = (6..24).toList()

fun formatHour12(hour: Int): String {
    val normalized = ((hour % 24) + 24) % 24
    val period = if (normalized < 12) "AM" else "PM"
    val displayHour = if (normalized % 12 == 0) 12 else normalized % 12
    return "$displayHour $period"
}

fun formatTimeWindowLabel(window: TimeWindow?, isTamil: Boolean = false): String {
    if (window == null) return if (isTamil) "இன்று" else "Today"
    return "${formatHour12(window.startHour)} – ${formatHour12(window.endHour)}"
}

private const val NON_TODAY_ONLY_DURATION_DAYS = 7

fun computeExpiresAt(now: Date, todayOnly: Boolean, window: TimeWindow?): Date {
    val calendar = Calendar.getInstance()
    calendar.time = now
    if (!todayOnly) {
        calendar.add(Calendar.DATE, NON_TODAY_ONLY_DURATION_DAYS)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        return calendar.time
    }
    if (window != null) {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.add(Calendar.HOUR_OF_DAY, window.endHour)
    } else {
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
    }
    return calendar.time
}

const val OFFER_STATUS_ACTIVE = "ACTIVE"
const val OFFER_STATUS_ENDED = "ENDED"

interface MatchableOffer {
    val id: String
    val businessId: String
    val category: String
    val title: String
    val status: String
    val expiresAt: Date
    val latitude: Double
    val longitude: Double
    val createdAt: Date
}

fun isOfferExpired(status: String, expiresAt: Date, now: Date): Boolean {
    if (status == OFFER_STATUS_ENDED) return true
    return expiresAt.time <= now.time
}

val OFFER_RADII_KM = listOf(1, 3, 5, 10)
const val DEFAULT_OFFER_RADIUS_KM = 1

data class OfferWithDistance<T : MatchableOffer>(val offer: T, val distanceKm: Double)

private const val DISTANCE_TIE_BREAK_KM = 0.05

fun <T : MatchableOffer> findNearbyOffers(
    consumerLatitude: Double,
    consumerLongitude: Double,
    offers: List<T>,
    now: Date,
    radiusKm: Double,
    categoryFilter: String? = null,
): List<OfferWithDistance<T>> =
    offers
        .filter { !isOfferExpired(it.status, it.expiresAt, now) }
        .filter { categoryFilter == null || it.category == categoryFilter }
        .map { OfferWithDistance(it, haversineKm(consumerLatitude, consumerLongitude, it.latitude, it.longitude)) }
        .filter { it.distanceKm <= radiusKm }
        .sortedWith(
            compareBy<OfferWithDistance<T>> { candidate ->
                // Distances within the tie-break band compare as equal; sortedWith is stable
                // so the createdAt-desc tiebreak below still governs their relative order.
                Math.round(candidate.distanceKm / DISTANCE_TIE_BREAK_KM)
            }.thenByDescending { it.offer.createdAt.time },
        )

fun formatDistanceLabel(distanceKm: Double): String =
    if (distanceKm < 1) "${Math.round(distanceKm * 1000)} m" else "%.1f km".format(distanceKm)

private fun normalizeOfferTitle(title: String): String = title.trim().lowercase()

fun <T : MatchableOffer> findDuplicateOffer(
    businessId: String,
    title: String,
    category: String,
    existingOffers: List<T>,
    now: Date,
): T? {
    val target = normalizeOfferTitle(title)
    return existingOffers.firstOrNull {
        it.businessId == businessId &&
            it.category == category &&
            normalizeOfferTitle(it.title) == target &&
            !isOfferExpired(it.status, it.expiresAt, now)
    }
}
