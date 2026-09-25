package com.shopai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.components.BottomNavBar
import com.shopai.app.ui.components.BottomNavTab
import com.shopai.app.ui.navigation.Routes

@Composable
fun MainTabScaffold(
    activeTab: BottomNavTab,
    onNavigate: (String) -> Unit,
    padContent: Boolean = true,
    applyTopInset: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val contentModifier = Modifier
            .fillMaxSize()
            .then(
                if (applyTopInset) {
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                } else {
                    Modifier
                },
            )
            .then(
                if (padContent) {
                    Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 16.dp, bottom = 8.dp)
                } else {
                    Modifier
                },
            )
        Column(modifier = contentModifier) {
            content()
        }
        BottomNavBar(
            active = activeTab,
            onTabSelected = { tab ->
                val route = when (tab) {
                    BottomNavTab.Home -> Routes.Home
                    BottomNavTab.Discover -> Routes.Discover
                    BottomNavTab.Customers -> Routes.Customers
                    BottomNavTab.Suppliers -> Routes.Suppliers
                    BottomNavTab.More -> Routes.More
                }
                if (tab != activeTab) onNavigate(route)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
