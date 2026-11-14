package dev.macodes.term

import kotlin.test.Test
import kotlin.test.assertEquals

/** TerminalSelection Copy Semantics: Do not insert line breaks when continuing on the next row; preserve line breaks between regular rows. */
class TerminalSelectionTest {
    @Test
    fun wrappedRowsJoinWithoutNewline() {
        val b = TerminalBuffer(5, 3)
        "abcdefghij".forEach { b.putChar(it.code) }
        // 10 characters wrapped onto two rows: line0="abcde" (wrapped), line1="fghij"
        val sel = TerminalSelection(b)
        sel.start(0, 0)
        sel.extend(1, 4)

        assertEquals("abcdefghij", sel.selectedText())
    }

    @Test
    fun unwrappedRowsKeepNewline() {
        val b = TerminalBuffer(5, 3)
        "abc".forEach { b.putChar(it.code) }
        b.newline()
        "def".forEach { b.putChar(it.code) }
        val sel = TerminalSelection(b)
        sel.start(0, 0)
        sel.extend(1, 2)

        assertEquals("abc\ndef", sel.selectedText())
    }

    @Test
    fun selectionStartingInsideWrappedRowStartsNewParagraph() {
        val b = TerminalBuffer(5, 3)
        "abcdefghij".forEach { b.putChar(it.code) }
        val sel = TerminalSelection(b)
        // Start selecting from the middle of the next row (row 1); do not include any content from the preceding row that was not selected.
        sel.start(1, 2)
        sel.extend(1, 4)

        assertEquals("hij", sel.selectedText())
    }
}
