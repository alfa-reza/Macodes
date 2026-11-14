package dev.macodes.ui

import androidx.compose.runtime.Composable

/** File saving target: Write to the file in a streaming manner during the download process; be sure to [close] the file after writing is complete. */
interface FileSink {
    fun write(bytes: ByteArray)

    fun close()

    /** The entry point for opening the file after download (for Android, use the MediaStore content:// URI; for other platforms, use null). */
    val openUri: String? get() = null
}

/**
 * Platform File Saver: returns a function that “selects a save location and starts writing” (arg: suggested file name).
 * When the user selects a target, the [onReady] callback is triggered, carrying a writable [FileSink]; if the user cancels, the callback is not triggered.
 * After a download completes or there is a failure, the caller is responsible for calling [FileSink.close].
 */
@Composable
expect fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit
