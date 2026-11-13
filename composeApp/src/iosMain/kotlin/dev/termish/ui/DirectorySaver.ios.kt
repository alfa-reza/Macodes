package dev.termish.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write

/**
 * iOS: Recursively download to a temporary directory; use UIDocumentPicker when calling [DirectorySink.close]
 * Export the entire directory (users can save it to Files or other apps).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit {
    return { name ->
        val tmpDir = NSTemporaryDirectory()
        if (tmpDir != null) {
            val safeName = sanitizeDirName(name)
            val root = "$tmpDir/termish-dir-$safeName"
            // Delete any directories with the same name left over from the previous failure or interruption of the export to prevent obsolete files from being included in this export (O_TRUNC only overwrites files with the same name).
            NSFileManager.defaultManager.removeItemAtPath(root, null)
            NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
            onReady(
                name,
                object : DirectorySink {
                    override fun openFile(relativePath: String): FileSink {
                        val path = "$root/$relativePath"
                        val parent = path.substringBeforeLast('/')
                        NSFileManager.defaultManager.createDirectoryAtPath(parent, true, null, null)
                        val fd = open(path, O_CREAT or O_WRONLY or O_TRUNC, 0x1A4u)
                        if (fd < 0) throw IllegalStateException("无法写入: $relativePath")
                        return object : FileSink {
                            override fun write(bytes: ByteArray) {
                                bytes.usePinned { pinned ->
                                    var off = 0
                                    while (off < bytes.size) {
                                        val n = write(fd, pinned.addressOf(off), (bytes.size - off).toULong())
                                        // Writing "failure" or 0 bytes must throw an error: silent truncation will cause upper-level code to falsely report success for a download
                                        if (n < 0) throw IllegalStateException("写入失败: $relativePath")
                                        if (n == 0L) throw IllegalStateException("写入中断: $relativePath")
                                        off += n.toInt()
                                    }
                                }
                            }

                            override fun close() {
                                close(fd)
                            }
                        }
                    }

                    override fun close() {
                        val url = NSURL.fileURLWithPath(root)
                        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
                        dispatch_async(dispatch_get_main_queue()) {
                            topViewController()?.presentViewController(picker, animated = true, completion = null)
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    val window =
        UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
            ?.windows
            ?.filterIsInstance<UIWindow>()
            ?.firstOrNull { it.isKeyWindow() }
    return window?.rootViewController
}
