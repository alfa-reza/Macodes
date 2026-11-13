@file:OptIn(ExperimentalForeignApi::class)

package dev.termish.ssh

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import libssh2.LIBSSH2_ERROR_EAGAIN
import libssh2.LIBSSH2_FXF_CREAT
import libssh2.LIBSSH2_FXF_READ
import libssh2.LIBSSH2_FXF_TRUNC
import libssh2.LIBSSH2_FXF_WRITE
import libssh2.LIBSSH2_SFTP
import libssh2.LIBSSH2_SFTP_ATTRIBUTES
import libssh2.LIBSSH2_SFTP_HANDLE
import libssh2.LIBSSH2_SFTP_OPENDIR
import libssh2.LIBSSH2_SFTP_REALPATH
import libssh2.LIBSSH2_SFTP_STAT
import libssh2.LIBSSH2_SFTP_S_IFDIR
import libssh2.libssh2_sftp_close_handle
import libssh2.libssh2_sftp_fstat_ex
import libssh2.libssh2_sftp_mkdir_ex
import libssh2.libssh2_sftp_open_ex
import libssh2.libssh2_sftp_read
import libssh2.libssh2_sftp_readdir_ex
import libssh2.libssh2_sftp_rename_ex
import libssh2.libssh2_sftp_rmdir_ex
import libssh2.libssh2_sftp_seek64
import libssh2.libssh2_sftp_shutdown
import libssh2.libssh2_sftp_stat_ex
import libssh2.libssh2_sftp_symlink_ex
import libssh2.libssh2_sftp_unlink_ex
import platform.posix.usleep
import sftp_write.termish_sftp_write

actual fun createSftpSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SftpSession = SftpSessionLibssh2(connection, callbacks)

/**
 * libssh2 SFTP implementation (iOS): Reuse the authentication chain from [SshSessionLibssh2],
 * Call libssh2_sftp_* in blocking mode.
 */
private class SftpSessionLibssh2(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SftpSession {
    private val ssh = SshSessionLibssh2(connection, callbacks)
    private var sftp: CPointer<LIBSSH2_SFTP>? = null

    private fun sftpOrThrow(): CPointer<LIBSSH2_SFTP> =
        sftp ?: ssh.openSftp()?.also { sftp = it }
            ?: throw SshException("SFTP 通道打开失败")

    override fun openRead(remotePath: String): SftpReadHandle {
        val owner = SftpSessionLibssh2(connection, callbacks.forSftpReader())
        try {
            val handle =
                libssh2_sftp_open_ex(
                    owner.sftpOrThrow(),
                    remotePath,
                    remotePath.encodeToByteArray().size.toUInt(),
                    LIBSSH2_FXF_READ.toULong(),
                    0L,
                    0,
                ) ?: throw SshException("SFTP open failed")
            try {
                val length = owner.fileSize(handle)
                return object : SftpReadHandle {
                    override val size = length
                    private var closed = false

                    override fun read(
                        offset: Long,
                        maxBytes: Int,
                    ): ByteArray {
                        check(!closed) { "SFTP reader closed" }
                        require(offset >= 0 && maxBytes > 0)
                        if (offset >= size) return ByteArray(0)
                        libssh2_sftp_seek64(handle, offset.toULong())
                        return memScoped {
                            val count = minOf(maxBytes.toLong(), 64L * 1024, size - offset).toInt()
                            val buffer = allocArray<ByteVar>(count)
                            var retries = 0
                            while (true) {
                                val n = libssh2_sftp_read(handle, buffer, count.toULong())
                                if (n >= 0) return@memScoped buffer.readBytes(n.toInt())
                                if (n.toInt() != LIBSSH2_ERROR_EAGAIN || retries++ >= 200) {
                                    throw SshException("SFTP read failed")
                                }
                                usleep(30_000u)
                            }
                            @Suppress("UNREACHABLE_CODE")
                            ByteArray(0)
                        }
                    }

                    override fun close() {
                        if (closed) return
                        closed = true
                        try {
                            libssh2_sftp_close_handle(handle)
                        } finally {
                            owner.close()
                        }
                    }
                }
            } catch (e: Exception) {
                libssh2_sftp_close_handle(handle)
                throw e
            }
        } catch (e: Exception) {
            owner.close()
            throw e
        }
    }

    override fun list(path: String): List<SftpEntry> {
        val s = sftpOrThrow()
        // cinterop String parameters are encoded in UTF-8: the length must be specified in bytes (`.length` refers to the number of characters,
        // (Chinese filenames/paths suffer truncation—directories cannot be opened, and there is failure when attempting to download, delete, or create new files.)
        val pathLen = path.encodeToByteArray().size
        val handle =
            memScoped {
                var h: CPointer<LIBSSH2_SFTP_HANDLE>? = null
                var guard = 0
                while (h == null && guard++ < 200) {
                    h = libssh2_sftp_open_ex(s, path, pathLen.toUInt(), 0uL, 0L, LIBSSH2_SFTP_OPENDIR)
                    if (h == null) usleep(30_000u)
                }
                h ?: throw SshException("SFTP 打开目录失败: $path")
            }
        return try {
            val entries = ArrayList<SftpEntry>()
            memScoped {
                val buf = allocArray<ByteVar>(4096)
                val longBuf = allocArray<ByteVar>(512)
                while (true) {
                    val n =
                        libssh2_sftp_readdir_ex(
                            handle,
                            buf,
                            4096uL,
                            longBuf,
                            512uL,
                            null,
                        )
                    when {
                        n > 0 -> {
                            val name = buf.readBytes(n.toInt()).decodeToString()
                            // The `attrs` structure in libssh2 is opaque in cinterop,
                            // Parsed from longentry (drwxr-xr-x 2 root root 4096 Jan 1 12:34 name);
                            // When the server does not return a `longentry` (Windows OpenSSH, some embedded SFTP implementations)
                            // type unknown; use `stat` as the fallback — recursive directory download relies on an accurate `isDirectory`
                            val long = longBuf.toKString()
                            val entry = parseLongEntry(name, long) ?: statEntry(path, name)
                            entries.add(entry)
                        }
                        n == 0 -> return@memScoped
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> usleep(30_000u)
                        else -> throw SshException("SFTP 读取目录失败: $path")
                    }
                }
            }
            entries
        } finally {
            libssh2_sftp_close_handle(handle)
        }
    }

    /**
     * Parse the `longentry` (permissions/size) from `readdir`; if the time is missing, fallback to empty (Android has full attributes).
     * Returns null when the type character cannot be recognized (longentry missing/format exception); [statEntry] provides the fallback.
     */
    private fun parseLongEntry(
        name: String,
        long: String,
    ): SftpEntry? {
        // longentry format: perm links owner group size date… name (tested with OpenSSH).
        // For `size`, take the 5th column from the front: the number of trailing columns varies with spaces in the name/date format
        // (measured: "File.txt with spaces" shifts the fifth-to-last column onto Aug/12:28), so leading columns are the stable ones.
        // If no valid number is obtained, return 0 (this is a display-only field and does not affect the `isDirectory` check).
        val parts = long.trim().split(Regex("\\s+"))
        val perm = parts.firstOrNull() ?: return null
        if (perm.isEmpty() || perm.first() !in "dl-") return null
        val isDir = perm.startsWith("d")
        val size = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        return SftpEntry(
            name = name,
            isDirectory = isDir,
            permissions = perm,
            size = size,
            modifiedAt = 0L,
            isHidden = name.startsWith("."),
        )
    }

    /** Type fallback when `longentry` is missing: `stat` uses the actual type (directory/file/link) and includes size and time. */
    private fun statEntry(
        dirPath: String,
        name: String,
    ): SftpEntry {
        val s = sftpOrThrow()
        val full = if (dirPath == "/") "/$name" else "$dirPath/$name"
        val fullLen = full.encodeToByteArray().size
        memScoped {
            val attrs = alloc<LIBSSH2_SFTP_ATTRIBUTES>()
            var guard = 0
            while (true) {
                val rc = libssh2_sftp_stat_ex(s, full, fullLen.toUInt(), LIBSSH2_SFTP_STAT, attrs.ptr)
                if (rc == 0) {
                    val perm = attrs.permissions
                    return SftpEntry(
                        name = name,
                        isDirectory = perm and LIBSSH2_SFTP_S_IFDIR.toULong() != 0uL,
                        permissions = formatPerm(perm),
                        size = attrs.filesize.toLong(),
                        modifiedAt = attrs.mtime.toLong() * 1000L,
                        isHidden = name.startsWith("."),
                    )
                }
                if (rc == LIBSSH2_ERROR_EAGAIN && guard++ < 200) {
                    usleep(30_000u)
                    continue
                }
                // stat failure: Handle as a regular file (an error will naturally be reported again when downloading or navigating to it), without interrupting the entire list
                return SftpEntry(
                    name = name,
                    isDirectory = false,
                    permissions = "-rw-r--r--",
                    size = 0L,
                    modifiedAt = 0L,
                    isHidden = name.startsWith("."),
                )
            }
        }
    }

    /** Numeric mode → "drwxr-xr-x" permission string (used for the `stat` fallback path, for demonstration purposes). */
    private fun formatPerm(mode: ULong): String {
        val type = if (mode and LIBSSH2_SFTP_S_IFDIR.toULong() != 0uL) 'd' else '-'
        val chars = "rwxrwxrwx"
        val bits = longArrayOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)
        return buildString {
            append(type)
            for (i in 0..8) append(if (mode and bits[i].toULong() != 0uL) chars[i] else '-')
        }
    }

    override fun mkdir(path: String) {
        val s = sftpOrThrow()
        if (libssh2_sftp_mkdir_ex(s, path, path.encodeToByteArray().size.toUInt(), 0x1EDL) != 0) {
            throw SshException("SFTP 创建目录失败: $path")
        }
    }

    override fun delete(path: String) {
        deleteRecursive(sftpOrThrow(), path)
    }

    private fun deleteRecursive(
        s: CPointer<LIBSSH2_SFTP>,
        path: String,
    ) {
        val isDir =
            runCatching { list(path) }.getOrNull()?.let {
                // list success = directory (the list function will raise an error); it may also return an empty list (empty directory)
                true
            } ?: false
        val pathLen = path.encodeToByteArray().size.toUInt()
        if (isDir) {
            // Recursively delete child items (the ., /, and .. characters within the list have already been skipped)
            list(path).forEach { child -> deleteRecursive(s, joinRemote(path, child.name)) }
            if (libssh2_sftp_rmdir_ex(s, path, pathLen) != 0) {
                throw SshException("SFTP 删除目录失败: $path")
            }
        } else {
            if (libssh2_sftp_unlink_ex(s, path, pathLen) != 0) {
                throw SshException("SFTP 删除文件失败: $path")
            }
        }
    }

    override fun rename(
        oldPath: String,
        newPath: String,
    ) {
        val s = sftpOrThrow()
        val rc =
            libssh2_sftp_rename_ex(
                s,
                oldPath,
                oldPath.encodeToByteArray().size.toUInt(),
                newPath,
                newPath.encodeToByteArray().size.toUInt(),
                0,
            )
        if (rc != 0) {
            throw SshException("SFTP 重命名失败: $oldPath")
        }
    }

    override fun home(): String {
        val s = sftpOrThrow()
        memScoped {
            val buf = allocArray<ByteVar>(4096)
            while (true) {
                val n = libssh2_sftp_symlink_ex(s, ".", 1u, buf, 4096u, LIBSSH2_SFTP_REALPATH)
                if (n >= 0) {
                    val path =
                        buf
                            .readBytes(n)
                            .decodeToString()
                            .trimEnd('\u0000')
                            .trimEnd('/')
                            .ifEmpty { "/" }
                    return path
                }
                if (n.toInt() == LIBSSH2_ERROR_EAGAIN) {
                    usleep(30_000u)
                    continue
                }
                throw SshException("SFTP 获取主目录失败")
            }
        }
    }

    override fun upload(
        remotePath: String,
        totalSize: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
        nextChunk: () -> ByteArray?,
    ) {
        val s = sftpOrThrow()
        val flags = (LIBSSH2_FXF_WRITE or LIBSSH2_FXF_CREAT or LIBSSH2_FXF_TRUNC).toULong()
        val h =
            libssh2_sftp_open_ex(s, remotePath, remotePath.encodeToByteArray().size.toUInt(), flags, 0x1A4L, 0)
                ?: throw SshException("SFTP 打开文件失败: $remotePath")
        try {
            // Pull blocks one by one: peak memory usage = size of a single block, symmetrical with "download"
            var sent = 0L
            while (true) {
                // Defense: The source that declares `totalSize` must not exceed the limit (as in the JVM implementation).
                if (totalSize > 0 && sent >= totalSize) break
                val chunk = nextChunk() ?: break
                chunk.usePinned { pinned ->
                    var written = 0
                    while (written < chunk.size) {
                        var guard = 0
                        while (true) {
                            val n =
                                termish_sftp_write(
                                    h,
                                    pinned.addressOf(written).reinterpret(),
                                    (chunk.size - written).toULong(),
                                )
                            if (n > 0) {
                                written += n.toInt()
                                break
                            }
                            if (n.toInt() == LIBSSH2_ERROR_EAGAIN && guard++ < 200) {
                                usleep(30_000u)
                                continue
                            }
                            throw SshException("SFTP 写入失败: $remotePath")
                        }
                    }
                }
                sent += chunk.size
                onProgress(sent, totalSize)
            }
        } finally {
            libssh2_sftp_close_handle(h)
        }
    }

    override fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        val s = sftpOrThrow()
        val h =
            libssh2_sftp_open_ex(
                s,
                remotePath,
                remotePath.encodeToByteArray().size.toUInt(),
                LIBSSH2_FXF_READ.toULong(),
                0L,
                0,
            )
                ?: throw SshException("SFTP 打开文件失败: $remotePath")
        try {
            val total = fileSize(h)
            memScoped {
                val buf = allocArray<ByteVar>(64 * 1024)
                var loaded = 0L
                while (true) {
                    val n = libssh2_sftp_read(h, buf, (64 * 1024).toULong())
                    when {
                        n > 0 -> {
                            onChunk(buf.readBytes(n.toInt()))
                            loaded += n
                            onProgress(loaded, total)
                        }
                        n == 0L -> return
                        n.toInt() == LIBSSH2_ERROR_EAGAIN -> usleep(30_000u)
                        else -> throw SshException("SFTP 读取失败: $remotePath")
                    }
                }
            }
        } finally {
            libssh2_sftp_close_handle(h)
        }
    }

    /** Size of the open file handle (fstat); returns 0 on failure or if unknown (progress total not available). */
    private fun fileSize(h: CPointer<LIBSSH2_SFTP_HANDLE>): Long {
        memScoped {
            val attrs = alloc<LIBSSH2_SFTP_ATTRIBUTES>()
            var guard = 0
            while (guard++ < 200) {
                val rc = libssh2_sftp_fstat_ex(h, attrs.ptr, 0)
                if (rc == 0) return attrs.filesize.toLong()
                if (rc != LIBSSH2_ERROR_EAGAIN) break
                usleep(30_000u)
            }
        }
        return 0L
    }

    override fun close() {
        try {
            sftp?.let { libssh2_sftp_shutdown(it) }
        } catch (_: Exception) {
        }
        sftp = null
        ssh.close()
    }
}
