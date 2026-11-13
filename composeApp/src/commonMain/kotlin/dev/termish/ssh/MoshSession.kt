package dev.termish.ssh

import dev.termish.mosh.KmpMoshSession
import dev.termish.mosh.MoshExitReason
import dev.termish.term.TerminalBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Mosh client session: The implementation is entirely in Kotlin (dev.termish.mosh package, SSP protocol).
 * The UI is driven by `sendData` and `resize`, and the shadow terminal state is synced to the UI buffer via `onStateUpdate`.
 */
interface MoshSession {
    fun isActive(): Boolean

    fun resize(
        columns: Int,
        rows: Int,
    )

    fun sendData(data: ByteArray)

    fun close()
}

/** Parse `MOSH CONNECT <PORT> <KEY>` from the output of `mosh-server new`. */
fun parseMoshConnect(output: String): Pair<Int, String>? {
    val m = Regex("MOSH CONNECT (\\d+) ([A-Za-z0-9+/=]+)").find(output) ?: return null
    return m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] }
}

/** Parse the service PID reported by `mosh-server new` when detached from the terminal, to enable precise cleanup after a boot failure. */
internal fun parseMoshServerPid(output: String): Int? =
    Regex("\\[mosh-server detached, pid = (\\d+)]")
        .find(output)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?.takeIf { it > 1 }

/**
 * System detection command (same approach as Termius: after the connection, use the exec channel to read /etc/os-release and
 * uname (automatically identifies the remote system; users do not need to enter it manually).
 */
const val SYSTEM_PROBE_COMMAND = "cat /etc/os-release 2>/dev/null; uname -s"

/**
 * Parse system identifiers from the probe output:
 * - Give priority to the `ID=` from os-release (ubuntu / debian / centos / fedora …)
 * - Otherwise, normalize to `uname -s` to `linux`, `macos`, `freebsd`, or `openbsd`.
 */
fun detectSystemFromOutput(output: String): String? {
    val id =
        Regex("(?m)^ID=\"?([A-Za-z0-9_-]+)\"?$")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
    if (!id.isNullOrBlank()) return id
    // The output of `uname -s` is on the last line; trim trailing newline/whitespace and take the last segment
    return when (output.trim().substringAfterLast('\n').trim()) {
        "Linux" -> "linux"
        "Darwin" -> "macos"
        "FreeBSD" -> "freebsd"
        "OpenBSD" -> "openbsd"
        else -> null
    }
}

/**
 * Pure Kotlin mosh client (dev.termish.mosh package): Syncs the shadow terminal state to [uiBuffer].
 */
fun createKmpMoshSession(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    scope: kotlinx.coroutines.CoroutineScope,
    uiBuffer: TerminalBuffer,
    onTitle: (String) -> Unit,
    onClipboard: (String) -> Unit,
    onExit: (MoshExitReason) -> Unit,
    /** State has been synced into [uiBuffer]; request a UI redraw (TerminalBuffer is not a Compose state,
     *  (If no notification is issued, new content will not appear on the screen until the next sporadic frame update.) */
    onFrame: () -> Unit,
    /** Callback triggered upon receiving the first packet from the other end (indicating that the Mosh session has been successfully established). */
    onPeerConnected: () -> Unit,
    /** Link Health: seconds since the last packet was received from the peer (0–3 is normal; the UI shows a “disconnected” notice based on this). */
    onLinkStatus: (Int) -> Unit,
): MoshSession {
    // Rendering and merging: The CONFLATED channel retains only the latest state; during burst updates, at most one main thread copies data in transit.
    val pending = Channel<KmpMoshSession.ShadowTerminalView>(Channel.CONFLATED)
    scope.launch(Dispatchers.Main) {
        for (view in pending) {
            // Copy-holding shadow lock: Session coroutines cannot read while applyDiff/resize is in progress,
            // Otherwise, inconsistent row states during resizing cause `cloneLine` to be out of bounds.
            view.lock.withLock {
                // Row-level/field-level incremental comparison: Pushes with no visual changes (such as replaying after a prediction of incorporation) do not trigger a repaint.
                if (uiBuffer.copyContentFrom(view.buffer)) onFrame()
            }
        }
    }
    val session =
        KmpMoshSession(
            ip = ip,
            port = port,
            key = key,
            columns = columns,
            rows = rows,
            scope = scope,
            onStateUpdate = { view ->
                // Copying to the UI buffer must occur on the main thread (Compose renders while reading);
                // The channel-consuming coroutine has been fixed in Main; this is just a submission.
                pending.trySend(view)
            },
            onExit = onExit,
            onPeerConnected = onPeerConnected,
            onLinkStatus = onLinkStatus,
        )
    session.setTitleCallback(onTitle)
    session.setClipboardCallback(onClipboard)
    session.start()
    return object : MoshSession {
        override fun isActive(): Boolean = session.isActive()

        override fun resize(
            columns: Int,
            rows: Int,
        ) = session.resize(columns, rows)

        override fun sendData(data: ByteArray) = session.sendData(data)

        override fun close() = session.close()
    }
}
