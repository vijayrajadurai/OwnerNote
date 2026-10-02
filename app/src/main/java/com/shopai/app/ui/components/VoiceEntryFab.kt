package com.shopai.app.ui.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shopai.app.R

const val VOICE_ENTRY_FAB_TEST_TAG = "voice_entry_fab"

@Composable
fun VoiceEntryFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 24.dp,
) {
    val label = stringResource(R.string.voice_speak)
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .padding(bottom = bottomPadding)
            .navigationBarsPadding()
            .testTag(VOICE_ENTRY_FAB_TEST_TAG)
            .semantics { contentDescription = label },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = null,
        )
    }
}
