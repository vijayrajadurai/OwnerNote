package com.shopai.app.ui.legal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shopai.app.R
import com.shopai.app.ui.components.DetailScaffold
import com.shopai.app.ui.theme.ShopAiTheme

class PrivacyPolicyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val terms = intent.getBooleanExtra(EXTRA_TERMS, false)
        setContent {
            ShopAiTheme {
                LegalDocumentScreen(
                    title = stringResource(
                        if (terms) R.string.terms_and_conditions else R.string.privacy_policy,
                    ),
                    body = stringResource(
                        if (terms) R.string.terms_full else R.string.privacy_policy_full,
                    ),
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TERMS = "show_terms"

        fun openPrivacy(context: Context) = context.startActivity(intent(context, terms = false))

        fun openTerms(context: Context) = context.startActivity(intent(context, terms = true))

        private fun intent(context: Context, terms: Boolean) =
            Intent(context, PrivacyPolicyActivity::class.java).putExtra(EXTRA_TERMS, terms)
    }
}

@Composable
private fun LegalDocumentScreen(
    title: String,
    body: String,
    onBack: () -> Unit,
) {
    DetailScaffold(title = title, onBack = onBack) { contentModifier ->
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = contentModifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}
