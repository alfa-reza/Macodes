package dev.macodes.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.macodes.notify.NotificationEvent
import dev.macodes.notify.NotificationPermissionController
import dev.macodes.notify.NotificationPermissionState
import dev.macodes.notify.openNotificationSettings
import dev.macodes.ui.theme.Corners
import dev.macodes.ui.theme.Sizes
import dev.macodes.ui.theme.Spacing

/**
 * Notification Settings subpage: master switch + per-event switches + system notification settings entry.
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
        MacodesHeader(
            title = s.settingsNotificationsTitle,
            onBack = onBack,
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(Spacing.Sm))
            Card(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.Lg),
                shape = RoundedCornerShape(Corners.Lg),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(Sizes.BorderThin, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column {
                    SettingsSwitchItem(
                        title = s.settingsNotificationsEnabled,
                        checked = effectiveEnabled,
                        onCheckedChange = ::setEnabled,
                    )
                    SettingsOptionItem(
                        title = s.settingsNotificationsSystem,
                        value =
                            when (permissionState) {
                                NotificationPermissionState.GRANTED -> s.permissions.notificationGranted
                                NotificationPermissionState.DENIED -> s.permissions.notificationDenied
                                NotificationPermissionState.UNKNOWN -> s.permissions.notificationUnknown
                            },
                        onClick = ::openNotificationSettings,
                    )
                }
            }

            SettingsGroup(s.settingsNotificationsEvents) {
                NotificationEvent.entries.forEach { event ->
                    SettingsSwitchItem(
                        title = eventLabel(event, s),
                        checked = event.id !in disabledEvents,
                        onCheckedChange = { onChangeEvent(event.id, !it) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.Xl))
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
