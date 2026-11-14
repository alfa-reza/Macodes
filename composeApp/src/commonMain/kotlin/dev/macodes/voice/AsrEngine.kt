package dev.macodes.voice

import dev.macodes.data.AsrProvider
import dev.macodes.data.AsrProviderType

/**
 * Speech Recognition Engine Abstraction (Pluggable Provider):
 * An engine interface for a “Tap Start → Stream Speech → End and Send” session.
 * Each provider (e.g. Volcano streaming ASR) implements this interface; the [createAsrEngine] factory creates instances based on
 * [AsrProvider.type] create. To add new services in the future (such as Alibaba Cloud, iFlytek, or the local Whisper), simply:
 * 1. Add enumeration values to AsrProviderType; 2. New engine implements the implementation of this interface; 3. Add a branch to the factory.
 *
 * Callbacks are uniformly triggered on the platform thread; the UI layer switches to the main thread on its own.
 */
interface AsrEngine {
    enum class State { IDLE, CONNECTING, LISTENING, FINALIZING, DONE, ERROR }

    /** State change callbacks (platform thread). */
    var onState: ((State) -> Unit)?

    /** Intermediate results (platform thread): Incremental full-text processing per package, with real-time display on screen. */
    var onPartial: ((String) -> Unit)?

    /** Final recognized text (platform thread). */
    var onFinalText: ((String) -> Unit)?

    /** Error (platform thread). */
    var onError: ((String) -> Unit)?

    val state: State

    /** Start a session (establish connection/prepare); upon success, enter the LISTENING state and call the onState callback. */
    fun start()

    /** Stream a single packet of PCM (16 kHz/16-bit/mono, approximately 200 ms). */
    fun sendPcm(data: ByteArray)

    /** End of statement: Wait for the final result and callback to `onFinalText` (or `onError`). */
    fun finish()

    /** Abort (accidental trigger/cancel): Close immediately without triggering the result callback. */
    fun abort()
}

/** Create an engine instance based on the provider type. */
fun createAsrEngine(
    provider: AsrProvider,
    apiKey: String,
): AsrEngine =
    when (provider.type) {
        AsrProviderType.VOLC_STREAMING ->
            VolcStreamingAsrEngine(
                apiKey = apiKey,
                resourceId = provider.resourceId.ifBlank { VolcAsrProtocol.DEFAULT_RESOURCE_ID },
                ws = createVoiceWebSocket(),
            )
    }
