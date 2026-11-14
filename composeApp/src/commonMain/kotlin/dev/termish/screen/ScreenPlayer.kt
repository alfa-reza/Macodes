package dev.termish.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier

/** Platform decoder failure types: The platform layer only reports structured causes; user-facing text is determined by the UI language pack. */
sealed interface ScreenPlayerFailure {
    data class Initialization(
        val detail: String?,
    ) : ScreenPlayerFailure

    data class NoOutput(
        val fedFrames: Int,
    ) : ScreenPlayerFailure

    data class Decoding(
        val detail: String,
    ) : ScreenPlayerFailure

    data object Unsupported : ScreenPlayerFailure
}

/** Cumulative metrics for the playback pipeline; the caller uses the difference between adjacent snapshots to assess decoding load. */
data class ScreenPlayerMetrics(
    val receivedFrames: Long = 0,
    val renderedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val decoderBusyFrames: Long = 0,
    val queueDepth: Int = 0,
)

/**
 * Platform Video Player (Remote Screen Rendering Layer):
 * - Android: MediaCodec direct decoding + SurfaceView direct-to-screen (ToDesk-style direct pipeline).
 *
 * Usage: Complete H.264 frames extracted via the TCP protocol [feed]; first-frame rendering callback [onReady];
 * Playback error callback [onError]; the UI renders the screen using [ScreenVideoSurface].
 */
expect class ScreenPlayer(
    targetFps: Int,
    onReady: () -> Unit,
    onError: (ScreenPlayerFailure) -> Unit,
) {
    /** Launch the decoding and rendering pipeline (idempotent; repeated calls are ignored). */
    fun start()

    /** Feed in a complete frame; the platform player uses a bounded queue, discarding older items to make room for new ones when consumption is slow. */
    fun feed(data: ByteArray)

    /** Release the player and service. */
    fun stop()

    /** Actual video dimensions (reported by the decoder; UI layout based on aspect ratio + remote coordinate mapping). */
    val videoDims: MutableState<Pair<Int, Int>?>

    /** The time when the most recent frame was successfully submitted to the display layer; used to distinguish between network frame loss and decoding/rendering freezes. */
    val lastRenderedAtMillis: Long

    /** Check if there is currently an active rendering surface; the absence of a Surface when switching to another session does not indicate that the player has frozen. */
    val renderSurfaceAttached: Boolean

    /** Obtain a side-effect-free cumulative performance snapshot. */
    fun metrics(): ScreenPlayerMetrics
}

/** Video rendering surface (Android = SurfaceView). */
@Composable
expect fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier = Modifier,
    /** Alignment of the video at the bottom (when the remote keyboard pops up: align the video to the bottom of the toolbar, leaving a black border at the top). */
    alignBottom: Boolean = false,
)

/**
 * Decoding Capability Test: The maximum frame rate supported by the hardware decoder at a specified resolution (0 = unknown/failure).
 * Used to limit the streaming rate—if the encoding frame rate exceeds the decoding capacity, it will only waste bandwidth and cause the decoder to become overloaded and queue up.
 * (Latency escalation), industry standards (scrcpy/Parsec) limit the encoding frame rate based on decoding capabilities.
 */
expect fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int
