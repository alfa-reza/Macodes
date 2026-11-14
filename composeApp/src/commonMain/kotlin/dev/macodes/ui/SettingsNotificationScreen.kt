package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.macodes.notify.NotificationEvent
import dev.macodes.notify.NotificationPermissionController
import dev.macodes.notify.NotificationPermissionState
import dev.macodes.notify.openNotificationSettings
import dev.macodes.util.monospaceFontFamily

/**
 * Notification Settings subpage: master switch + per-event switches (managed centrally; business code posts via
 * (NotificationCenter.post call) + entry point for system notification settings.
 */
@Composable
fun SettingsNotificationScreen(
    enabled: Boolean,
    disabledEvents: Set<String>,
    permissionState: NotificationPermissionState,
    permissionController: NotificationPermissionController,
    onPermissionStateChange: (NotificationPermissionState) -> Unit,
    onChangeEnabled: (Boolean) -> Unit,
    onChangeEvent: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    val effectiveEnabled = enabled && permissionState == NotificationPermissionState.GRANTED

    fun setEnabled(next: Boolean) {
        if (!next) {
            onChangeEnabled(false)
        } else if (permissionState == NotificationPermissionState.GRANTED) {
            onChangeEnabled(true)
        } else {
            permissionController.request { state ->
                onPermissionStateChange(state)
                onChangeEnabled(state == NotificationPermissionState.GRANTED)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.settingsNotificationsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Master switch: When enabled, requests system notification permissions (Android 13+)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        setEnabled(!effectiveEnabled)
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.settingsNotificationsEnabled,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = effectiveEnabled,
                    onCheckedChange = ::setEnabled,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Text(
                s.settingsNotificationsEvents,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            // Event-level switches: uniformly read by NotificationCenter
            NotificationEvent.entries.forEachIndexed { i, event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onChangeEvent(event.id, event.id in disabledEvents)
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        eventLabel(event, s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = event.id !in disabledEvents,
                        onCheckedChange = { onChangeEvent(event.id, !it) },
                    )
                }
                if (i < NotificationEvent.entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }

            // System Notification Settings (Fallback when permissions are denied)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { openNotificationSettings() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.settingsNotificationsSystem,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when (permissionState) {
                            NotificationPermissionState.GRANTED -> s.permissions.notificationGranted
                            NotificationPermissionState.DENIED -> s.permissions.notificationDenied
                            NotificationPermissionState.UNKNOWN -> s.permissions.notificationUnknown
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

private fun eventLabel(
    event: NotificationEvent,
    s: AppStrings,
): String =
    when (event) {
        NotificationEvent.CONNECTION_LOST -> s.notificationEventConnectionLost
        NotificationEvent.RECONNECT_FAILED -> s.notificationEventReconnectFailed
        NotificationEvent.TRANSFER_DONE -> s.notificationEventTransferDone
        NotificationEvent.AGENT_TASK -> s.notificationEventAgentTask
    }
