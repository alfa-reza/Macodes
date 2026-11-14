package dev.macodes.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screen session UI state (attached to the session entry; retained when switching tabs).
 */
class ScreenUiState {
    /** Video player (for rendering surface binding; null = not established). */
    var player by mutableStateOf<ScreenPlayer?>(null)

    /** The player has rendered the first frame. */
    var videoReady by mutableStateOf(false)

    /** Screen resolution descriptions (e.g., 1280×720). */
    var frameSize by mutableStateOf("")

    /** Frame rate estimation. */
    var fps by mutableStateOf(0)

    /** The recent average bitrate for the statistics window. */
    var bitrateKbps by mutableStateOf(0)

    /** Network jitter estimated based on the difference in send/receive intervals. */
    var jitterMillis by mutableStateOf(0)

    /** Network-inferred frame drops + player-initiated frame drops, in per mille. */
    var droppedPermille by mutableStateOf(0)

    /** Has connectivity been established and streaming initiated? */
    var connected by mutableStateOf(false)

    /** Connection error. */
    var error by mutableStateOf<String?>(null)

    /** Screen status notices (non-error, recoverable): e.g., Mac screen off/locked; cleared automatically when frames arrive. */
    var screenHint by mutableStateOf<String?>(null)

    /** Current streaming frame rate (shown in the gear icon in the upper-right corner of fullscreen mode). */
    var streamFps by mutableStateOf(30)

    /** The maximum frame rate selected by the user; automatic downshifting will not exceed this value (0 = wait for the remote configuration to be read back). */
    var preferredStreamFps: Int = 0

    /** Time of the most recent adaptive tier adjustment; retained across reconnects, providing recovery hysteresis. */
    var adaptiveFpsChangedAtMillis: Long = 0

    /** Current image quality setting (shown in the top-right corner of the fullscreen view). */
    var streamQuality by mutableStateOf(1)

    /** Frame-rate cap from the decode-capability probe (0 = unknown; the level menu hides exceeding items accordingly). */
    var decoderMaxFps by mutableStateOf(0)

    /** Time the current screen first produces a frame; used to distinguish occasional disconnects after stable operation from failure right after connect. */
    var videoReadyAtMillis: Long = 0

    /** Number of consecutive automatic reconnection attempts; resets to zero after stable playback to prevent sessions with failure from being replaced in an infinite loop. */
    var streamReconnectAttempts: Int = 0

    /** Remote control mode (touch → mouse/scroll wheel, enabled by default); disabled = view-only (local scale and translate, no keyboard). */
    var controlMode by mutableStateOf(true)

    /** The controlled device lacks accessibility permissions (relay status packet reporting; prompt the user to grant authorization). */
    var controlPermissionMissing by mutableStateOf(false)

    /** The client lacks screen recording permissions; refresh the authorization cache of the macOS service process when explicitly reconnecting. */
    var recordingPermissionMissing: Boolean = false

    /** The controlled end's current graphical session has no available control backend (relay status 2 reported). */
    var controlUnsupported by mutableStateOf(false)

    /** Remote operation sender (ScreenSession injection: touch events → TCP control packets). */
    var controlSender: ((type: Int, x: Float, y: Float, extra: Int) -> Unit)? by mutableStateOf(null)

    /** Remote keyboard sender: keyCode + modifier mask + text (text takes precedence if not empty). */
    var keySender: ((keyCode: Int, mods: Int, text: String) -> Unit)? by mutableStateOf(null)

    /** ffmpeg is missing on the remote server (signal to install). */
    var ffmpegMissing by mutableStateOf(false)

    /** The remote streaming service is not running (signal for one-click installation). */
    var serviceMissing by mutableStateOf(false)

    /** The remote relay version is too old (prompt for an upgrade rather than a fresh installation). */
    var relayNeedsUpgrade by mutableStateOf(false)

    /** Service is being installed. */
    var installing by mutableStateOf(false)

    /** Installing FFmpeg on Linux requires a sudo password (used only for this installation; it is not saved). */
    var needsSudoPassword by mutableStateOf(false)

    /** Install log (displayed in real-time). */
    var installLog by mutableStateOf("")
}
