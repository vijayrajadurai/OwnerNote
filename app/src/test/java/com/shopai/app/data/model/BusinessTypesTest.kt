package com.shopai.app.data.model

import com.shopai.app.books.model.BusinessType
import com.shopai.app.data.model.BusinessKind.RETAIL
import com.shopai.app.data.model.BusinessKind.SERVICE
import com.shopai.app.data.model.BusinessKind.WHOLESALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Business types decide the features (owner, 21 Oct 2026: "categories vaise features-a piricha naraiya kolapam relieve aagidum"):
 * a salon sees its service menu and no stock, a retailer its stock, bills, offers and Discover, a wholesaler stock and bills
 * without group buying; several types see all of theirs, and nothing is hidden on a guess.
 */
class BusinessTypesTest {
    private fun biz(category: String = "OTHER", categories: List<String>? = null, types: List<String>? = null) =
        Business("b", "u", "O", "Shop", category, "Chennai", null, null, categories = categories, businessTypes = types)

    @Test fun eachTypeSeesItsOwnFeatures() {
        assertEquals(setOf(Feature.SERVICE_MENU), FeatureSet.of(setOf(SERVICE)))
        val retail = FeatureSet.of(setOf(RETAIL))
        assertTrue(retail.containsAll(listOf(Feature.INVENTORY, Feature.BILLING, Feature.HSN_MASTER, Feature.GROUP_BUYING, Feature.LOCAL_OFFERS, Feature.DISCOVER)))
        assertFalse(Feature.SERVICE_MENU in retail)
        assertEquals(setOf(Feature.INVENTORY, Feature.BILLING, Feature.HSN_MASTER), FeatureSet.of(setOf(WHOLESALE)))
        // Several types: all of theirs (a hotel: its menu and its stock).
        assertEquals(setOf(Feature.SERVICE_MENU) + retail, FeatureSet.of(setOf(SERVICE, RETAIL)))
        // Nothing known, or "Show all features": everything.
        assertEquals(Feature.entries.toSet(), FeatureSet.of(emptySet()))
        assertEquals(Feature.entries.toSet(), FeatureSet.of(setOf(SERVICE), showAll = true))
    }

    @Test fun theTypesPickedAreReadAndAnUnknownOneIsNotGuessed() {
        assertEquals(setOf(SERVICE), FeatureSet.kindsOf(biz(types = listOf("SERVICE"))))
        assertEquals(setOf(RETAIL, WHOLESALE), FeatureSet.kindsOf(biz(types = listOf(" retail ", "WHOLESALE", "RETAIL"))))
        // A type this app doesn't know (a newer server's) is never read as another one; with nothing else known, the categories decide.
        assertEquals(setOf(SERVICE), FeatureSet.kindsOf(biz(categories = listOf("SALON_BEAUTY_SERVICE"), types = listOf("FRANCHISE"))))
        // Picked types win over the categories.
        assertEquals(setOf(WHOLESALE), FeatureSet.kindsOf(biz(categories = listOf("SALON_BEAUTY_SERVICE"), types = listOf("WHOLESALE"))))
    }

    @Test fun aBusinessSetUpBeforeTheTypesIsReadFromItsCategories() {
        assertEquals(setOf(SERVICE), FeatureSet.kindsOf(biz("BEAUTY_SALON")))
        assertEquals(setOf(SERVICE, RETAIL), FeatureSet.kindsOf(biz("RESTAURANT_FOOD")))
        assertEquals(setOf(RETAIL), FeatureSet.kindsOf(biz("GROCERY")))
        assertEquals(setOf(WHOLESALE), FeatureSet.kindsOf(biz(categories = listOf("WHOLESALE_GENERAL"))))
        assertEquals(setOf(SERVICE, RETAIL), FeatureSet.kindsOf(biz(categories = listOf("ELECTRONICS_MOBILES", "MOBILE_REPAIR"))))
        // Nothing to go on: no types (every feature shows), never a guessed one.
        assertTrue(FeatureSet.kindsOf(null).isEmpty())
        assertTrue(FeatureSet.kindsOf(biz("SOMETHING_NEW")).isEmpty())
    }

    @Test fun theBooksTypeFollowsAndAServiceShopKeepsItsStock() {
        assertEquals(BusinessType.PRODUCT_AND_SERVICE, FeatureSet.booksType(setOf(SERVICE)))
        assertTrue(FeatureSet.booksType(setOf(SERVICE)).inventory)
        assertEquals(BusinessType.PRODUCT_AND_SERVICE, FeatureSet.booksType(setOf(SERVICE, WHOLESALE)))
        assertEquals(BusinessType.WHOLESALER, FeatureSet.booksType(setOf(RETAIL, WHOLESALE)))
        assertEquals(BusinessType.RETAILER, FeatureSet.booksType(setOf(RETAIL)))
        assertEquals(BusinessType.RETAILER, FeatureSet.booksType(emptySet()))
    }

    @Test fun theCategoriesOfferedFollowTheTypes() {
        val service = FeatureSet.categoriesFor(setOf(SERVICE))
        assertTrue(service.isNotEmpty() && service.all { it.service })
        assertTrue(service.any { it.code == "TAILORING_BOUTIQUE" } && service.any { it.code == BusinessCategories.OTHER_SERVICES })
        val goods = FeatureSet.categoriesFor(setOf(WHOLESALE))
        assertTrue(goods.none { it.service } && goods.any { it.code == BusinessCategories.WHOLESALE })
        assertEquals(BusinessCategories.all.size, FeatureSet.categoriesFor(setOf(SERVICE, RETAIL)).size)
        assertEquals(BusinessCategories.all, FeatureSet.categoriesFor(emptySet()))
        // A salon's cards are service shops; its "Other" is "Other Services", a retailer's "Other Product Businesses".
        assertTrue(BusinessCategories.popularFor(service).all { it.service })
        assertEquals(BusinessCategories.OTHER_SERVICES, BusinessCategories.otherFor(service))
        assertEquals(BusinessCategories.OTHER, BusinessCategories.otherFor(FeatureSet.categoriesFor(setOf(RETAIL))))
        // A service word searched by a salon finds it; a word in no category finds nothing (the owner names it under Other).
        assertTrue(BusinessCategories.search("xerox").any { it in service })
        assertTrue(BusinessCategories.search("zzqx").none { it in service })
    }
}
