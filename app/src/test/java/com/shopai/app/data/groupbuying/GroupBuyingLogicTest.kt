package com.shopai.app.data.groupbuying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.shopai.app.data.model.GroupBuyingMatchPublic

class GroupBuyingLogicTest {
    private val ambatturLat = 13.1143
    private val ambatturLon = 80.1548

    private fun offsetKm(northKm: Double) = ambatturLat + northKm / 111.32

    private fun request(
        id: String,
        businessId: String,
        quantity: Double = 20.0,
        productId: String = "cement",
        requiredDate: String = "2026-09-30",
        lat: Double = ambatturLat,
        lon: Double = ambatturLon,
        radiusKm: Double = 5.0,
        status: String = "ACTIVE",
    ) = MatchableGroupBuyingRequest(
        id = id,
        businessId = businessId,
        productId = productId,
        quantity = quantity,
        unit = "bags",
        requiredDate = requiredDate,
        latitude = lat,
        longitude = lon,
        areaLabel = "Ambattur",
        radiusKm = radiusKm,
        status = status,
    )

    @Test
    fun matchPublicTypeHasNoCoordinateFields() {
        val keys = GroupBuyingMatchPublic::class.java.declaredFields.map { it.name }
        assertFalse(keys.contains("latitude"))
        assertFalse(keys.contains("longitude"))
    }

    @Test
    fun compatibleDatesAllowOneDayWindow() {
        assertTrue(isCompatibleRequiredDate("2026-09-30", "2026-09-30"))
        assertTrue(isCompatibleRequiredDate("2026-09-30", "2026-10-01"))
        assertFalse(isCompatibleRequiredDate("2026-09-30", "2026-10-02"))
    }

    @Test
    fun phase1Section32AmbatturCementScenario() {
        val origin = request(id = "a", businessId = "biz-a", quantity = 20.0)
        val matches = findNearbyMatches(
            origin,
            listOf(
                request(id = "b", businessId = "biz-b", quantity = 50.0, lat = offsetKm(2.0)),
                request(
                    id = "c",
                    businessId = "biz-c",
                    quantity = 80.0,
                    requiredDate = "2026-10-01",
                    lat = offsetKm(3.0),
                ),
                request(id = "d", businessId = "biz-d", quantity = 40.0, lat = offsetKm(20.0)),
                request(id = "e", businessId = "biz-e", productId = "paint", quantity = 10.0, lat = offsetKm(1.0)),
                request(id = "f", businessId = "biz-f", quantity = 30.0, lat = offsetKm(8.0)),
            ),
        )
        assertEquals(listOf("biz-b", "biz-c"), matches.map { it.request.businessId })
        val totals = computeGroupTotals(origin, matches)
        assertEquals(150.0, totals.totalQuantity, 0.001)
        assertEquals(3, totals.businessCount)
        matches.forEach { match ->
            val publicMatch = GroupBuyingMatchPublic(
                requestId = match.request.id,
                businessId = match.request.businessId,
                areaLabel = match.request.areaLabel,
                quantity = match.request.quantity,
                unit = match.request.unit,
                requiredDate = match.request.requiredDate,
                distanceKm = roundDistanceKm(match.distanceKm),
            )
            assertFalse(publicMatch.toString().contains("latitude="))
        }
    }

    @Test
    fun duplicateRequestSameProductAndDate() {
        val existing = listOf(request(id = "a", businessId = "biz-a"))
        assertEquals("a", findDuplicateRequest(existing, "biz-a", "cement", "2026-09-30")?.id)
        assertNull(findDuplicateRequest(existing, "biz-a", "paint", "2026-09-30"))
    }
}
