package com.shopai.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keeps scrollable tab content above the floating bottom nav. */
@Composable
fun VoiceFabBottomSpacer() {
    Spacer(Modifier.height(80.dp))
}
