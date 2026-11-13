package dev.termish.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android: Download directly to the public Download directory (MediaStore.Downloads, Android 10+),
 * Do not display the “Save As” dialog box—the mobile convention is “Download directly to the Downloads directory.” Files with the same name are handled by
 * MediaStore automatic deduplication (name (1).ext); IS_PENDING placeholder until completed.
 * For Android 9 and earlier (without scoped storage), fall back to SAF’s “Save As” functionality.
 */
@Composable
actual fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit {
    val context = LocalContext.current
    // API < 29 Fallback: SAF CreateDocument allows users to select a save location
    val safLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            if (uri != null) {
                val out = context.contentResolver.openOutputStream(uri)
                if (out != null) {
                    onReady(
                        queryName(context, uri) ?: "file",
                        object : FileSink {
                            override fun write(bytes: ByteArray) = out.write(bytes)

                            override val openUri: String? = uri.toString()

                            override fun close() {
                                try {
                                    out.close()
                                } catch (_: Exception) {
                                }
                            }
                        },
                    )
                }
            }
        }
    return { name ->
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = insertDownload(context, name)
            if (uri != null) {
                val out = context.contentResolver.openOutputStream(uri)
                if (out != null) {
                    // Use the actual file name (for example, “MediaStore” may become “name (1).ext” after automatic deduplication of duplicate files).
                    val actualName = queryName(context, uri) ?: name
                    onReady(
                        actualName,
                        object : FileSink {
                            override fun write(bytes: ByteArray) = out.write(bytes)

                            override val openUri: String? = uri.toString()

                            override fun close() {
                                try {
                                    out.close()
                                } catch (_: Exception) {
                                }
                                // Clear IS_PENDING; mark the file as visible and completed.
                                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                                context.contentResolver.update(uri, done, null, null)
                            }
                        },
                    )
                }
            }
        } else {
            safLauncher.launch(name)
        }
    }
}

/** MediaStore.Downloads insertion (IS_PENDING=1 placeholder); MediaStore automatically removes duplicates with the same name. */
@RequiresApi(29)
private fun insertDownload(
    context: Context,
    name: String,
): Uri? {
    val values =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, guessMimeType(name))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    return context.contentResolver.insert(
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        values,
    )
}

private fun queryName(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

/** Infer the MIME type based on the file extension; fall back to `application/octet-stream` for unknown types. */
internal fun guessMimeType(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    // An APK is essentially a ZIP file; on some devices, the `MimeTypeMap` may misidentify it or return `null`: Explicitly specify the APK MIME type
    if (ext == "apk") return "application/vnd.android.package-archive"
    if (ext.isNotEmpty()) {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
    }
    return "application/octet-stream"
}
