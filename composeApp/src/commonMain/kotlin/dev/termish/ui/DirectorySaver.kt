package dev.termish.ui

import androidx.compose.runtime.Composable

/** Directory save target: Write to files using relative paths (automatically create parent directories); click [close] when finished. */
interface DirectorySink {
    /** Opens or creates the file at [relativePath] and returns a stream writer; must call `close()` after writing is complete. */
    fun openFile(relativePath: String): FileSink

    /**
     * Called after all files have been written (finalizes the SAF-written directory).
     * Call only when all files succeed; never on failure paths (to avoid exporting a half-finished directory).
     */
    fun close()
}

/** Directory name sanitization: strip path separators and dangerous characters; empty falls back to “download”. Save-directory naming is unified across all three platforms. */
internal fun sanitizeDirName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "download" }

/**
 * Platform Directory Saver: A function that returns "Select a save location and start writing to the directory" (with the directory name as the input parameter).
 * When the user selects a target, the [onReady] callback is triggered; if the selection is canceled, the callback is not triggered.
 */
@Composable
expect fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit
