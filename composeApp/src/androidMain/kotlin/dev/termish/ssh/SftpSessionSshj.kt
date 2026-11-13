package dev.termish.ssh

import java.io.InputStream
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.xfer.FilePermission

private const val TRANSFER_BUFFER_SIZE = 64 * 1024
private const val PIPELINE_DEPTH = 16

actual fun createSftpSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SftpSession = SftpSessionSshj(connection, callbacks)

/**
 * sshj SFTP Implementation (Android): Reusing the authentication chain from [SshSessionSshj]
 * (password / private key / encrypted private key / keyboard-interactive / host key confirmation),
 * Open an SFTP channel over a separate SSH connection.
 */
class SftpSessionSshj(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
) : SftpSession {
    // SFTP metadata operations remain request-response-based; file transfers use a finite-depth pipeline as described below.
    // A 30-second timeout is treated as a broken link or exception; only time-out failures can be caught by the UI to prevent indefinite waiting after a connection is interrupted.
    private val ssh = SshSessionSshj(connection, callbacks, readTimeoutMs = 30_000L)
    private var client: SFTPClient? = null

    private fun clientOrThrow(): SFTPClient = client ?: ssh.openSftp().also { client = it }

    override fun openRead(remotePath: String): SftpReadHandle {
        val owner = SftpSessionSshj(connection, callbacks.forSftpReader())
        try {
            val remote = owner.clientOrThrow().open(remotePath)
            try {
                val length = remote.length()
                return object : SftpReadHandle {
                    override val size = length
                    private var closed = false
                    private var input: InputStream? = null
                    private var nextOffset = -1L

                    @Synchronized
                    override fun read(
                        offset: Long,
                        maxBytes: Int,
                    ): ByteArray {
                        check(!closed) { "SFTP reader closed" }
                        require(offset >= 0 && maxBytes > 0)
                        if (offset >= size) return ByteArray(0)
                        val buffer = ByteArray(minOf(maxBytes.toLong(), TRANSFER_BUFFER_SIZE.toLong(), size - offset).toInt())
                        if (offset != nextOffset) {
                            input?.close()
                            // Sequential playback reuses the 16-packet pipeline (~512KB in-flight); repositioning only happens on seek.
                            input = remote.ReadAheadRemoteFileInputStream(PIPELINE_DEPTH, offset, size - offset)
                        }
                        val count = input!!.read(buffer, 0, buffer.size)
                        nextOffset = offset + count.coerceAtLeast(0)
                        return if (count <= 0) ByteArray(0) else buffer.copyOf(count)
                    }

                    @Synchronized
                    override fun close() {
                        if (closed) return
                        closed = true
                        try {
                            remote.close()
                        } finally {
                            owner.close()
                        }
                    }
                }
            } catch (e: Exception) {
                remote.close()
                throw e
            }
        } catch (e: Exception) {
            owner.close()
            throw e
        }
    }

    override fun list(path: String): List<SftpEntry> {
        val c = clientOrThrow()
        return c.ls(path).map { f ->
            val a = f.attributes
            SftpEntry(
                name = f.name,
                isDirectory = a.type == FileMode.Type.DIRECTORY,
                permissions = permString(a.permissions, a.type == FileMode.Type.DIRECTORY),
                size = a.size,
                modifiedAt = a.mtime * 1000L,
                isHidden = f.name.startsWith("."),
            )
        }
    }

    override fun mkdir(path: String) {
        clientOrThrow().mkdir(path)
    }

    override fun delete(path: String) {
        deleteRecursive(clientOrThrow(), path)
    }

    private fun deleteRecursive(
        c: SFTPClient,
        path: String,
    ) {
        val attrs = c.stat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            c.ls(path).forEach { f ->
                if (f.name == "." || f.name == "..") return@forEach
                deleteRecursive(c, joinRemote(path, f.name))
            }
            c.rmdir(path)
        } else {
            c.rm(path)
        }
    }

    override fun rename(
        oldPath: String,
        newPath: String,
    ) {
        clientOrThrow().rename(oldPath, newPath)
    }

    override fun home(): String = clientOrThrow().canonicalize(".")

    override fun upload(
        remotePath: String,
        totalSize: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
        nextChunk: () -> ByteArray?,
    ) {
        val c = clientOrThrow()
        // RemoteFileOutputStream maintains a queue of uncommitted write requests: no longer writes every 64KB on networks with high latency
        // Wait for a single server response. When the queue depth is 16, the data in transit is approximately 1 MB, balancing throughput and memory usage.
        val remote = c.open(remotePath, setOf(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC))
        val output = remote.RemoteFileOutputStream(0L, PIPELINE_DEPTH)
        try {
            var sent = 0L
            while (true) {
                // Precaution: stop once `sent` reaches the declared `totalSize` (an endless `nextChunk` would fill the remote disk or keep the connection open forever).
                if (totalSize > 0 && sent >= totalSize) break
                val chunk = nextChunk() ?: break
                require(chunk.isNotEmpty()) { "File source returned an empty chunk" }
                var off = 0
                while (off < chunk.size) {
                    val remaining = if (totalSize > 0) totalSize - sent else Long.MAX_VALUE
                    if (remaining <= 0) break
                    val n = minOf(TRANSFER_BUFFER_SIZE.toLong(), (chunk.size - off).toLong(), remaining).toInt()
                    output.write(chunk, off, n)
                    off += n
                    sent += n
                    onProgress(sent, totalSize)
                }
            }
        } finally {
            try {
                output.close()
            } finally {
                remote.close()
            }
        }
    }

    override fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ) {
        val c = clientOrThrow()
        val remote = c.open(remotePath)
        try {
            val total = remote.length()
            if (total == 0L) {
                onProgress(0L, 0L)
                return
            }
            // Prefetch multiple blocks simultaneously to hide SFTP round-trip latency; use `total` as the upper limit to prevent exceeding EOF.
            // Making unnecessary requests.
            val input = remote.ReadAheadRemoteFileInputStream(PIPELINE_DEPTH, 0L, total)
            val buf = ByteArray(TRANSFER_BUFFER_SIZE)
            var offset = 0L
            while (true) {
                val n = input.read(buf, 0, buf.size)
                if (n <= 0) break
                onChunk(buf.copyOf(n))
                offset += n
                onProgress(offset, total)
            }
        } finally {
            remote.close()
        }
    }

    override fun close() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
        ssh.close()
    }

    private fun permString(
        perms: Set<FilePermission>,
        isDir: Boolean,
    ): String {
        val sb = StringBuilder(10)
        sb.append(if (isDir) 'd' else '-')
        val order =
            listOf(
                FilePermission.USR_R,
                FilePermission.USR_W,
                FilePermission.USR_X,
                FilePermission.GRP_R,
                FilePermission.GRP_W,
                FilePermission.GRP_X,
                FilePermission.OTH_R,
                FilePermission.OTH_W,
                FilePermission.OTH_X,
            )
        for (p in order) sb.append(if (p in perms) permChar(p) else '-')
        return sb.toString()
    }

    private fun permChar(p: FilePermission): Char =
        when (p) {
            FilePermission.USR_R, FilePermission.GRP_R, FilePermission.OTH_R -> 'r'
            FilePermission.USR_W, FilePermission.GRP_W, FilePermission.OTH_W -> 'w'
            else -> 'x'
        }
}
