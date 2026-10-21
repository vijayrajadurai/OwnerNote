package com.shopai.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.shopai.app.data.AppContainer
import com.shopai.app.data.model.BusinessKind
import com.shopai.app.data.model.Feature
import com.shopai.app.data.model.FeatureSet

/**
 * The features this business sees, by its type (owner, 21 Oct 2026): Home, the bottom tabs, More and the Daily Cash Note read
 * it, so a salon isn't shown stock and a wholesaler isn't shown group buying. Every feature until the business is read.
 */
val LocalFeatures = compositionLocalOf { Feature.entries.toSet() }

@Composable
fun rememberFeatures(container: AppContainer): Set<Feature> {
    val business by container.businessRepository.current.collectAsState()
    val showAll by container.preferencesRepository.showAllFeaturesFlow.collectAsState(initial = false)
    // (read once at start; screens that read the business keep it fresh from then on)
    LaunchedEffect(Unit) { if (container.businessRepository.current.value == null) runCatching { container.businessRepository.getMyBusiness() } }
    return remember(business, showAll) { FeatureSet.of(FeatureSet.kindsOf(business), showAll) }
}

/**
 * The books follow the business types the owner saved (Kai's service sales read the books' type). Books not open yet, or not set
 * up: nothing to do — the import sets the type from the business when it runs.
 */
suspend fun syncBooksType(container: AppContainer, kinds: Set<BusinessKind>) {
    if (kinds.isEmpty()) return
    runCatching { container.books.session()?.masters?.setBusinessType(FeatureSet.booksType(kinds)) }
}

/** Whether [feature] shows for this business. */
@Composable
fun hasFeature(feature: Feature): Boolean = feature in LocalFeatures.current
