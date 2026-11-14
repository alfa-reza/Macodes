package dev.termish.notify

import androidx.compose.runtime.Composable
import dev.termish.data.AppSettings
import dev.termish.util.TermLog
import kotlin.concurrent.Volatile

/**
 * List of notification events. Each event has its own switch (can be turned off on the “Notifications” subpage of the Settings page),
 * Distributed uniformly via [NotificationCenter]—business teams only call `post` and do not interact with the platform API.
 */
enum class NotificationEvent(
    val id: String,
) {
    /** Unexpected disconnection (in the background). */
    CONNECTION_LOST("connection_lost"),

    /** Automatic reconnection still leads to failure after exhaustion. */
    RECONNECT_FAILED("reconnect_failed"),

    /** SFTP transfer complete (reserved). */
    TRANSFER_DONE("transfer_done"),

    /** Agent task completed (reserved: herdr task notification). */
    AGENT_TASK("agent_task"),
}

/**
 * Unified Notification Center: master switch + event switches + foreground/background filtering + platform dispatch.
 * When the app launches, AppRoot performs injection of the settings reader and the foreground and background states.
 */
object NotificationCenter {
    /** Injection during launch (reads the latest AppSettings). */
    var settingsProvider: (() -> AppSettings)? = null

    /** Foreground and background state (AppRoot maintained via `observeAppLifecycle`): Do not display notifications on the foreground (UI banner is present). */
    @Volatile
    var foreground: Boolean = true

    /** Notification action callback (registered in AppRoot): When the “Reconnect” action is triggered, reconnect the session based on the hostId. */
    var onReconnectRequest: ((hostId: String) -> Unit)? = null

    /** Send notifications: after the master switch/event switches/foreground filtering pass, hand off to the platform implementation. */
    fun post(
        event: NotificationEvent,
        title: String,
        body: String,
        id: Int = event.ordinal,
        /** Action button: When not empty, the platform notification includes a “Reconnect” action (tapping it reconnects based on the hostId). */
        hostId: String? = null,
    ) {
        if (foreground) {
            TermLog.d("notify") { "skip ${event.id}: foreground" }
            return
        }
        val s = settingsProvider?.invoke() ?: return
        if (!s.notificationEnabled) {
            TermLog.d("notify") { "skip ${event.id}: disabled" }
            return
        }
        if (event.id in s.notificationDisabledEvents) {
            TermLog.d("notify") { "skip ${event.id}: event off" }
            return
        }
        TermLog.i("notify") { "post ${event.id}: $body" }
        runCatching { showPlatformNotification(id, title, body, hostId) }
    }
}

/** Platform implementation: Android NotificationManager. */
expect fun showPlatformNotification(
    id: Int,
    title: String,
    body: String,
    hostId: String?,
)

/** Open the system notification settings page (accessible via the “Notifications” entry in the Settings page). */
expect fun openNotificationSettings()

enum class NotificationPermissionState {
    UNKNOWN,
    GRANTED,
    DENIED,
}

/** Notification permission controller: supports async query, request, and status refresh after returning from system settings. */
interface NotificationPermissionController {
    fun refresh(onResult: (NotificationPermissionState) -> Unit)

    fun request(onResult: (NotificationPermissionState) -> Unit)
}

@Composable
expect fun rememberNotificationPermissionController(): NotificationPermissionController
