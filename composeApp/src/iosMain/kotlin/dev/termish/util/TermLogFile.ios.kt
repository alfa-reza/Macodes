@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.termish.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/** iOS: NSFileManager + POSIX append (java.io.File is not available in commonMain). */
internal actual class TermLogFile actual constructor(
    private val path: String,
) {
    actual fun exists(): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun length(): Long =
        (
            NSFileManager.defaultManager
                .attributesOfItemAtPath(path, null)
                ?.get(NSFileSize) as? NSNumber
        )?.longLongValue ?: 0L

    actual fun delete() {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    actual fun renameTo(target: String): Boolean = NSFileManager.defaultManager.moveItemAtPath(path, toPath = target, error = null)

    actual fun appendText(text: String) {
        // "ab": Append + create if does not exist; fwrite writes in one go (log rows are short, so no blocking is needed)
        val fd = fopen(path, "ab") ?: return
        try {
            val bytes = text.encodeToByteArray()
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fd)
            }
        } finally {
            fclose(fd)
        }
    }

    actual fun absolutePath(): String = path
}
