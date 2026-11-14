package dev.macodes.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile

/** Android: In SAF OpenDocumentTree, select the target directory, create a <REMOTE directory name> subdirectory within the tree, and then write to it. */
@Composable
actual fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit {
    val context = LocalContext.current
    // Remember the name of the remote directory saved this time: After the user selects the target directory, create a subdirectory with the same name under it.
    // (OpenDocumentTree cannot preset directory names; it can only retrieve the user’s selection within the callback.)
    var pendingName by remember { mutableStateOf("download") }
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: Exception) {
                }
                val root = DocumentFile.fromTreeUri(context, uri)
                val name = sanitizeDirName(pendingName)
                if (root != null) {
                    onReady(
                        name,
                        object : DirectorySink {
                            /** This download creates a root directory named <REMOTE directory name> in the user-specified directory (if a directory with the same name already exists, a number is appended). */
                            var createdRoot: DocumentFile? = null

                            override fun openFile(relativePath: String): FileSink {
                                val parts = relativePath.split('/')
                                val base = root ?: throw IllegalStateException("保存目录不可用")
                                val rootDir =
                                    createdRoot ?: run {
                                        var candidate = name
                                        var n = 1
                                        while (base.findFile(candidate) != null) {
                                            candidate = "${name}_$n"
                                            n++
                                        }
                                        val d =
                                            base.createDirectory(candidate)
                                                ?: throw IllegalStateException("无法创建目录: $candidate")
                                        createdRoot = d
                                        d
                                    }
                                var dir = rootDir
                                for (p in parts.dropLast(1)) {
                                    dir = dir.findFile(p) ?: dir.createDirectory(p)
                                        ?: throw IllegalStateException("无法创建目录: $p")
                                }
                                val file =
                                    dir.createFile("application/octet-stream", parts.last())
                                        ?: throw IllegalStateException("无法创建文件: ${parts.last()}")
                                val out =
                                    context.contentResolver.openOutputStream(file.uri)
                                        ?: throw IllegalStateException("无法写入: ${parts.last()}")
                                return object : FileSink {
                                    override fun write(bytes: ByteArray) = out.write(bytes)

                                    override fun close() {
                                        try {
                                            out.close()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }

                            override fun close() {}
                        },
                    )
                }
            }
        }
    return { name ->
        pendingName = name
        launcher.launch(null)
    }
}
