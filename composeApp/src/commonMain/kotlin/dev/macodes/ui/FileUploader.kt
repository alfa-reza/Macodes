package dev.macodes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.macodes.ssh.SftpSession
import dev.macodes.ssh.SshCallbacks
import dev.macodes.ssh.SshConnection
import dev.macodes.ssh.createSftpSession
import dev.macodes.util.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Upload session state (drives the progress overlay in the lower-right corner). */
sealed interface UploadUiState {
    data object Idle : UploadUiState

    /** Upload: Current file progress. */
    data class Uploading(
        val name: String,
        val sent: Long,
        val total: Long,
        val doneCount: Int,
        val totalCount: Int,
    ) : UploadUiState

    /** All done (paths = remote paths of successfully uploaded files, used to auto-input into the terminal). */
    data class Done(
        val count: Int,
        val paths: List<String>,
    ) : UploadUiState

    /** Failure (including cause; returns to Idle after a tooltip). */
    data class Failed(
        val message: String,
    ) : UploadUiState
}

/**
 * Terminal File Uploader: Reuse existing authentication parameters (password/private key/host) to create a new SFTP connection,
 * Decouple from interactive sessions (the "mosh" mode also applies—SFTP uses a separate SSH connection).
 * Upload multiple selected files to the queue in a **sequential** manner (reusing a single SFTP session for the entire batch; close the session after completion);
 * Streaming upload: Local files are read and written in blocks; files of any size are not loaded entirely into memory.
 */
class TerminalFileUploader(
    private val controller: TerminalController,
    private val scope: CoroutineScope,
) {
    var state: UploadUiState = UploadUiState.Idle
        private set
    var onState: ((UploadUiState) -> Unit)? = null

    private val queue = ArrayDeque<PickedFile>()
    private var job: Job? = null

    /** Enqueue and launch (if not already in progress) a serial upload. */
    fun enqueue(
        file: PickedFile,
        targetDir: String,
    ) {
        if (job?.isActive == true) {
            queue.addLast(file)
            return
        }
        queue.addLast(file)
        job = scope.launch { drain(targetDir) }
    }

    private suspend fun drain(targetDir: String) {
        val total = queue.size
        var done = 0
        val uploadedPaths = mutableListOf<String>()
        val callbacks = uploadCallbacks()
        val connection = uploadConnection()
        val sftp =
            try {
                withContext(ioDispatcher()) { createSftpSession(connection, callbacks) }
            } catch (e: CancellationException) {
                closeQueuedFiles()
                throw e
            } catch (e: Exception) {
                closeQueuedFiles()
                state = UploadUiState.Failed(e.message ?: (e::class.simpleName ?: "error"))
                onState?.invoke(state)
                return
            }
        try {
            while (queue.isNotEmpty()) {
                val file = queue.removeFirst()
                try {
                    val path = uploadOne(sftp, file, targetDir, done, total)
                    uploadedPaths.add(path)
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    closeQueuedFiles()
                    state = UploadUiState.Failed(e.message ?: (e::class.simpleName ?: "error"))
                    onState?.invoke(state)
                    return
                }
            }
        } finally {
            closeQueuedFiles()
            withContext(ioDispatcher()) { runCatching { sftp.close() } }
        }
        state = UploadUiState.Done(done, uploadedPaths)
        onState?.invoke(state)
    }

    private suspend fun uploadOne(
        sftp: SftpSession,
        picked: PickedFile,
        targetDir: String,
        index: Int,
        queueTotal: Int,
    ): String {
        try {
            val remotePath = if (targetDir.endsWith("/")) "$targetDir${picked.name}" else "$targetDir/${picked.name}"
            withContext(ioDispatcher()) {
                sftp.upload(
                    remotePath = remotePath,
                    totalSize = picked.size,
                    onProgress = { sent, total ->
                        scope.launch {
                            state = UploadUiState.Uploading(picked.name, sent, total, index, queueTotal)
                            onState?.invoke(state)
                        }
                    },
                    nextChunk = { picked.readChunk() },
                )
            }
            return remotePath
        } finally {
            picked.close()
        }
    }

    private fun closeQueuedFiles() {
        while (queue.isNotEmpty()) queue.removeFirst().close()
    }

    private fun uploadConnection(): SshConnection =
        SshConnection(
            host = controller.host.hostname,
            port = controller.host.port,
            username = controller.host.username,
            password = controller.password,
            privateKeyPem = controller.privateKeyPem,
            connectTimeoutMillis = 10_000,
            keepAliveSeconds = 0,
        )

    private fun uploadCallbacks(): SshCallbacks =
        object : SshCallbacks {
            override suspend fun onOutput(data: ByteArray) {}

            override suspend fun onStderr(data: ByteArray) {}

            override fun onExitStatus(status: Int) {}

            override fun onClosed(reason: String?) {}

            override suspend fun onPrompt(prompt: dev.macodes.ssh.AuthPrompt): List<String>? = null

            override fun verifyHostKey(hostKey: dev.macodes.ssh.HostKeyInfo): Boolean = true
        }
}

/** Upload Target Directory card: Icon + main title + path subtitle + right arrow; clearly defined tap region. */
@Composable
fun UploadDirOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(
                    36.dp,
                ).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Transfer Progress Overlay (shared for terminal uploads and SFTP downloads): Surface card with corner radius + brand-green progress bar.
 * File name + percentage + subtitle (e.g., “Uploading 1/3,” “Downloading…”).
 */
@Composable
fun TransferProgressCard(
    /** File names. */
    title: String,
    /** Progress 0..1 (if the total size is unknown, set to 0; the progress bar will not advance). */
    progress: Float,
    /** Percentage values (e.g., "42%"; leave blank if unknown). */
    percent: String,
    /** Subtitles (e.g., “Uploading 1/3,” “Downloading…”). */
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(surface.copy(alpha = 0.97f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (percent.isNotBlank()) {
                Text(
                    percent,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
    }
}
