package dev.macodes.notify

/**
 * Download complete notification (session event notification independent of [NotificationCenter]: **also sent to the foreground**,
 * (Because it serves as the entry point for “opening a file from the system download directory,” rather than a background event notification.)
 *
 * @param openUri On Android, this is a MediaStore content:// URI (tap it to open the item in the default app);
 *  pass `null` to leave the action unbound (files saved via SAF need no open action).
 */
expect fun showDownloadDoneNotification(
    title: String,
    body: String,
    openUri: String?,
)
