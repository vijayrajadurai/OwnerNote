package com.shopai.app.ui.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun load_whenBillingDisabled_setsUnavailable() = runTest {
        val viewModel = SubscriptionViewModel()
        advanceUntilIdle()
        assertEquals(SubscriptionStatus.UNAVAILABLE, viewModel.uiState.value.status)
        assertEquals(2, viewModel.uiState.value.plans.size)
    }
}
