package com.shopai.app.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keeps scrollable tab content above the global Speak FAB (bottom-right). */
@Composable
fun VoiceFabBottomSpacer() {
    Spacer(Modifier.height(96.dp))
}
