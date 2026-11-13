package dev.termish.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingForUploading
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read
import platform.posix.stat

private const val CHUNK = 64 * 1024

/**
 * iOS: UIDocumentPicker (import mode) file selection and upload—alignment with Android’s
 * OpenDocument stream reading: URLs with security scopes are first copied to a temporary directory via NSFileCoordinator,
 * Then read in blocks using POSIX file descriptors. Peak memory usage = 64 KB per block; files of any size are not loaded entirely into memory;
 * When `readChunk` reaches EOF, close the file descriptor; temporary files are cleaned up by the system as needed (NSTemporaryDirectory).
 *
 * The copy + open-fd + onPicked callback all complete inside the coordinating callback (data is ready as soon as the callback returns,
 * (does not depend on the sync/async dialects of `coordinateReading`); `onPicked` dispatches an upload coroutine,
 * The `readChunk` function will then pull the data block by block.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit =
    {
        val picker =
            UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeData),
            )
        picker.allowsMultipleSelection = true
        picker.delegate =
            object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(
                    controller: UIDocumentPickerViewController,
                    didPickDocumentsAtURLs: List<*>,
                ) {
                    // Multiple choices: Independent security scopes for each URL + coordinated copying + fd streaming, with callbacks for each file
                    didPickDocumentsAtURLs.filterIsInstance<NSURL>().forEach { url ->
                        val name = url.lastPathComponent ?: "file"
                        val coordinator = NSFileCoordinator()
                        // Security scope URL: The URL returned by the document picker must first have access permissions granted,
                        // Then copied via NSFileCoordinator (under the “reading-for-uploading” semantics)
                        // (Files that are in use can also be read); access rights and copies are released in pairs upon completion.
                        val scoped = url.startAccessingSecurityScopedResource()
                        try {
                            coordinator.coordinateReadingItemAtURL(
                                url,
                                NSFileCoordinatorReadingForUploading,
                                null,
                            ) { newUrl ->
                                val src = newUrl ?: return@coordinateReadingItemAtURL
                                val tmp = NSTemporaryDirectory() ?: return@coordinateReadingItemAtURL
                                val dst = "${tmp}termish-upload-$name"
                                NSFileManager.defaultManager.removeItemAtPath(dst, null)
                                if (!NSFileManager.defaultManager.copyItemAtURL(
                                        src,
                                        NSURL.fileURLWithPath(dst),
                                        null,
                                    )
                                ) {
                                    return@coordinateReadingItemAtURL
                                }
                                var size = 0L
                                memScoped {
                                    val st = alloc<stat>()
                                    if (stat(dst, st.ptr) == 0) size = st.st_size
                                }

                                fun openReader(): PickedFileReader? {
                                    val fd = open(dst, O_RDONLY)
                                    if (fd < 0) return null
                                    var fileOpen = true
                                    val closeFile = {
                                        if (fileOpen) {
                                            fileOpen = false
                                            close(fd)
                                        }
                                    }
                                    return PickedFileReader(
                                        readChunk = {
                                            val buf = ByteArray(CHUNK)
                                            val n =
                                                if (fileOpen) {
                                                    buf.usePinned { pinned ->
                                                        read(fd, pinned.addressOf(0), CHUNK.toULong())
                                                    }
                                                } else {
                                                    0L
                                                }
                                            if (n <= 0L) {
                                                closeFile()
                                                null
                                            } else {
                                                buf.copyOf(n.toInt())
                                            }
                                        },
                                        close = closeFile,
                                    )
                                }
                                val reader = openReader() ?: return@coordinateReadingItemAtURL
                                onPicked(
                                    PickedFile(
                                        sourceId = url.absoluteString ?: url.path.orEmpty(),
                                        name = name,
                                        size = size,
                                        readChunk = reader.readChunk,
                                        close = reader.close,
                                        openReader = ::openReader,
                                    ),
                                )
                            }
                        } finally {
                            if (scoped) url.stopAccessingSecurityScopedResource()
                        }
                    }
                }
            }
        dispatch_async(dispatch_get_main_queue()) {
            topViewController()?.presentViewController(picker, animated = true, completion = null)
        }
    }

/** Top-level VC (same logic as FileSaver.ios.kt; keep it separate to avoid cross-file private visibility). */
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
