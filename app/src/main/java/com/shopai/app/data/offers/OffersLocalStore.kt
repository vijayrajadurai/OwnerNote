package com.shopai.app.data.offers

import com.shopai.app.data.model.LocalOffer
import com.shopai.app.data.model.OfferWithDistanceDto
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-device local demo store, same limitation as Expo local mode —
 * discovery only ever sees offers created in this process.
 */
class OffersLocalStore {
    private val offers = ConcurrentHashMap<String, LocalOffer>()

    fun listMyOffers(businessId: String): List<LocalOffer> =
        offers.values.filter { it.businessId == businessId }.sortedByDescending { it.createdAt }

    fun createOffer(
        businessId: String,
        businessName: String,
        category: String,
        offerType: String,
        title: String,
        description: String?,
        price: Double,
        imageUri: String?,
        todayOnly: Boolean,
        window: TimeWindow?,
        latitude: Double,
        longitude: Double,
        areaLabel: String,
    ): LocalOffer {
        val now = Date()
        val existing = listMyOffers(businessId).map { it.toMatchable() }
        val duplicate = findDuplicateOffer(businessId, title, category, existing, now)
        require(duplicate == null) { "You already have an active offer with this title in this category." }
        require(latitude != 0.0 || longitude != 0.0) { "A real shop location is required." }

        val expiresAt = computeExpiresAt(now, todayOnly, window)
        val nowIso = now.toInstantString()
        val offer = LocalOffer(
            id = UUID.randomUUID().toString(),
            businessId = businessId,
            businessName = businessName,
            category = category,
            offerType = offerType,
            title = title,
            description = description,
            price = price,
            imageUri = imageUri,
            todayOnly = todayOnly,
            timeWindowLabel = formatTimeWindowLabel(window),
            startAt = nowIso,
            expiresAt = expiresAt.toInstantString(),
            latitude = latitude,
            longitude = longitude,
            areaLabel = areaLabel,
            status = OFFER_STATUS_ACTIVE,
            createdAt = nowIso,
            updatedAt = nowIso,
            viewCount = 0,
            directionsCount = 0,
            callCount = 0,
        )
        offers[offer.id] = offer
        return offer
    }

    fun endOffer(businessId: String, offerId: String): LocalOffer {
        val offer = offers[offerId]?.takeIf { it.businessId == businessId } ?: error("Offer not found")
        val updated = offer.copy(status = OFFER_STATUS_ENDED, updatedAt = Date().toInstantString())
        offers[offerId] = updated
        return updated
    }

    fun getNearbyOffers(latitude: Double, longitude: Double, radiusKm: Double, category: String?): List<OfferWithDistanceDto> {
        val now = Date()
        val matches = findNearbyOffers(latitude, longitude, offers.values.map { it.toMatchable() }, now, radiusKm, category)
        return matches.mapNotNull { match ->
            offers[match.offer.id]?.let { offer -> OfferWithDistanceDto(offer, match.distanceKm) }
        }
    }

    fun recordView(offerId: String): LocalOffer = mutate(offerId) { it.copy(viewCount = it.viewCount + 1) }

    fun recordDirectionsTap(offerId: String): LocalOffer = mutate(offerId) { it.copy(directionsCount = it.directionsCount + 1) }

    fun recordCallTap(offerId: String): LocalOffer = mutate(offerId) { it.copy(callCount = it.callCount + 1) }

    fun getOfferById(offerId: String): LocalOffer? = offers[offerId]

    private fun mutate(offerId: String, block: (LocalOffer) -> LocalOffer): LocalOffer {
        val current = offers[offerId] ?: error("Offer not found")
        val updated = block(current)
        offers[offerId] = updated
        return updated
    }

    private fun Date.toInstantString(): String = Instant.ofEpochMilli(time).toString()

    private fun LocalOffer.toMatchable(): MatchableOfferImpl = MatchableOfferImpl(
        id = id,
        businessId = businessId,
        category = category,
        title = title,
        status = status,
        expiresAt = Date(Instant.parse(expiresAt).toEpochMilli()),
        latitude = latitude,
        longitude = longitude,
        createdAt = Date(Instant.parse(createdAt).toEpochMilli()),
    )

    private data class MatchableOfferImpl(
        override val id: String,
        override val businessId: String,
        override val category: String,
        override val title: String,
        override val status: String,
        override val expiresAt: Date,
        override val latitude: Double,
        override val longitude: Double,
        override val createdAt: Date,
    ) : MatchableOffer
}
