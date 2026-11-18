package dev.macodes.util

import dev.macodes.AppContext
import java.io.File

actual fun getAppCacheStats(): AppCacheStats =
    runCatching {
        val context = AppContext.get()
        val cacheBytes = directorySize(context.cacheDir)
        val logBytes = logFileDirectory()?.let { directorySize(File(it)) } ?: 0L
        AppCacheStats(cacheBytes = cacheBytes, diagnosticLogBytes = logBytes)
    }.getOrDefault(AppCacheStats())

actual fun clearAppCache(): Boolean =
    runCatching {
        val context = AppContext.get()
        val cacheCleared = deleteChildren(context.cacheDir)
        val logsCleared =
            logFileDirectory()?.let { dir -> deleteChildren(File(dir)) } ?: true
        cacheCleared && logsCleared
    }.getOrDefault(false)

private fun directorySize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    if (dir.isFile) return dir.length()
    val children = dir.listFiles() ?: return 0L
    var total = 0L
    for (child in children) {
        total += directorySize(child)
    }
    return total
}

private fun deleteChildren(dir: File?): Boolean {
    if (dir == null || !dir.exists()) return true
    val children = dir.listFiles() ?: return true
    var ok = true
    for (child in children) {
        if (!child.deleteRecursively()) {
            ok = false
        }
    }
    return ok
}
