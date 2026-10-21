package com.shopai.app.data.model

import com.shopai.app.books.model.BusinessType
import java.util.Locale

/**
 * What kind of business the owner runs (owner, 21 Oct 2026: "categories vaise features-a piricha naraiya kolapam relieve
 * aagidum"). The type decides which of the app's features show; the business categories only decide their details (units,
 * product categories, expiry). A shop may be more than one: a hotel or a mobile shop with repairs is SERVICE + RETAIL, a shop
 * selling both by the piece and in bulk is RETAIL + WHOLESALE. A distributor is a WHOLESALE business.
 */
enum class BusinessKind { SERVICE, RETAIL, WHOLESALE }

/** The app's features that differ by business type. Everything else (credit / payments, Kai, reminders, Daily Cash) is everyone's. */
enum class Feature {
    /** The owner's service menu with prices (Daily Sales by service: "oru cutting, 2 shaving"). */
    SERVICE_MENU,
    /** Stock: products, units, low stock, expiry. */
    INVENTORY,
    /** Sales / purchase bills, GST invoices, drafts. */
    BILLING,
    /** The HSN / SAC master. */
    HSN_MASTER,
    GROUP_BUYING,
    LOCAL_OFFERS,
    /** The Discover tab (offers and products around). */
    DISCOVER,
}

object FeatureSet {
    /**
     * The owner's table (21 Oct 2026): ✅ shown for that type; ⚪ (optional) and ❌ hidden — the ⚪ ones come back with
     * "Show all features". A service shop's stock and bills are optional; a wholesaler's group buying, offers and Discover are.
     */
    private val shown: Map<BusinessKind, Set<Feature>> = mapOf(
        BusinessKind.SERVICE to setOf(Feature.SERVICE_MENU),
        BusinessKind.RETAIL to setOf(Feature.INVENTORY, Feature.BILLING, Feature.HSN_MASTER, Feature.GROUP_BUYING, Feature.LOCAL_OFFERS, Feature.DISCOVER),
        BusinessKind.WHOLESALE to setOf(Feature.INVENTORY, Feature.BILLING, Feature.HSN_MASTER),
    )

    /** The features a business of these kinds sees (several kinds: all of theirs). Nothing known yet, or "show all": everything. */
    fun of(kinds: Set<BusinessKind>, showAll: Boolean = false): Set<Feature> =
        if (showAll || kinds.isEmpty()) Feature.entries.toSet() else kinds.flatMap { shown.getValue(it) }.toSet()

    /**
     * The business's kinds: the ones it picked, else (set up before the types) read from its categories — a service category
     * is SERVICE (a hotel also sells goods: SERVICE + RETAIL), "Wholesale & General Merchandise" is WHOLESALE, any other product
     * category RETAIL. Nothing to go on: empty (every feature shows — nothing is hidden on a guess).
     */
    fun kindsOf(business: Business?): Set<BusinessKind> {
        business ?: return emptySet()
        val picked = business.businessTypes.orEmpty().mapNotNull { t -> BusinessKind.entries.firstOrNull { it.name == t.trim().uppercase(Locale.ROOT) } }.toSet()
        if (picked.isNotEmpty()) return picked
        val categories = BusinessCategories.picked(business).mapNotNull(BusinessCategories::of)
        if (categories.isEmpty()) return emptySet()
        return categories.flatMap { c ->
            when {
                c.code == BusinessCategories.HOTEL -> listOf(BusinessKind.SERVICE, BusinessKind.RETAIL)
                c.service -> listOf(BusinessKind.SERVICE)
                c.code == BusinessCategories.WHOLESALE -> listOf(BusinessKind.WHOLESALE)
                else -> listOf(BusinessKind.RETAIL)
            }
        }.toSet()
    }

    /**
     * The books' business type for these kinds. A service shop is PRODUCT_AND_SERVICE, never SERVICE: a salon that also sells a
     * shampoo ("Show all features") keeps its stock right — the books' SERVICE type would never move stock.
     */
    fun booksType(kinds: Set<BusinessKind>): BusinessType = when {
        BusinessKind.SERVICE in kinds -> BusinessType.PRODUCT_AND_SERVICE
        BusinessKind.WHOLESALE in kinds -> BusinessType.WHOLESALER
        else -> BusinessType.RETAILER
    }

    /** The categories a business of these kinds picks from: services for a service shop, goods for a shop that sells them. */
    fun categoriesFor(kinds: Set<BusinessKind>): List<BusinessCategories.Category> {
        val services = BusinessKind.SERVICE in kinds
        val goods = BusinessKind.RETAIL in kinds || BusinessKind.WHOLESALE in kinds
        return BusinessCategories.all.filter { kinds.isEmpty() || it.service && services || !it.service && goods }
    }
}
