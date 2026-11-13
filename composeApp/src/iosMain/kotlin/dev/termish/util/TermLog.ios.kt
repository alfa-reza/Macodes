@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.termish.util

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/** iOS: Kotlin/Native debug build symbols (automatically disabled in release builds). */
actual val termLogEnabled: Boolean = kotlin.native.Platform.isDebugBinary

actual fun platformLog(
    level: Char,
    tag: String,
    msg: String,
) {
    // K/N’s NSLog vararg does not bridge Kotlin Strings to NSStrings: %@ will access the internal data pointer of the string and cause a crash.
    // Use %s + C character pointers to avoid Objective-C object bridging.
    memScoped {
        NSLog("[%s/%s] %s", level.toString().cstr.ptr, tag.cstr.ptr, msg.cstr.ptr)
    }
}

/** Log directory: Documents/logs (visible in iTunes File Sharing for easy export). */
actual fun logFileDirectory(): String? {
    val docs =
        NSFileManager.defaultManager
            .URLsForDirectory(
                NSDocumentDirectory,
                NSUserDomainMask,
            ).firstOrNull() as? NSURL
    val dir = docs?.path + "/logs"
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    return dir
}

/** iOS: UIActivityViewController for sharing log files. */
actual fun shareDiagnosticLogs() {
    val dir = logFileDirectory() ?: return
    val url = NSURL.fileURLWithPath("$dir/termish.log")
    val vc = UIActivityViewController(listOf(url), null)
    UIApplication.sharedApplication.keyWindow
        ?.rootViewController
        ?.presentViewController(vc, true, null)
}
