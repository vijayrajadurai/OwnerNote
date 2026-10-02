package com.shopai.app.data.offers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

private const val ambatturLat = 13.1143
private const val ambatturLon = 80.1548

class OffersLogicTest {
    private fun offsetKm(northKm: Double) = ambatturLat + northKm / 111.32

    private data class Offer(
        override val id: String,
        override val businessId: String,
        override val category: String = "FOOD",
        override val title: String = "Lunch thali",
        override val status: String = OFFER_STATUS_ACTIVE,
        override val expiresAt: Date,
        override val latitude: Double = ambatturLat,
        override val longitude: Double = ambatturLon,
        override val createdAt: Date,
    ) : MatchableOffer

    private fun hoursFromNow(now: Date, hours: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.HOUR_OF_DAY, hours)
        return calendar.time
    }

    @Test
    fun computeExpiresAtForTodayOnlyWithWindowUsesEndHour() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 25, 10, 0, 0)
        }.time
        val expiry = computeExpiresAt(now, todayOnly = true, window = TimeWindow(startHour = 18, endHour = 21))
        val calendar = Calendar.getInstance().apply { time = expiry }
        assertEquals(21, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun computeExpiresAtForTodayOnlyWithoutWindowIsEndOfDay() {
        val now = Date()
        val expiry = computeExpiresAt(now, todayOnly = true, window = null)
        val calendar = Calendar.getInstance().apply { time = expiry }
        assertEquals(23, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, calendar.get(Calendar.MINUTE))
    }

    @Test
    fun computeExpiresAtForNonTodayOnlyUsesFixedDuration() {
        val now = Date()
        val expiry = computeExpiresAt(now, todayOnly = false, window = null)
        val daysDiff = ((expiry.time - now.time) / (1000 * 60 * 60 * 24)).toInt()
        assertEquals(7, daysDiff)
    }

    @Test
    fun formatTimeWindowLabelHandlesNullWindow() {
        assertEquals("Today", formatTimeWindowLabel(null))
        assertEquals("இன்று", formatTimeWindowLabel(null, isTamil = true))
        assertEquals("6 PM – 9 PM", formatTimeWindowLabel(TimeWindow(18, 21)))
    }

    @Test
    fun isOfferExpiredTrueWhenStatusEndedRegardlessOfExpiresAt() {
        val now = Date()
        assertTrue(isOfferExpired(OFFER_STATUS_ENDED, hoursFromNow(now, 100), now))
    }

    @Test
    fun isOfferExpiredTrueWhenPastExpiresAt() {
        val now = Date()
        assertTrue(isOfferExpired(OFFER_STATUS_ACTIVE, hoursFromNow(now, -1), now))
        assertFalse(isOfferExpired(OFFER_STATUS_ACTIVE, hoursFromNow(now, 1), now))
    }

    @Test
    fun findNearbyOffersSortsByDistanceThenFreshness() {
        val now = Date()
        val far = Offer(id = "far", businessId = "biz-far", latitude = offsetKm(8.0), expiresAt = hoursFromNow(now, 2), createdAt = now)
        val nearOlder = Offer(id = "near-older", businessId = "biz-a", latitude = offsetKm(1.0), expiresAt = hoursFromNow(now, 2), createdAt = hoursFromNow(now, -5))
        val nearNewer = Offer(id = "near-newer", businessId = "biz-b", latitude = offsetKm(1.0), expiresAt = hoursFromNow(now, 2), createdAt = now)
        val expired = Offer(id = "expired", businessId = "biz-c", latitude = ambatturLat, expiresAt = hoursFromNow(now, -1), createdAt = now)
        val results = findNearbyOffers(ambatturLat, ambatturLon, listOf(far, nearOlder, nearNewer, expired), now, radiusKm = 5.0)
        assertEquals(listOf("near-newer", "near-older"), results.map { it.offer.id })
    }

    @Test
    fun findNearbyOffersRespectsCategoryFilter() {
        val now = Date()
        val food = Offer(id = "food", businessId = "biz-a", category = "FOOD", expiresAt = hoursFromNow(now, 2), createdAt = now)
        val grocery = Offer(id = "grocery", businessId = "biz-b", category = "GROCERY", expiresAt = hoursFromNow(now, 2), createdAt = now)
        val results = findNearbyOffers(ambatturLat, ambatturLon, listOf(food, grocery), now, radiusKm = 5.0, categoryFilter = "GROCERY")
        assertEquals(listOf("grocery"), results.map { it.offer.id })
    }

    @Test
    fun duplicateOfferSameBusinessSameTitleSameCategoryStillActive() {
        val now = Date()
        val existing = listOf(Offer(id = "a", businessId = "biz-a", expiresAt = hoursFromNow(now, 2), createdAt = now))
        assertEquals("a", findDuplicateOffer("biz-a", "Lunch Thali", "FOOD", existing, now)?.id)
        assertNull(findDuplicateOffer("biz-a", "Dinner Special", "FOOD", existing, now))
        assertNull(findDuplicateOffer("biz-b", "Lunch thali", "FOOD", existing, now))
    }

    @Test
    fun duplicateOfferNeverBlocksAfterOriginalExpired() {
        val now = Date()
        val existing = listOf(Offer(id = "a", businessId = "biz-a", expiresAt = hoursFromNow(now, -1), createdAt = now))
        assertNull(findDuplicateOffer("biz-a", "Lunch thali", "FOOD", existing, now))
    }

    @Test
    fun formatDistanceLabelSwitchesBetweenMetersAndKm() {
        assertEquals("500 m", formatDistanceLabel(0.5))
        assertEquals("2.3 km", formatDistanceLabel(2.34))
    }
}
