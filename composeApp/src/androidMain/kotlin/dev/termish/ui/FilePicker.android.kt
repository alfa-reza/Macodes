package dev.termish.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private const val CHUNK = 64 * 1024

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit {
    val context = LocalContext.current
    // OpenMultipleDocuments: Perform a selection of multiple files at once (each URI is read as an independent stream)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            uris.forEach { uri ->
                val name = queryName(context, uri) ?: "file"

                // ContentResolver streaming read: selector callbacks only wrap streams; readChunk pulls data blocks one at a time,
                // Peak memory usage for files of any size = 64KB (previously, `readBytes()` loaded the full file into heap memory, causing OOM for large files)
                fun openReader(): PickedFileReader? =
                    context.contentResolver.openInputStream(uri)?.let { stream ->
                        PickedFileReader(
                            readChunk = {
                                val buf = ByteArray(CHUNK)
                                val n = stream.read(buf)
                                if (n < 0) {
                                    stream.close()
                                    null
                                } else {
                                    buf.copyOf(n)
                                }
                            },
                            close = { runCatching { stream.close() } },
                        )
                    }
                val reader = openReader()
                if (reader != null) {
                    val size = querySize(context, uri)
                    onPicked(
                        PickedFile(
                            sourceId = uri.toString(),
                            name = name,
                            size = size,
                            readChunk = reader.readChunk,
                            close = reader.close,
                            openReader = ::openReader,
                        ),
                    )
                }
            }
        }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun queryName(
    context: Context,
    uri: Uri,
): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

private fun querySize(
    context: Context,
    uri: Uri,
): Long =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst()) {
            if (!cursor.isNull(idx)) cursor.getLong(idx).coerceAtLeast(0L) else 0L
        } else {
            0L
        }
    } ?: 0L
