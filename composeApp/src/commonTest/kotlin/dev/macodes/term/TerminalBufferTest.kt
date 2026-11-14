package dev.macodes.term

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TerminalBuffer regression test: Covers previously fixed bugs such as resize contraction and color inheritance for wide character tails. */
class TerminalBufferTest {
    @Test
    fun unlimitedScrollbackNotTruncated() {
        // In the shadow terminal, `Int.MAX_VALUE` represents no upper limit (mosh Framebuffer semantics),
        // No rows should be lost when scrolling; otherwise, the SSP diff will be misaligned with the service-side framebuffer.
        val b = TerminalBuffer(10, 5, maxScrollbackLines = Int.MAX_VALUE)
        repeat(200) { b.scrollUp(1) }
        assertEquals(200, b.scrollbackSize())

        val capped = TerminalBuffer(10, 5, maxScrollbackLines = 100)
        repeat(200) { capped.scrollUp(1) }
        assertEquals(100, capped.scrollbackSize())
    }

    @Test
    fun shallowForkSharesLinesAndCopiesOnWrite() {
        // COW: Shared objects after a fork; when either buffer writes to a shared row, it clones it first so they do not interfere with each other
        val b = TerminalBuffer(10, 5)
        b.putChar('a'.code)
        val fork = b.shallowFork()
        assertTrue(fork.lineAt(0) === b.lineAt(0)) // Shared on the same row as the object before writing

        fork.putChar('b'.code) // Write Row 0 → COW clone
        assertTrue(fork.lineAt(0) !== b.lineAt(0))
        assertEquals('a'.code, b.lineAt(0).cells[0].codePoint) // The original buffer remains unchanged.
        assertEquals('b'.code, fork.lineAt(0).cells[1].codePoint) // Write each row separately.
    }

    @Test
    fun shallowForkPreservesExactLineCount() {
        // Regression: shallowFork used to append source rows after the empty rows pre-built by the constructor; each fork
        // There are extra "rows" of phantom blank lines at the top, and they accumulate as the number of forks increases (the longer the session, the slower it becomes).
        val b = TerminalBuffer(10, 5, maxScrollbackLines = 100)
        repeat(20) { b.scrollUp(1) } // Create 20 rows of scrollback
        b.putChar('x'.code)
        val fork = b.shallowFork()
        assertEquals(b.totalLines(), fork.totalLines())
        assertEquals(b.scrollbackSize(), fork.scrollbackSize())
        // Ensure consistency in row-by-row references (phantom lines will change both).
        for (i in 0 until b.totalLines()) {
            assertTrue(fork.absLine(i) === b.absLine(i), "line $i 应共享同一对象")
        }
        // Consecutive rows do not accumulate phantom lines
        val fork2 = fork.shallowFork()
        assertEquals(b.totalLines(), fork2.totalLines())
    }

    @Test
    fun lineVersionBumpsOnWriteNotOnCursorMove() {
        val b = TerminalBuffer(10, 5)
        b.putChar('a'.code)
        val v0 = b.lineAt(0).version
        // Cursor movement and mode toggling do not alter the content; version numbers remain unchanged.
        b.moveTo(2, 2)
        b.setScrollRegion(0, 4)
        assertEquals(v0, b.lineAt(0).version)
        // Write content → Version increment
        b.putChar('b'.code)
        assertTrue(b.lineAt(0).version > v0)
    }

    @Test
    fun lineVersionBumpsOnEraseAndResize() {
        val b = TerminalBuffer(10, 5)
        b.putChar('x'.code)
        val v0 = b.lineAt(0).version
        b.eraseLine()
        assertTrue(b.lineAt(0).version > v0)
        b.putChar('y'.code)
        val v1 = b.lineAt(0).version
        b.resize(6, 5)
        assertTrue(b.lineAt(0).version > v1)
    }

    @Test
    fun fullScreenScrollKeepsLineIdentityAndVersion() {
        val b = TerminalBuffer(10, 5)
        repeat(5) { r ->
            b.lineAt(r).let {
                it.cells[0].codePoint = 'a'.code + r
                it.touch()
            }
        }
        val first = b.lineAt(0)
        val firstVersion = first.version
        b.scrollUp(1)
        // The old first row enters the scrollback; it is still the same instance and the version is unchanged (the content was not rewritten)
        assertTrue(b.scrollbackSize() == 1)
        assertTrue(b.absLine(0) === first)
        assertEquals(firstVersion, b.absLine(0).version)
        // the first visible row is the original row 2; the instance is unchanged
        assertTrue(b.lineAt(0) !== first)
    }

    @Test
    fun regionScrollBumpsDestinationLines() {
        val b = TerminalBuffer(10, 5)
        repeat(5) { r -> b.lineAt(r).touch() }
        val versions = (0..4).map { b.lineAt(it).version }
        b.setScrollRegion(1, 3)
        b.scrollUp(1)
        // The content within the highlighted region has been rewritten → version increment; the content outside the highlighted region remains unchanged.
        assertTrue(b.lineAt(1).version > versions[1])
        assertTrue(b.lineAt(2).version > versions[2])
        assertEquals(versions[4], b.lineAt(4).version)
    }

    @Test
    fun cellWidthPrecomputed() {
        val b = TerminalBuffer(10, 5)
        b.putChar('a'.code)
        assertEquals(1, b.lineAt(0).cells[0].width)
        b.putChar('中'.code)
        assertEquals(2, b.lineAt(0).cells[1].width)
        assertTrue(b.lineAt(0).cells[1].isWide)
        assertTrue(b.lineAt(0).cells[2].isWideTail)
        assertEquals(1, b.lineAt(0).cells[2].width)
        // Narrow characters overlap wide characters → reset width, clear tail markers
        b.moveTo(0, 1)
        b.putChar('b'.code)
        assertEquals(1, b.lineAt(0).cells[1].width)
        assertFalse(b.lineAt(0).cells[2].isWideTail)
    }

    private fun TerminalBuffer.lineText(row: Int): String {
        val sb = StringBuilder()
        for (c in visibleLines()[row].cells) {
            if (c.isWideTail) continue
            sb.append(c.codePoint.toChar())
        }
        return sb.toString().trimEnd()
    }

    @Test
    fun resizeShrinkKeepsCursorVisible() {
        // Keyboard-pop scenario: when shrinking the row count, blank rows below the cursor are discarded and the prompt stays in the visible area
        val b = TerminalBuffer(10, 5)
        val e = TerminalEmulator(b)
        e.writeText("line0\r\nline1\r\n$ ")
        assertEquals(2, b.cursorRow)

        b.resize(10, 3)

        assertEquals(2, b.cursorRow)
        assertEquals("line0", b.lineText(0))
        assertEquals("line1", b.lineText(1))
        assertEquals("$", b.lineText(2))
        // The discarded rows are the blank rows at the bottom; the top content must not be pushed into the scrollback
        assertEquals(0, b.scrollbackSize())
    }

    @Test
    fun resizeShrinkFullScreenFallsBackToScrollback() {
        // A full screen of text has no blank rows to drop: the top row enters the scrollback and the cursor is clamped to the visible area
        val b = TerminalBuffer(4, 3)
        val e = TerminalEmulator(b)
        e.writeText("aaaa\r\nbbbb\r\ncccc")
        b.resize(4, 2)
        assertTrue(b.cursorRow in 0..1)
        assertEquals("cccc", b.lineText(1))
    }

    @Test
    fun wideCharTailInheritsColors() {
        // Wide character tails must inherit the color/properties of the head; otherwise, the right half-cell on a row with a background color will display the default background.
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("[47m中[0m")
        val line = b.visibleLines()[0]
        assertEquals('中'.code, line.cells[0].codePoint)
        assertTrue(line.cells[1].isWideTail)
        assertEquals(line.cells[0].bg, line.cells[1].bg)
        assertEquals(line.cells[0].attrs, line.cells[1].attrs)
    }

    @Test
    fun narrowOverwriteWideHeadClearsTail() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("中文\rxy")
        val line = b.visibleLines()[0]
        assertEquals('x'.code, line.cells[0].codePoint)
        assertFalse(line.cells[1].isWideTail)
        assertEquals('y'.code, line.cells[1].codePoint)
    }

    @Test
    fun writeOnWideTailClearsHead() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("中") // Occupies 0–1 column; cursor at position 2
        e.writeText("[1D") // Move the cursor one space to the left, so it rests on the tail.
        e.writeText("x")
        val line = b.visibleLines()[0]
        // the head is cleared and x is written at the former tail position
        assertEquals(' '.code, line.cells[0].codePoint)
        assertEquals('x'.code, line.cells[1].codePoint)
        assertFalse(line.cells[1].isWideTail)
    }

    @Test
    fun bracketedPasteModeTracked() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        assertFalse(b.bracketedPaste)
        e.writeText("[?2004h")
        assertTrue(b.bracketedPaste)
        e.writeText("[?2004l")
        assertFalse(b.bracketedPaste)
    }

    @Test
    fun osc52WritesClipboard() {
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        var clip = ""
        e.onClipboardWrite = { clip = it }
        // The Base64 encoding for "hi" is aGk=
        e.writeText("]52;c;aGk=")
        assertEquals("hi", clip)
    }

    @Test
    fun mouseTrackingModesNegotiated() {
        // The actual negotiation sequence sent by herdr: first "reset," then "enable" 1000/1002/1003/1015/1006
        val b = TerminalBuffer(10, 3)
        val e = TerminalEmulator(b)
        e.writeText("\u001b[?1006l\u001b[?1000l")
        assertEquals(0, b.mouseTracking)
        assertFalse(b.mouseSgr)
        e.writeText("\u001b[?1000h\u001b[?1002h\u001b[?1003h\u001b[?1015h\u001b[?1006h")
        assertEquals(1003, b.mouseTracking)
        assertTrue(b.mouseSgr)
    }

    @Test
    fun autoWrapMovesToNextLineAfterLastColumn() {
        val b = TerminalBuffer(5, 3)
        // After filling the first row, add one more character: it should move to the first column of the second row.
        "abcde".forEach { b.putChar(it.code) }
        assertTrue(b.pendingWrap)
        assertEquals(4, b.cursorCol)
        b.putChar('f'.code)
        assertEquals(1, b.cursorRow)
        assertEquals(1, b.cursorCol)
        assertEquals('f'.code, b.lineAt(1).cells[0].codePoint)
        assertTrue(b.lineAt(0).wrapped)
    }

    @Test
    fun autoWrapDisabledOverwritesLastColumn() {
        val b = TerminalBuffer(5, 3)
        b.autoWrap = false
        "abcde".forEach { b.putChar(it.code) }
        b.putChar('X'.code)
        // DECAWM off: no wrap; overwrite the last cell
        assertEquals(0, b.cursorRow)
        assertEquals(4, b.cursorCol)
        assertEquals('X'.code, b.lineAt(0).cells[4].codePoint)
    }

    @Test
    fun wideCharWrapAfterPendingWrap() {
        val b = TerminalBuffer(4, 3)
        "abcd".forEach { b.putChar(it.code) }
        b.putChar(0x4E2D) // U+4E2D
        assertEquals(1, b.cursorRow)
        assertEquals(2, b.cursorCol)
        assertEquals(0x4E2D, b.lineAt(1).cells[0].codePoint)
        assertTrue(b.lineAt(1).cells[1].isWideTail)
    }

    @Test
    fun wideCharAtLastColumnWithAutoWrapOffDoesNotWrap() {
        val b = TerminalBuffer(4, 3)
        b.autoWrap = false
        "abcd".forEach { b.putChar(it.code) }
        b.putChar(0x4E2D) // When DECAWM is off: no wrap; overwrite the last cell
        assertEquals(0, b.cursorRow)
        assertEquals(0x4E2D, b.lineAt(0).cells[3].codePoint)
    }

    @Test
    fun wideCharExactlyFillingLineEndWrapsOnNextChar() {
        // With the wide character header at cols-2 and the tail at cols-1, the row is exactly filled: the cursor must be clamped to the last column and suspended.
        // The deferred wrap. Before the fix, cursorCol landed on cols (out of bounds); the next character accessed cells[cursorCol]
        // Throws ArrayIndexOutOfBounds — the consumer coroutine terminates, and the terminal freezes after a long row of CJK text
        val b = TerminalBuffer(4, 3)
        "ab".forEach { b.putChar(it.code) }
        b.putChar(0x4E2D) // Chinese: Start at 2, end at 3, exactly filling the row
        assertEquals(3, b.cursorCol)
        b.putChar(0x6587) // Text: Trigger a latency-based line break; this used to crash here before the fix.
        assertEquals(1, b.cursorRow)
        assertEquals(2, b.cursorCol)
        assertEquals(0x6587, b.lineAt(1).cells[0].codePoint)
        assertTrue(b.lineAt(1).cells[1].isWideTail)
    }

    @Test
    fun wideCharExactlyFillingLineEndNarrowFollows() {
        // Same as the previous round, but follow narrow character limits: Use line breaks as needed, but do not go out of bounds.
        val b = TerminalBuffer(4, 3)
        "ab".forEach { b.putChar(it.code) }
        b.putChar(0x4E2D) // Fill in the row
        b.putChar('x'.code)
        assertEquals(1, b.cursorRow)
        assertEquals(1, b.cursorCol)
        assertEquals('x'.code, b.lineAt(1).cells[0].codePoint)
    }

    @Test
    fun eraseScreenKeepsCursor() {
        val b = TerminalBuffer(10, 3)
        b.moveTo(1, 4)
        b.eraseScreen()
        assertEquals(1, b.cursorRow)
        assertEquals(4, b.cursorCol)
    }

    @Test
    fun saveRestoreCursorKeepsPendingWrap() {
        val b = TerminalBuffer(5, 3)
        "abcde".forEach { b.putChar(it.code) }
        assertTrue(b.pendingWrap)
        b.saveCursor()
        b.moveTo(0, 0)
        b.restoreCursor()
        assertTrue(b.pendingWrap)
        assertEquals(4, b.cursorCol)
    }
}
