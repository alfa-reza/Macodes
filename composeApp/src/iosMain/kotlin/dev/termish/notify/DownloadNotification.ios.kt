package dev.termish.notify

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/** iOS: Local notification upon download completion (ignore `openUri`—iOS downloads are saved via the Files app and do not use `content://` to open). */
actual fun showDownloadDoneNotification(
    title: String,
    body: String,
    openUri: String?,
) {
    val content = UNMutableNotificationContent()
    content.setTitle(title)
    content.setBody(body)
    val request =
        UNNotificationRequest.requestWithIdentifier(
            "termish-download-${title.hashCode()}-${body.hashCode()}",
            content,
            null,
        )
    UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
}
