package dev.macodes.util

/** Snapshot of local app cache and diagnostic log file sizes in bytes. */
data class AppCacheStats(
    val cacheBytes: Long = 0L,
    val diagnosticLogBytes: Long = 0L,
) {
    val totalBytes: Long get() = cacheBytes + diagnosticLogBytes
}

expect fun getAppCacheStats(): AppCacheStats

expect fun clearAppCache(): Boolean

/** Formats a byte count into a compact human-readable label (B / KB / MB). */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "${((kb * 10).toLong() / 10.0)} KB"
    val mb = kb / 1024.0
    return "${((mb * 10).toLong() / 10.0)} MB"
}
