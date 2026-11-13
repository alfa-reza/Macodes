package dev.termish.mosh

/**
 * Local prediction echo (a simplified version of the protocol prediction engine).
 *
 * The key to "instant" typing in the native mosh-client: On high-RTT connections, each keystroke is first processed locally
 * Prediction of rendering on the framebuffer; correction after server confirmation. Ensure semantic alignment with mosh:
 * - Triggered using `send_interval` (SRTT/2 clamped to 20–250 ms): >30 ms to turn on / <=20 ms to turn off,
 *   With hysteresis (mosh SRTT_TRIGGER_LOW/HIGH); underline marking (flagging) >80 ms on,
 *   <=50ms threshold (mosh FLAG_TRIGGER_LOW/HIGH)
 * - Prediction of survival across acknowledgment frames: When `echo_ack` does not override the carrier frame, replay the prediction on each new confirmation state,
 *   Avoid the flickering effect where "a character appears → the frame is confirmed to contain no echo → it disappears → the echo frame arrives → and it reappears."
 * - echo_ack >= the carrying frame (late ACK after mosh ECHO_TIMEOUT=50ms) is incorporated:
 *   Echo (or the fact that there is “no echo,” such as during password input) is already included in the confirmation state.
 * - echo_ack >= the carrying frame (late ACK after mosh ECHO_TIMEOUT=50ms) is incorporated:
 *   Echo (or the fact that there is “no echo,” such as during password input) is already included in the confirmation state.
 * - For secondary screens (fullscreen programs like vim, tmux, herdr, etc.), only perform character prediction for printable characters: shell within a pane /
 *   Benefit from the echo path in Vim insert mode; control byte semantics vary by program and are not subject to prediction (mosh native)
 *   (then full prediction + per-cell integration); skip this section for standard screens containing unsupported control bytes;
 *   Large pastes (>128 bytes; mosh threshold 100) do not receive any prediction.
 */
internal class PredictionLayer(
    private val nowMs: () -> Long,
) {
    private var confirmed: ShadowTerminal? = null
    private var predicted: ShadowTerminal? = null

    /** Input bytes not yet covered by echo_ack (used for replay prediction). */
    private var pending = ByteArray(0)

    /** Estimated transmission frame containing the trailing bytes of "pending" (at the input time: lastSentNum+1);
     *  echoAck >= the carrying frame counts as acknowledged (the prefix bytes in the same or an earlier frame must also have been acked). */
    private var pendingCarriedBy: ULong = 0uL
    private var predictedAt = 0L
    private var srttTrigger = false
    private var flagging = false

    // Trigger thresholds for mosh terminaloverlay.h (all apply to `send_interval`, not bare SRTT)
    private val srttTriggerLow = 20L
    private val srttTriggerHigh = 30L
    private val flagTriggerLow = 50L
    private val flagTriggerHigh = 80L
    private val glitchTimeout = 3000L
    private val maxPending = 128

    /** Called when a session receives a new confirmation status: echo_ack incorporation + survival prediction cross-frame replay. */
    fun onConfirmed(shadow: ShadowTerminal) {
        confirmed = shadow
        if (pending.isEmpty()) {
            predicted = null
            return
        }
        if (shadow.echoAck >= pendingCarriedBy) {
            pending = ByteArray(0)
            predicted = null
            return
        }
        // Predictions not yet confirmed: Rebuild on the new confirmed state (COW branching O(number of rows) + replay O(pending)),
        // Equivalent to mosh reapplying the surviving overlay to each new frame
        predicted = shadow.predictInput(pending, flagging)
    }

    /**
     * Call when user input arrives (before sending it to SSP). Return whether a prediction that can be shown was generated.
     */
    fun onUserInput(
        bytes: ByteArray,
        sendIntervalMs: Long,
        lastSentNum: ULong,
    ): Boolean {
        // Trigger hysteresis (prediction trigger hysteresis)
        if (sendIntervalMs > srttTriggerHigh) {
            srttTrigger = true
        } else if (sendIntervalMs <= srttTriggerLow && predicted == null) {
            srttTrigger = false // mosh: Allowing shutdown only when there are no active predictions
        }
        if (sendIntervalMs > flagTriggerHigh) {
            flagging = true
        } else if (sendIntervalMs <= flagTriggerLow) {
            flagging = false
        }

        val base = confirmed ?: return false
        if (!srttTrigger) return false
        val altScreen = base.buffer.altScreen

        pending += bytes
        pendingCarriedBy = lastSentNum + 1u
        if (pending.size > maxPending) {
            // Mass paste: Abandon prediction (in `mosh process_user_input`, `paste` directly triggers a reset)
            pending = ByteArray(0)
            predicted = null
            return false
        }
        if (altScreen &&
            pending.any {
                val v = it.toInt() and 0xff
                v < 0x20 || v == 0x7f
            }
        ) {
            // alt: Predict only printable character segments: control bytes have different meanings in fullscreen programs
            // (In Vim normal mode, “x” deletes a character, not a backspace); wait for the echo; pending—to be incorporated later
            predicted = null
            return false
        }

        val p = base.predictInput(pending, flagging)
        predicted = p
        if (p != null) {
            predictedAt = nowMs()
            return true
        }
        // Contains unsupported control bytes: This section does not make predictions (pending—reserved until echo_ack is incorporated).
        return false
    }

    /** Window resizing: geometric failure, discarded predictions, and other confirmation states (mosh process_resize → reset). */
    fun reset() {
        predicted = null
        pending = ByteArray(0)
    }

    /** Prediction hangs for too long: Discard the prediction and return to the confirmation state (restart with the next input). */
    fun dropPrediction() {
        predicted = null
        pending = ByteArray(0)
    }

    /** Current status to show: Show “Prediction” if a prediction is available; otherwise, show “Confirmed.” */
    fun currentForDisplay(): ShadowTerminal? = predicted ?: confirmed

    /** If a prediction remains pending for too long (the server has not yet confirmed it or there has been output in the meantime) → Discard it and return to the confirmed state. */
    fun glitchTimedOut(): Boolean = predicted != null && nowMs() - predictedAt > glitchTimeout
}
