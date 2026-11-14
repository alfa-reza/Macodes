package dev.macodes.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import dev.macodes.AppContext
import dev.macodes.app.R
import dev.macodes.ui.guessMimeType

private const val CHANNEL_ID = "macodes_download"

/**
 * Android: Download complete notification (also sent in the foreground). When [openUri] is not empty, tap to open the file with the system’s default app.
 * Silently dismiss unauthorized notifications on Android 13+ (does not cause conflict with in-app progress bars or snackbars).
 */
actual fun showDownloadDoneNotification(
    title: String,
    body: String,
    openUri: String?,
) {
    val context = AppContext.get()
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "下载", NotificationManager.IMPORTANCE_DEFAULT),
    )
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setColor(0xFF34D399.toInt()) // Brand: emerald

    if (openUri != null) {
        val open =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(openUri.toUri(), guessMimeType(body))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val contentIntent =
            PendingIntent.getActivity(
                context,
                openUri.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        builder.setContentIntent(contentIntent)
    }

    nm.notify(openUri?.hashCode() ?: body.hashCode(), builder.build())
}
