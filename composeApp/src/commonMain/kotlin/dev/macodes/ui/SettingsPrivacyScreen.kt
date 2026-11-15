package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import dev.macodes.ui.theme.Spacing

/** Offline version of the app’s privacy policy; the online version links to the Google Play Store. */
@Composable
fun SettingsPrivacyScreen(onBack: () -> Unit) {
    val s = LocalAppStrings.current
    val p = s.permissions
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MacodesHeader(
            title = p.privacyTitle,
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg, vertical = Spacing.Sm),
        ) {
            Text(
                p.privacySummary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.Xl))
            PrivacySection(p.privacyLocalTitle, p.privacyLocalBody)
            PrivacySection(p.privacyConnectionsTitle, p.privacyConnectionsBody)
            PrivacySection(p.privacyVoiceTitle, p.privacyVoiceBody)
            PrivacySection(p.privacyAgentsTitle, p.privacyAgentsBody)
            PrivacySection(p.privacyDiagnosticsTitle, p.privacyDiagnosticsBody)
            PrivacySection(p.privacyControlTitle, p.privacyControlBody)
            PrivacySection(p.privacyDeletionTitle, p.privacyDeletionBody)
            Text(
                p.privacyUpdated,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) }) {
                Text(p.privacyOpenWeb)
            }
            Spacer(Modifier.height(Spacing.Xl))
        }
    }
}

@Composable
private fun PrivacySection(
    title: String,
    body: String,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(Spacing.Sm))
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.Xl))
}
