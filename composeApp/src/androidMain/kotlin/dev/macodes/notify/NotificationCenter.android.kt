package dev.macodes.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.app.NotificationCompat
import dev.macodes.AppContext
import dev.macodes.MainActivity
import dev.macodes.app.R

private const val CHANNEL_ID = "macodes_session"
private const val EXTRA_HOST_ID = "macodes_host_id"

/** Android: System notifications (foreground service kept alive; notification permissions require runtime requests on Android 13 and later). */
actual fun showPlatformNotification(
    id: Int,
    title: String,
    body: String,
    hostId: String?,
) {
    val context = AppContext.get()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "会话事件", NotificationManager.IMPORTANCE_DEFAULT),
    )
    // Android 13+: Silently fall back if authorization is not granted (a switch on the settings page will prompt for authorization)
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    // Tap notification: Open the app; when hostId is included, trigger the “Reconnect” action
    val openIntent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val contentIntent =
        PendingIntent.getActivity(
            context,
            id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setColor(0xFF34D399.toInt()) // Brand: emerald
            .setContentIntent(contentIntent)
    if (hostId != null) {
        val reconnectIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_HOST_ID, hostId)
            }
        val reconnectPending =
            PendingIntent.getActivity(
                context,
                id + 1000,
                reconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        builder.addAction(0, "重新连接", reconnectPending)
    }
    nm.notify(id, builder.build())
}

/** Navigate to the system app notification settings page (all supported on minSdk 26 and above). */
actual fun openNotificationSettings() {
    val context = AppContext.get()
    val intent =
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private class NotificationPermissionCallback {
    var onResult: ((NotificationPermissionState) -> Unit)? = null
}

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pending = remember { NotificationPermissionCallback() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending.onResult?.invoke(
                if (granted) NotificationPermissionState.GRANTED else NotificationPermissionState.DENIED,
            )
            pending.onResult = null
        }
    return remember(context, launcher) {
        object : NotificationPermissionController {
            override fun refresh(onResult: (NotificationPermissionState) -> Unit) {
                val granted =
                    Build.VERSION.SDK_INT < 33 ||
                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                onResult(
                    if (granted) NotificationPermissionState.GRANTED else NotificationPermissionState.DENIED,
                )
            }

            override fun request(onResult: (NotificationPermissionState) -> Unit) {
                if (Build.VERSION.SDK_INT < 33) {
                    onResult(NotificationPermissionState.GRANTED)
                    return
                }
                pending.onResult = onResult
                launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** Notification action forwarding: “Reconnect” notification → MainActivity.onNewIntent → here → NotificationCenter callback. */
fun handleNotificationIntent(intent: Intent?) {
    val hostId = intent?.getStringExtra(EXTRA_HOST_ID) ?: return
    NotificationCenter.onReconnectRequest?.invoke(hostId)
}
