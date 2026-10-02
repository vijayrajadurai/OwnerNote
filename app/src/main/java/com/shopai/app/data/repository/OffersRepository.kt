package com.shopai.app.data.repository

import com.shopai.app.BuildConfig
import com.shopai.app.data.api.ShopAiApi
import com.shopai.app.data.model.CreateLocalOfferInput
import com.shopai.app.data.model.LocalOffer
import com.shopai.app.data.model.OfferWithDistanceDto
import com.shopai.app.data.offers.OffersLocalStore
import com.shopai.app.data.offers.TimeWindow
import java.io.IOException

/**
 * Network Local Offers API with the same local-demo fallback shape as
 * `GroupBuyingRepository`. Offer creation requires the business's saved
 * location (latitude/longitude/areaLabel) — same requirement the backend
 * enforces via `requireBusinessId` + a saved `Business.latitude/longitude`.
 */
class OffersRepository(
    private val api: ShopAiApi,
    private val businessRepository: BusinessRepository,
    private val local: OffersLocalStore = OffersLocalStore(),
    private val localBusinessId: String = LOCAL_BUSINESS_ID,
) {
    suspend fun listMyOffers(): List<LocalOffer> {
        if (isLocalMode()) return local.listMyOffers(localBusinessId)
        return runCatching { api.listMyOffers().data }
            .recoverCatching { error -> if (error is IOException) local.listMyOffers(localBusinessId) else throw error }
            .getOrThrow()
    }

    suspend fun createOffer(input: CreateLocalOfferInput): LocalOffer {
        if (isLocalMode()) return createOfferLocally(input)
        return runCatching { api.createOffer(input).data }
            .recoverCatching { error -> if (error is IOException) createOfferLocally(input) else throw error }
            .getOrThrow()
    }

    suspend fun endOffer(offerId: String): LocalOffer {
        if (isLocalMode()) return local.endOffer(localBusinessId, offerId)
        return runCatching { api.endOffer(offerId).data }
            .recoverCatching { error -> if (error is IOException) local.endOffer(localBusinessId, offerId) else throw error }
            .getOrThrow()
    }

    suspend fun getNearbyOffers(latitude: Double, longitude: Double, radiusKm: Double, category: String? = null): List<OfferWithDistanceDto> {
        if (isLocalMode()) return local.getNearbyOffers(latitude, longitude, radiusKm, category)
        return runCatching { api.getNearbyOffers(latitude, longitude, radiusKm, category).data }
            .recoverCatching { error -> if (error is IOException) local.getNearbyOffers(latitude, longitude, radiusKm, category) else throw error }
            .getOrThrow()
    }

    suspend fun recordView(offerId: String) {
        if (isLocalMode()) { local.recordView(offerId); return }
        runCatching { api.recordOfferView(offerId) }
            .recoverCatching { error -> if (error is IOException) local.recordView(offerId) else throw error }
    }

    suspend fun recordDirectionsTap(offerId: String) {
        if (isLocalMode()) { local.recordDirectionsTap(offerId); return }
        runCatching { api.recordOfferDirections(offerId) }
            .recoverCatching { error -> if (error is IOException) local.recordDirectionsTap(offerId) else throw error }
    }

    suspend fun recordCallTap(offerId: String) {
        if (isLocalMode()) { local.recordCallTap(offerId); return }
        runCatching { api.recordOfferCall(offerId) }
            .recoverCatching { error -> if (error is IOException) local.recordCallTap(offerId) else throw error }
    }

    private suspend fun createOfferLocally(input: CreateLocalOfferInput): LocalOffer {
        val business = businessRepository.getMyBusiness()
        val latitude = business?.latitude ?: 0.0
        val longitude = business?.longitude ?: 0.0
        val areaLabel = business?.areaLabel?.takeIf { it.isNotBlank() } ?: business?.city.orEmpty()
        val window = if (input.startHour != null && input.endHour != null) TimeWindow(input.startHour, input.endHour) else null
        return local.createOffer(
            businessId = localBusinessId,
            businessName = business?.businessName.orEmpty(),
            category = input.category,
            offerType = input.offerType,
            title = input.title,
            description = input.description,
            price = input.price,
            imageUri = input.imageUri,
            todayOnly = input.todayOnly,
            window = window,
            latitude = latitude,
            longitude = longitude,
            areaLabel = areaLabel,
        )
    }

    private fun isLocalMode(): Boolean {
        val base = BuildConfig.API_BASE_URL.trim()
        return base.isEmpty() || base == "local://"
    }

    companion object {
        const val LOCAL_BUSINESS_ID = "local-business"
    }
}
