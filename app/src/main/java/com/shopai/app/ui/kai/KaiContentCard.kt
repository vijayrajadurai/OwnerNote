package com.shopai.app.ui.kai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shopai.app.ui.theme.Background
import com.shopai.app.ui.theme.Border
import com.shopai.app.ui.theme.Surface
import com.shopai.app.ui.theme.TextPrimary

/**
 * Light-theme card for KAI artwork and the copy around it.
 * Always uses the default (light) surface — never follows dark mode.
 */
@Composable
fun KaiContentCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Surface,
            contentColor = TextPrimary,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Background)
                .padding(contentPadding),
            content = content,
        )
    }
}
