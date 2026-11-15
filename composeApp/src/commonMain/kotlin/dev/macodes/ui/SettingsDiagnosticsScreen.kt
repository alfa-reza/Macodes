package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Spacing
import dev.macodes.util.TermLog
import dev.macodes.util.monospaceFontFamily
import dev.macodes.util.shareDiagnosticLogs

/**
 * Diagnostics Settings subpage: Diagnostics log switch + Export and Share.
 */
@Composable
fun SettingsDiagnosticsScreen(
    enabled: Boolean,
    onChangeEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var logEnabled by remember { mutableStateOf(enabled) }

    fun setEnabled(value: Boolean) {
        logEnabled = value
        onChangeEnabled(value)
    }
    val logPath = TermLog.logFilePath
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MacodesHeader(
            title = s.settingsDiagnosticsTitle,
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Lg),
        ) {
            Spacer(Modifier.height(Spacing.Sm))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    SettingsSwitchItem(
                        title = s.settingsDiagnosticsEnabled,
                        subtitle = s.settingsDiagnosticsHint,
                        checked = logEnabled,
                        onCheckedChange = ::setEnabled,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Lg))
            Button(
                onClick = { shareDiagnosticLogs() },
                enabled = logEnabled && logPath != null,
                shape = RoundedCornerShape(Corners.Md),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(s.settingsDiagnosticsExport)
            }
            if (logPath != null) {
                Text(
                    logPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monospaceFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.Sm),
                )
            }
        }
    }
}
