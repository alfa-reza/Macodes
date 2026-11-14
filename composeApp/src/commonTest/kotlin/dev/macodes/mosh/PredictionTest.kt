package dev.macodes.mosh

import dev.macodes.term.CellAttr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Speculative local prediction regression test. */
class PredictionTest {
    private var now = 0L

    private fun layer() = PredictionLayer(nowMs = { now })

    @Test
    fun printableCharPredictsWithUnderline() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)

        assertTrue(l.onUserInput("a".encodeToByteArray(), 100, 0u))
        val shown = l.currentForDisplay()!!
        val cell = shown.buffer.lineAt(0).cells[0]
        assertEquals('a'.code, cell.codePoint)
        assertTrue(cell.attrs and CellAttr.UNDERLINE != 0) // send_interval>80 → flagging with an underscore
        // the confirmation state itself is unaffected
        assertEquals(
            ' '.code,
            base.buffer
                .lineAt(0)
                .cells[0]
                .codePoint,
        )
    }

    @Test
    fun lowSendIntervalDisablesUnderline() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        // 30 < send_interval <= 50: Prediction that it will be enabled (>30) but not underlined (<=50, mosh FLAG_TRIGGER)
        assertTrue(l.onUserInput("a".encodeToByteArray(), 40, 0u))
        val cell =
            l
                .currentForDisplay()!!
                .buffer
                .lineAt(0)
                .cells[0]
        assertEquals('a'.code, cell.codePoint)
        assertTrue(cell.attrs and CellAttr.UNDERLINE == 0)
    }

    @Test
    fun unsupportedControlCharSkipsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)

        assertFalse(l.onUserInput(byteArrayOf(0x08), 100, 0u)) // BS control characters not supported → This section does not receive any prediction
        assertTrue(l.currentForDisplay() === base) // Back to confirmation mode
    }

    @Test
    fun backspacePredictsErase() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("ab".encodeToByteArray(), 100, 0u)
        assertTrue(l.onUserInput(byteArrayOf(0x7f), 100, 0u)) // Delete b

        val shown = l.currentForDisplay()!!
        assertEquals(
            'a'.code,
            shown.buffer
                .lineAt(0)
                .cells[0]
                .codePoint,
        )
        assertEquals(
            ' '.code,
            shown.buffer
                .lineAt(0)
                .cells[1]
                .codePoint,
        ) // b Deleted
        assertEquals(1, shown.buffer.cursorCol) // Cursor Back
    }

    @Test
    fun carriageReturnPredictsNewline() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("ls".encodeToByteArray(), 100, 0u)
        assertTrue(l.onUserInput(byteArrayOf(0x0d), 100, 0u)) // Enter → Carriage Return \r\n

        val shown = l.currentForDisplay()!!
        assertEquals(1, shown.buffer.cursorRow)
        assertEquals(0, shown.buffer.cursorCol)
    }

    @Test
    fun leftArrowPredictsCursorMove() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("ab".encodeToByteArray(), 100, 0u)
        assertTrue(l.onUserInput("\u001b[D".encodeToByteArray(), 100, 0u)) // ←
        assertEquals(1, l.currentForDisplay()!!.buffer.cursorCol)
    }

    @Test
    fun lowRttDisablesPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        // send_interval<=20 (SRTT_TRIGGER_LOW): No prediction for low-latency links
        assertFalse(l.onUserInput("a".encodeToByteArray(), 20, 0u))
    }

    @Test
    fun altScreenPredictsPrintableOnly() {
        val base = ShadowTerminal.create(80, 24)
        base.buffer.enterAltScreen(true)
        val l = layer()
        l.onConfirmed(base)
        // alt Screen (shell echo path within a Vim/tmux pane, etc.): Printable characters still receive prediction
        assertTrue(l.onUserInput("a".encodeToByteArray(), 100, 0u))
        assertEquals(
            'a'.code,
            l
                .currentForDisplay()!!
                .buffer
                .lineAt(0)
                .cells[0]
                .codePoint,
        )
        // Do not perform prediction on control bytes: their meanings vary across fullscreen programs; wait for the echo.
        assertFalse(l.onUserInput(byteArrayOf(0x7f), 100, 0u))
    }

    @Test
    fun predictionSurvivesConfirmedFrameWithoutEchoAck() {
        val base = ShadowTerminal.create(80, 24)
        val next = base.fork() // echoAck is still 0 (input carry frame 1 not overwritten)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)

        l.onConfirmed(next)
        // Frames arriving without confirmation: Prediction of cross-frame survival (no longer discard entire segments → no rollback flickering)
        val shown = l.currentForDisplay()!!
        assertTrue(shown !== next)
        assertEquals(
            'a'.code,
            shown.buffer
                .lineAt(0)
                .cells[0]
                .codePoint,
        )
    }

    @Test
    fun echoAckAdvancingDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val next = base.fork()
        next.echoAck = 1u // Carry frame covered (lastSentNum 0 + 1)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)

        l.onConfirmed(next)
        assertTrue(l.currentForDisplay() === next) // Echo received → Revert to state of confirmation
    }

    @Test
    fun glitchTimeoutDropsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        now = 1000
        l.onUserInput("a".encodeToByteArray(), 100, 0u)
        assertFalse(l.glitchTimedOut())

        now = 5000 // Idle for more than 3 seconds
        assertTrue(l.glitchTimedOut())
        l.dropPrediction()
        assertTrue(l.currentForDisplay() === base)
        assertFalse(l.glitchTimedOut()) // Do not trigger again after discarding.
    }

    @Test
    fun resizeResetsPrediction() {
        val base = ShadowTerminal.create(80, 24)
        val l = layer()
        l.onConfirmed(base)
        l.onUserInput("a".encodeToByteArray(), 100, 0u)
        l.reset()
        assertTrue(l.currentForDisplay() === base)
    }
}
