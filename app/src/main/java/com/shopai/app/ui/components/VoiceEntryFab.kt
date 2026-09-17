package com.shopai.app.ui.components

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .padding(end = 16.dp, bottom = bottomPadding)
            .navigationBarsPadding()
            .testTag(VOICE_ENTRY_FAB_TEST_TAG)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
            )
        },
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}
