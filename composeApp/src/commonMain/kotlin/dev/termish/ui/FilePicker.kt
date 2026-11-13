package dev.termish.ui

import androidx.compose.runtime.Composable

/** Streaming read handle for the selected file: Each call to [readChunk] returns the next block; null indicates EOF. */
data class PickedFile(
    /** Platform stability identifier (Android URI / iOS URL), used for deduplication across batches. */
    val sourceId: String,
    val name: String,
    val size: Long,
    val readChunk: () -> ByteArray?,
    /** Proactively release the underlying file handle before reaching EOF (canceled, removed, or failure). Repeated calls are allowed. */
    val close: () -> Unit,
    /** Reopen a separate reader for retryable uploads; fall back to a one-shot reader when the platform has no implementation. */
    val openReader: (() -> PickedFileReader?)? = null,
)

data class PickedFileReader(
    val readChunk: () -> ByteArray?,
    val close: () -> Unit,
)

/** Append attachments and deduplicate based on platform file identifiers; immediately release new handles that are rejected. */
internal fun appendUniquePickedFile(
    files: List<PickedFile>,
    candidate: PickedFile,
): List<PickedFile> {
    if (files.any { it.sourceId == candidate.sourceId }) {
        candidate.close()
        return files
    }
    return files + candidate
}

/** Remove attachments and release their underlying file handles. */
internal fun removePickedFile(
    files: List<PickedFile>,
    removed: PickedFile,
): List<PickedFile> {
    removed.close()
    return files.filterNot { it.sourceId == removed.sourceId }
}

/** Release file handles for unread files before clearing attachments. */
internal fun closePickedFiles(files: List<PickedFile>) {
    files.forEach { it.close() }
}

/**
 * Platform file selector (**multiple selection**): Returns a function that "opens the selector"; after selection, **each file
 * Callback once **[onPicked]** (name + total size + block retrieval function—streamed uploads of large files do not keep the entire file in memory).
 * Android SAF (OpenMultipleDocuments) / iOS UIDocumentPicker (multiple selection).
 */
@Composable
expect fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit
