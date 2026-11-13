package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.termish.ssh.SftpSession

/** The native player reads SFTP files on demand; the connection is released when the screen is left, playback is paused in the background, and the playback position is saved in the session. */
@Composable
expect fun SftpVideoPlayer(
    session: SftpSession,
    remotePath: String,
    initialPositionMillis: Long,
    onPositionChanged: (Long) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
)
