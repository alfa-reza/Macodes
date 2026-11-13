package dev.termish.screen

internal const val SCREEN_STABLE_WINDOW_MS = 15_000L
internal const val MAX_SCREEN_RECONNECT_ATTEMPTS = 3
internal const val SCREEN_FIRST_VIDEO_TIMEOUT_MS = 12_000L
internal const val SCREEN_WAYLAND_FIRST_VIDEO_TIMEOUT_MS = 60_000L
internal const val SCREEN_VIDEO_STALL_TIMEOUT_MS = 6_000L
internal const val SCREEN_RENDER_STALL_TIMEOUT_MS = 6_000L

/**
 * TCP/SSH itself may remain in the ESTABLISHED state, but the consuming coroutine or intermediate link of `direct-tcpip` has already
 * Stop advancing. At this point, EOF will never be reached, so you cannot rely solely on `socket.close` to detect a connection failure.
 *
 * Allow the remote encoder more time to launch for the first frame; once video is received, the normal frame interval of 30 fps is much shorter than
 * 1 second; if there are no complete frames for 6 consecutive seconds, the connection can be determined to be spurious.
 */
internal fun isScreenVideoStalled(
    nowMillis: Long,
    lastVideoAtMillis: Long,
    startedAtMillis: Long,
    hasReceivedVideo: Boolean,
    firstVideoTimeoutMillis: Long = SCREEN_FIRST_VIDEO_TIMEOUT_MS,
): Boolean {
    val baseline = if (hasReceivedVideo) lastVideoAtMillis else startedAtMillis
    val timeout = if (hasReceivedVideo) SCREEN_VIDEO_STALL_TIMEOUT_MS else firstVideoTimeoutMillis
    return baseline > 0L && nowMillis - baseline >= timeout
}

/** When the network continues to receive video but the player is no longer committing frames, do not mistakenly classify TCP activity as available. */
internal fun isScreenRenderingStalled(
    nowMillis: Long,
    lastRenderedAtMillis: Long,
    videoReady: Boolean,
    renderSurfaceAttached: Boolean,
): Boolean =
    videoReady &&
        renderSurfaceAttached &&
        lastRenderedAtMillis > 0L &&
        nowMillis - lastRenderedAtMillis >= SCREEN_RENDER_STALL_TIMEOUT_MS

internal fun isUnstableScreenStream(
    nowMillis: Long,
    videoReadyAtMillis: Long,
): Boolean = videoReadyAtMillis == 0L || nowMillis - videoReadyAtMillis < SCREEN_STABLE_WINDOW_MS

internal fun fallbackScreenQuality(
    currentQuality: Int,
    actualWidth: Int,
    unstable: Boolean,
): Int =
    when {
        !unstable -> currentQuality
        currentQuality == 3 && actualWidth > 1920 -> 2
        currentQuality == 2 -> 1
        else -> currentQuality
    }
