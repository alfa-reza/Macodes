package dev.termish.ssh

/** Directory/file entries (for SFTP lists). */
data class SftpEntry(
    val name: String,
    val isDirectory: Boolean,
    /** Permission strings, such as drwxr-xr-x. */
    val permissions: String,
    val size: Long,
    /** Time to modify (epoch millis). */
    val modifiedAt: Long,
    val isHidden: Boolean,
)

/**
 * Platform-independent SFTP sessions: standalone SSH connection + SFTP channel (authentication/confirmation of host keys)
 * Reuse [SshCallbacks]; dialogs are handled globally by the UI layer.
 */
interface SftpSession {
    /**
     * Open a random-read handle (blocking call; the caller uses it serially in the background and is responsible for closing it).
     * The handle exclusively uses a separate SSH/SFTP connection and does not share the channel with directory browsing or downloads; closing the parent session does not affect it.
     */
    fun openRead(remotePath: String): SftpReadHandle = throw UnsupportedOperationException("Random reads unavailable")

    /** List the directory; throw an exception on failure (surfaced as a notice by the UI layer). */
    fun list(path: String): List<SftpEntry>

    /** Create a directory. */
    fun mkdir(path: String)

    /**
     * Delete a file or directory; **recursively delete** if the directory is not empty (internal handling by the platform implementation, which is different from
     * (Semantics consistent with the shell `rm -r` command). Throws an exception on failure (e.g., no permissions or read-only).
     */
    fun delete(path: String)

    /** Rename/move (rename within the same directory or move across directories). Throws an exception on failure. */
    fun rename(
        oldPath: String,
        newPath: String,
    )

    /**
     * User home directory (server-side SFTP working directory, i.e., ~): Parse using realpath("."),
     * More versatile than guessing /home/xxx (covers macOS, BSD, and custom home directories); throws an exception on failure.
     */
    fun home(): String

    /**
     * Streaming upload: Repeatedly call the [nextChunk] callback to retrieve the next block (returns null = EOF), and write each block to the remote server one by one,
     * Files of any size are never loaded entirely into memory (mirroring the chunked callback behavior of [download]).
     * [totalSize] is used to show progress (pass 0 if unknown); [onProgress] is a callback that returns the number of bytes transferred or the total number of bytes.
     */
    fun upload(
        remotePath: String,
        totalSize: Long,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        nextChunk: () -> ByteArray?,
    )

    /**
     * Download remote files to the local machine: in blocks of approximately 64 KB, using the [onChunk] callback (blocking call).
     * [onProgress] Callback reports the number of bytes downloaded or the total number of bytes (0 if total is unknown, such as when certain servers do not report the size).
     * The UI layer is responsible for writing the blocks to a local file and closing the save channel.
     */
    fun download(
        remotePath: String,
        onProgress: (loaded: Long, total: Long) -> Unit = { _, _ -> },
        onChunk: (ByteArray) -> Unit,
    )

    fun close()
}

/** Bounded random read: 64-bit byte position; returns an empty array on EOF; cannot be read again after closed. */
interface SftpReadHandle {
    val size: Long

    /** Return at most min([maxBytes], 64KB) bytes; short reads are allowed. */
    fun read(
        offset: Long,
        maxBytes: Int,
    ): ByteArray

    fun close()
}

/** The playback connection has its own lifecycle; close/trace events cannot be passed back to the directory session. */
internal fun SshCallbacks.forSftpReader(): SshCallbacks =
    object : SshCallbacks by this {
        override fun onClosed(reason: String?) {}

        override fun onTraceStep(step: String) {}
    }

/** Platform factories: JVM=sshj SFTPClient; iOS=libssh2_sftp. */
expect fun createSftpSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SftpSession

/** Remote path concatenation: no double slash under the root directory (used by the platform's recursive delete/rename). */
internal fun joinRemote(
    base: String,
    name: String,
): String = if (base == "/") "/$name" else "$base/$name"
