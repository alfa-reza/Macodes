package dev.macodes.voice

import androidx.compose.runtime.Composable

/**
 * Voice Input Platform Abstraction (expect/actual):
 * - [MicrophoneRecorder]: 16 kHz / PCM16 / Mono Recorder
 * - [VoiceWebSocket]: Binary WebSocket client
 * - [rememberMicPermissionRequester]: Microphone permission request (Android runtime permission dialog;
 *   idempotent; if permission was already granted, `onResult(true)` is called immediately)
 *
 * Microphone permission requester: `request` is idempotent; if permission was already granted, call back `true` immediately.
 */

interface MicPermissionRequester {
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberMicPermissionRequester(): MicPermissionRequester

/**
 * Recorder: 16 kHz / 16-bit / mono PCM (the standard input format for Volcano ASR).
 * Platform thread callbacks: [onData] (approximately every 200 ms); [onError] reports unrecoverable errors
 * (e.g., no device available or permissions revoked), after which the recording stops.
 */
expect class MicrophoneRecorder() {
    /** Launch recording; returning false indicates that recording cannot be launched (no device/no authorization; the error is provided via [onError]). */
    fun start(
        onData: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ): Boolean

    fun stop()
}

/** Binary WebSocket: async callback after `connect`; `send` is thread-safe; `close` is idempotent. */
interface VoiceWebSocket {
    fun connect(
        url: String,
        headers: Map<String, String>,
        onOpen: () -> Unit,
        onMessage: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        onClosed: () -> Unit,
    )

    fun send(bytes: ByteArray)

    fun close()
}

expect fun createVoiceWebSocket(): VoiceWebSocket
