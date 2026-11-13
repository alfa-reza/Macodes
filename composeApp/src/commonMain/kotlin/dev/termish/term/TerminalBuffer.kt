package dev.termish.term

import kotlin.concurrent.Volatile

/** Single-character units. */
class TerminalCell {
    var codePoint: Int = ' '.code
    var attrs: Int = 0
    var fg: Int = DEFAULT_FG
    var bg: Int = DEFAULT_BG

    /** This unit is the “tail” of the previous wide character (spanning 2 columns). */
    var isWideTail: Boolean = false

    /** The number of columns occupied by this unit (1 = narrow, 2 = wide character header); pre-calculated when writing; wcwidth is no longer called during rendering. */
    var width: Int = 1

    /** OSC 8 hyperlink targets; non-hyperlink units are null. */
    var link: String? = null

    val isWide: Boolean get() = width == 2

    fun clear() {
        codePoint = ' '.code
        attrs = 0
        fg = DEFAULT_FG
        bg = DEFAULT_BG
        isWideTail = false
        width = 1
        link = null
    }

    fun copyFrom(o: TerminalCell) {
        codePoint = o.codePoint
        attrs = o.attrs
        fg = o.fg
        bg = o.bg
        isWideTail = o.isWideTail
        width = o.width
        link = o.link
    }
}

/** A single row of characters. */
class TerminalLine(
    var cols: Int,
) {
    var cells = Array(cols) { TerminalCell() }

    /** Whether this row began with an automatic line wrap (used for selection / scrollback, etc.). */
    var wrapped: Boolean = false

    /** Row content version number: Increments when a cell is actually overwritten, to enable row-level cache invalidation by the rendering layer. */
    @Volatile
    var version: Long = 0
        internal set

    /** Logical row identity: Preserved during cloning/COW copying; used to compare across buffering to determine whether "the same row has changed." */
    internal var identity: Any = Any()

    /** COW (Copy-on-Write) shared marker: Rows marked as `true` cannot be modified in place; they must be cloned before writing (similar to the `mosh shared_ptr` row semantics). */
    internal var shared: Boolean = false

    fun touch() {
        version++
    }

    fun clear() {
        wrapped = false
        for (c in cells) c.clear()
        touch()
    }

    /** Whether the entire row consists of default whitespace (no characters, colors, or attributes); used for safe discarding when resizing. */
    fun isBlank(): Boolean {
        for (c in cells) {
            if (c.codePoint != ' '.code || c.attrs != 0 || c.fg != DEFAULT_FG || c.bg != DEFAULT_BG) return false
        }
        return true
    }
}

/**
 * Terminal screen buffering: double buffering (main screen + alternate screen), scrollback, cursor, and modes.
 *
 * Standard screen: An [ArrayDeque] where the last [rows] rows are the visible screen, and the preceding rows are the scrollback.
 * (Maximum [maxScrollbackLines]). Secondary screen: Fixed rows × cols, no scrollback (fullscreen programs like vim or tmux).
 */
class TerminalBuffer(
    var cols: Int,
    var rows: Int,
    private val maxScrollbackLines: Int = 10_000,
) {
    private val normal = ArrayDeque<TerminalLine>()
    private var alt = Array(rows) { TerminalLine(cols) }

    /** The starting point of the source buffer in the normal screen window (the source row index corresponding to `normal[0]`) when last copied, used for incremental sync. */
    private var copySourceOffset: Int = 0

    var altScreen: Boolean = false
        private set

    var cursorRow: Int = 0
    var cursorCol: Int = 0
    var cursorVisible: Boolean = true

    var savedCursorRow: Int = 0
    var savedCursorCol: Int = 0

    /** DECSC saves delayed line break markers along with the current character set (for example, tmux has a dependency on complete state resumption). */
    var savedPendingWrap: Boolean = false
    var savedCharset: Charset = Charset.ASCII

    var scrollTop: Int = 0
    var scrollBottom: Int = rows - 1

    var autoWrap: Boolean = true
    var originMode: Boolean = false
    var insertMode: Boolean = false
    var applicationCursorKeys: Boolean = false

    /** bracketed paste (DECSET 2004): When enabled, pasted content must be enclosed within ESC[200~ ... ESC[201~. */
    var bracketedPaste: Boolean = false

    /** Mouse reporting mode: 0=off, 1000=X10 taps, 1002=button event drag, 1003=all events. */
    var mouseTracking: Int = 0

    /** Mouse coordinate format: true=SGR(1006), false=X10 byte offset. */
    var mouseSgr: Boolean = false

    /** Default foreground/background (RGB), with UI settings based on the current theme, used for response to OSC 10/11 color queries. */
    var defaultFgRgb: Int = 0xd5d8de
    var defaultBgRgb: Int = 0x0e0f13

    /** Default cursor color (RGB), used for response to OSC 12 queries; OSC 12 settings overwrite the default cursor color. */
    var defaultCursorRgb: Int = 0x58a6ff
    var applicationKeypad: Boolean = false

    /** DECSCUSR cursor style: 0=default blinking block, 1=blinking block, 2=steady block, 3=blinking underscore, 4=steady underscore, 5=blinking vertical line, 6=steady vertical line. */
    var cursorStyle: Int = 0

    /** OSC 12 sets the cursor color; DEFAULT_CURSOR indicates the theme’s default color. */
    var cursorColor: Int = DEFAULT_CURSOR

    /** DECSET 1004: Focus/defocus event (CSI I / CSI O) reporting. */
    var focusEvents: Boolean = false

    /** DECSET 1007: Use the scroll wheel as directional keys on the secondary screen (alternate scroll). */
    var alternateScroll: Boolean = false

    /** DECSET 1015: urxvt mouse coordinate format (mutually exclusive with SGR 1006). */
    var mouseUrxvt: Boolean = false

    /** OSC 8: Current hyperlink target; OSC 8;; Set to null at the end of the link. */
    var currentLink: String? = null

    var tabStops: BooleanArray = defaultTabStops(cols)

    /** Recently written graphic characters, provided for CSI Ps b (REP) to repeat. */
    var lastPrintedCodePoint: Int = 0

    /** The character set currently used to show text (DEC special graphics, etc.). */
    var currentCharset: Charset = Charset.ASCII

    /** Change count: Used by the UI to skip repaints. */
    var changeSequence: Long = 0
        private set

    private fun markChanged() {
        changeSequence++
    }

    init {
        // Initialize the default screen as a blank screen
        repeat(rows) { normal.addLast(TerminalLine(cols)) }
    }

    enum class Charset { ASCII, DEC_SPECIAL }

    // ---------- Row Access ----------

    private fun lineIndex(row: Int): Int = normal.size - rows + row

    private fun normalLine(row: Int): TerminalLine = normal[lineIndex(row)]

    fun altLine(row: Int): TerminalLine = alt[row]

    fun lineAt(row: Int): TerminalLine = if (altScreen) alt[row] else normalLine(row)

    /** Write access: when a row is COW-shared, clone it first to ensure shared rows (shadow history/UI references) are never modified in place. */
    private fun mutableLine(row: Int): TerminalLine {
        val line = lineAt(row)
        if (!line.shared) return line
        val clone = cloneLine(line)
        if (altScreen) alt[row] = clone else normal[lineIndex(row)] = clone
        return clone
    }

    fun visibleLineCount(): Int = rows

    /** Number of scrollback rows (main screen) or 0 (alternate screen). */
    fun scrollbackSize(): Int = if (altScreen) 0 else normal.size - rows

    /** Visible rows (top → bottom), for rendering purposes. */
    fun visibleLines(): List<TerminalLine> =
        if (altScreen) {
            alt.toList()
        } else {
            val start = normal.size - rows
            (start until normal.size).map { normal[it] }
        }

    /** Total number of absolute rows (scrollback + visible screen). */
    fun totalLines(): Int = if (altScreen) rows else normal.size

    /** Absolute row (index 0 = oldest row during scrollback). */
    fun absLine(index: Int): TerminalLine = if (altScreen) alt[index] else normal[index]

    /** The absolute row number of the cursor. */
    fun absCursorRow(): Int = if (altScreen) cursorRow else (normal.size - rows) + cursorRow

    /** Render a column: parse the effective foreground color (taking into account inverse, etc.). */
    fun resolveFg(
        cell: TerminalCell,
        defaultFg: Int,
        defaultBg: Int,
    ): Int {
        val fg = if (cell.fg == DEFAULT_FG) defaultFg else cell.fg
        val bg = if (cell.bg == DEFAULT_BG) defaultBg else cell.bg
        return when {
            cell.attrs and CellAttr.INVERSE != 0 -> bg
            else -> fg
        }
    }

    fun resolveBg(
        cell: TerminalCell,
        defaultFg: Int,
        defaultBg: Int,
    ): Int {
        val fg = if (cell.fg == DEFAULT_FG) defaultFg else cell.fg
        val bg = if (cell.bg == DEFAULT_BG) defaultBg else cell.bg
        return when {
            cell.attrs and CellAttr.INVERSE != 0 -> fg
            else -> bg
        }
    }

    // ---------- Write Operations ----------

    /** Insert a code point at the current cursor position (wide characters occupy 2 columns), automatically applying line breaks or scrolling as needed. */
    fun putChar(cp: Int) {
        // The last character falls at the end of the line (pendingWrap): handle the deferred line wrap first
        if (pendingWrap) {
            pendingWrap = false
            if (autoWrap) {
                val wrapLine = mutableLine(cursorRow)
                wrapLine.wrapped = true
                wrapLine.touch()
                newline()
            } else {
                // DECAWM Off: The cursor is positioned at the end of the row, and new characters overwrite the last character (xterm behavior)
                cursorCol = cols - 1
            }
        }
        val width = if (cp == 0) 1 else CharWidth.wcwidth(cp)
        val line = mutableLine(cursorRow)
        val col = cursorCol

        // Landing on a wide-character tail: clear the head first to avoid leaving half a wide character behind.
        if (col > 0 && line.cells[col].isWideTail) line.cells[col - 1].clear()

        if (width == 2) {
            if (cols < 2) {
                writeNarrow(cp)
                return
            }
            // Wide characters: If at the end of a row, perform a line break (do not perform a line break when DECAWM is disabled; instead, use narrow characters to fill the last column).
            if (col >= cols - 1) {
                if (autoWrap) {
                    newline()
                    putChar(cp)
                } else {
                    writeNarrow(cp)
                }
                return
            }
            lastPrintedCodePoint = cp
            val c = line.cells[col]
            c.clear()
            c.codePoint = cp
            c.attrs = currentAttrs
            c.fg = currentFg
            c.bg = currentBg
            c.width = 2
            c.link = currentLink
            val tail = line.cells[col + 1]
            tail.clear()
            // The tail must inherit the color/properties of the head; otherwise, the right half of wide characters will reveal the default background,
            // On rows with a background color (such as the message bar in pi), display as a black block covering half a character.
            tail.attrs = currentAttrs
            tail.fg = currentFg
            tail.bg = currentBg
            tail.isWideTail = true
            tail.link = currentLink
            // The tail covers the beginning of the wide character: Remove the original tail marker to prevent the character from hanging over the edge.
            if (col + 2 < cols && line.cells[col + 2].isWideTail) line.cells[col + 2].isWideTail = false
            cursorCol = col + 2
            // Wide characters exactly fill the row (ending in the last column): Same semantics as narrow characters at the end of a row—the cursor is clamped at
            // the last cell and arm a deferred line wrap; otherwise, if `cursorCol == cols`, the next character access
            // cells[cursorCol] out of bounds crash (results in coroutine termination and terminal freeze)
            if (cursorCol >= cols) {
                cursorCol = cols - 1
                pendingWrap = true
            }
            line.touch()
            markChanged()
            return
        }

        writeNarrow(cp)
    }

    private fun writeNarrow(cp: Int) {
        if (insertMode) {
            insertCells(1)
        }
        val line = mutableLine(cursorRow)
        // Landing on a wide-character tail: clear the head first
        if (cursorCol > 0 && line.cells[cursorCol].isWideTail) line.cells[cursorCol - 1].clear()
        lastPrintedCodePoint = cp
        val c = line.cells[cursorCol]
        c.clear()
        c.codePoint = cp
        c.attrs = currentAttrs
        c.fg = currentFg
        c.bg = currentBg
        c.link = currentLink
        // Remove trailing delimiters when wrapping wide characters
        if (cursorCol + 1 < cols && line.cells[cursorCol + 1].isWideTail) {
            line.cells[cursorCol + 1].isWideTail = false
        }

        if (cursorCol == cols - 1) {
            // End of line: Mark as "wrap" and apply latency to the line break until the next character (pendingWrap is processed at the putChar entry point).
            pendingWrap = true
        } else {
            cursorCol++
        }
        line.touch()
        markChanged()
    }

    /** CSI Ps b (REP): Repeat the most recently written graphic character Ps times at the current cursor position. */
    fun repeatChar(n: Int) {
        if (lastPrintedCodePoint == 0) return
        repeat(n.coerceIn(1, 1024)) { putChar(lastPrintedCodePoint) }
    }

    var pendingWrap: Boolean = false

    /** Properties/colors currently being written (updated by the parser during SGR). */
    var currentAttrs: Int = 0
    var currentFg: Int = DEFAULT_FG
    var currentBg: Int = DEFAULT_BG

    /** LF: Line break (affected by the origin/scroll region). */
    fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else {
            // The cursor may be located below the scrollable region (CUP moves there and then receives an LF): clamped at rows-1,
            // Otherwise, lineAt() will crash immediately after cursorRow goes out of bounds.
            cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
        }
        pendingWrap = false
        markChanged()
    }

    /** CR: Enter. */
    fun carriageReturn() {
        cursorCol = 0
        pendingWrap = false
        markChanged()
    }

    /** Line breaks (LF + automatic line break handling). */
    fun newline() {
        lineFeed()
        carriageReturn()
    }

    fun backspace() {
        if (pendingWrap) {
            pendingWrap = false
        } else if (cursorCol > 0) {
            cursorCol--
        }
        markChanged()
    }

    fun tab() {
        var i = cursorCol + 1
        while (i < cols && !tabStops[i]) i++
        cursorCol = minOf(i, cols - 1)
        pendingWrap = false
        markChanged()
    }

    fun moveTo(
        row: Int,
        col: Int,
    ) {
        val r = if (originMode) row + scrollTop else row
        cursorRow = r.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        pendingWrap = false
        markChanged()
    }

    fun moveCursor(
        dRow: Int,
        dCol: Int,
    ) {
        cursorRow = (cursorRow + dRow).coerceIn(0, rows - 1)
        cursorCol = (cursorCol + dCol).coerceIn(0, cols - 1)
        pendingWrap = false
        markChanged()
    }

    fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedPendingWrap = pendingWrap
        savedCharset = currentCharset
    }

    fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, cols - 1)
        pendingWrap = savedPendingWrap
        currentCharset = savedCharset
        markChanged()
    }

    // ---------- Erase ----------

    fun eraseToEndOfLine() {
        val line = mutableLine(cursorRow)
        for (i in cursorCol until cols) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun eraseFromStartOfLine() {
        val line = mutableLine(cursorRow)
        for (i in 0..cursorCol) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun eraseLine() {
        mutableLine(cursorRow).clear()
        markChanged()
    }

    fun eraseToEndOfScreen() {
        eraseToEndOfLine()
        for (r in cursorRow + 1 until rows) mutableLine(r).clear()
        markChanged()
    }

    fun eraseFromStartOfScreen() {
        eraseFromStartOfLine()
        for (r in 0 until cursorRow) mutableLine(r).clear()
        markChanged()
    }

    /** ED 2: Clear screen. xterm semantics: Do not move the cursor. */
    fun eraseScreen() {
        for (r in 0 until rows) mutableLine(r).clear()
        markChanged()
    }

    /** ED 3: Clear the screen and clear the scrollback (standard screen). */
    fun eraseScreenAndScrollback() {
        eraseScreen()
        if (!altScreen) {
            repeat(normal.size - rows) { normal.removeFirst() }
        }
        markChanged()
    }

    /** Erase a specified number of characters (ECH). */
    fun eraseChars(n: Int) {
        val line = mutableLine(cursorRow)
        for (i in cursorCol until minOf(cursorCol + n, cols)) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    // ---------- Insert/Delete ----------

    fun insertCells(n: Int) {
        val line = mutableLine(cursorRow)
        val count = minOf(n, cols - cursorCol)
        for (i in (cols - 1) downTo (cursorCol + count)) {
            line.cells[i].copyFrom(line.cells[i - count])
        }
        for (i in cursorCol until cursorCol + count) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun deleteCells(n: Int) {
        val line = mutableLine(cursorRow)
        val count = minOf(n, cols - cursorCol)
        for (i in cursorCol until cols - count) {
            line.cells[i].copyFrom(line.cells[i + count])
        }
        for (i in cols - count until cols) line.cells[i].clear()
        line.touch()
        markChanged()
    }

    fun insertLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in scrollBottom downTo cursorRow + count) {
            val src = lineAt(r - count) // Read-only
            val dst = mutableLine(r) // Write
            for (i in 0 until cols) {
                dst.cells[i].copyFrom(src.cells[i])
            }
            dst.touch()
        }
        for (r in cursorRow until cursorRow + count) mutableLine(r).clear()
        markChanged()
    }

    fun deleteLines(n: Int) {
        val count = minOf(n, scrollBottom - cursorRow + 1)
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in cursorRow until scrollBottom - count + 1) {
            val src = lineAt(r + count) // Read-only
            val dst = mutableLine(r) // Write
            for (i in 0 until cols) {
                dst.cells[i].copyFrom(src.cells[i])
            }
            dst.touch()
        }
        for (r in scrollBottom - count + 1..scrollBottom) mutableLine(r).clear()
        markChanged()
    }

    // ---------- Scroll ----------

    /** Scroll up [n] rows within the scroll region. */
    fun scrollUp(n: Int) {
        if (scrollTop == 0 && scrollBottom == rows - 1 && !altScreen) {
            // Fullscreen scroll: append a new row (enters the scrollback)
            repeat(n) {
                normal.addLast(TerminalLine(cols))
                if (maxScrollbackLines != Int.MAX_VALUE && normal.size > maxScrollbackLines + rows) {
                    normal.removeFirst()
                }
            }
        } else if (altScreen || scrollTop < scrollBottom) {
            val region = scrollBottom - scrollTop + 1
            val count = minOf(n, region)
            for (r in scrollTop until scrollTop + region - count) {
                copyLine(r + count, r)
            }
            for (r in scrollTop + region - count until scrollBottom + 1) {
                mutableLine(r).clear()
            }
        }
        markChanged()
    }

    fun scrollDown(n: Int) {
        if (scrollTop == 0 && scrollBottom == rows - 1 && !altScreen) {
            // Scroll fullscreen down (scrollback content returns to the screen)
            repeat(n) {
                if (normal.size > rows) {
                    normal.removeLast()
                    normal.addFirst(TerminalLine(cols))
                }
            }
        } else {
            val region = scrollBottom - scrollTop + 1
            val count = minOf(n, region)
            for (r in (scrollBottom) downTo scrollTop + count) {
                copyLine(r - count, r)
            }
            for (r in scrollTop until scrollTop + count) {
                mutableLine(r).clear()
            }
        }
        markChanged()
    }

    private fun copyLine(
        from: Int,
        to: Int,
    ) {
        val src = lineAt(from)
        val dst = mutableLine(to)
        for (i in 0 until cols) dst.cells[i].copyFrom(src.cells[i])
        dst.wrapped = src.wrapped
        dst.touch()
    }

    // ---------- Mode Toggle ----------

    fun enterAltScreen(clear: Boolean = false) {
        if (altScreen) return
        altScreen = true
        // When entering the alternate screen via 1049, the screen should be cleared (xterm behavior); 1047/47 does not clear the screen.
        // If not cleared, the previous vim/tmux remnants will cause a one-frame flicker on re-entry.
        if (clear) for (r in 0 until rows) mutableLine(r).clear()
        cursorRow = 0
        cursorCol = 0
        savedCursorRow = 0
        savedCursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        markChanged()
    }

    fun leaveAltScreen() {
        if (!altScreen) return
        altScreen = false
        cursorRow = 0
        cursorCol = 0
        markChanged()
    }

    fun setScrollRegion(
        top: Int,
        bottom: Int,
    ) {
        if (top >= bottom) return
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
        cursorRow = 0
        cursorCol = 0
        markChanged()
    }

    fun resetTabStops() {
        tabStops = defaultTabStops(cols)
        markChanged()
    }

    fun setTabStop(col: Int) {
        if (col in 0 until cols) tabStops[col] = true
        markChanged()
    }

    fun clearTabStop(col: Int) {
        if (col in 0 until cols) tabStops[col] = false
        markChanged()
    }

    // ---------- Size Adjustment ----------

    fun resize(
        newCols: Int,
        newRows: Int,
    ) {
        if (newCols == cols && newRows == rows) return

        // Adjust the row width for standard screens (COW: Clone shared rows first, then modify them)
        for (i in normal.indices) {
            val line = normal[i]
            if (line.shared) normal[i] = cloneLine(line)
            resizeLine(normal[i], newCols)
        }
        // Adjust the secondary screen
        val newAlt = Array(newRows) { TerminalLine(newCols) }
        for (r in 0 until minOf(rows, newRows)) {
            val src = alt[r]
            val srcMutable = if (src.shared) cloneLine(src) else src
            resizeLine(srcMutable, newCols)
            copyInto(newAlt[r], srcMutable, newCols)
        }
        alt = newAlt

        // Adjust row count
        if (newRows < rows) {
            // If the screen gets shorter (e.g., when the keyboard pops up): prioritize discarding blank rows below the cursor to ensure the cursor and
            // The prompt remains in the visible area (xterm behavior); any remaining reduction is handled by
            // "Visible screen = the last newRows rows of the normal buffer" naturally pushes the top rows into the scrollback.
            val cursorAbs = normal.size - rows + cursorRow
            var toDrop = rows - newRows
            while (toDrop > 0 && normal.size - 1 > cursorAbs && normal.last().isBlank()) {
                normal.removeLast()
                toDrop--
            }
            cursorRow = (cursorAbs - (normal.size - newRows)).coerceIn(0, newRows - 1)
        } else if (newRows > rows) {
            // Screen gets bigger: take rows out of the scrollback or create new blank rows
            repeat(newRows - rows) {
                normal.addLast(TerminalLine(newCols))
            }
        }
        // Limit the total scrollback size (Int.MAX_VALUE = no limit; used by the shadow terminal; the normal UI buffer keeps its limit)
        while (maxScrollbackLines != Int.MAX_VALUE && normal.size > maxScrollbackLines + newRows) {
            normal.removeFirst()
        }

        cols = newCols
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        savedCursorRow = savedCursorRow.coerceIn(0, rows - 1)
        savedCursorCol = savedCursorCol.coerceIn(0, cols - 1)
        tabStops = defaultTabStops(cols)
        markChanged()
    }

    // ---------- Copy in its entirety (for mosh KMP shadow state / UI sync) ----------

    /** COW shallow forking: row objects share memory with the source (copy-on-write), O(number of rows) rather than O(cells).
     *  For mosh shadow state saving (row copies corresponding to server-side timestamped states). */
    fun shallowFork(): TerminalBuffer {
        // rows=0 Construction: The `init` method of the main constructor pre-creates `rows` blank screen rows; construct directly and then append.
        // The source rows add `rows` phantom blank lines to the top of each fork—phantoms accumulate with fork generations (per frame
        // +rows), the longer the session, the higher the cost of forking/GC; rows=0 results in zero rows and zero phantom objects
        val dst = TerminalBuffer(cols, 0, maxScrollbackLines)
        dst.cols = cols
        dst.rows = rows
        for (line in normal) {
            line.shared = true
            dst.normal.addLast(line)
        }
        dst.alt =
            Array(rows) { idx ->
                alt[idx].also { it.shared = true }
            }
        dst.copyStateFieldsFrom(this)
        return dst
    }

    /** Deep copy: Used to save shadow terminal state forks in mosh SSP. */
    fun deepCopy(): TerminalBuffer {
        val dst = TerminalBuffer(cols, rows, maxScrollbackLines)
        dst.copyContentFrom(this)
        return dst
    }

    /** Replace the current buffer with the state shown for `other` (keeping the instance unchanged for UI rendering).
     *  Line-level incremental: If the logical row is identical (same identity) and the version has not changed, reuse the target row and clone only the changed rows,
     *  Avoid cloning all cells every frame when the scrollback is large.
     *  Returns whether anything actually changed: if nothing changed, the caller may skip the redraw (after prediction coalescing this often pushes into the visual
     *  (exactly the same state). */
    fun copyContentFrom(other: TerminalBuffer): Boolean {
        var changed = false
        val oldCols = cols
        val oldRows = rows
        val oldSize = normal.size
        val oldOffset = copySourceOffset

        // When the target buffer has an upper limit, only copy the retained portion (the last maxScrollback+rows rows),
        // Avoid cloning everything every frame and then discarding it when the shadow size exceeds the limit (if the shadow size is Int.MAX_VALUE, perform a full copy).
        val keep =
            if (maxScrollbackLines == Int.MAX_VALUE) {
                other.normal.size
            } else {
                minOf(other.normal.size, maxScrollbackLines + other.rows)
            }
        val offset = other.normal.size - keep

        cols = other.cols
        rows = other.rows

        // Incremental alignment: the source scrollback only grows (unbounded deletes no rows); the target window start moves forward by `drop` rows;
        // Reuse the remaining rows by comparing them row by row with the source based on identity/version, and append new rows at the end.
        // When the shape changes (resize) or the scrollback is cleared (ED3), `drop` goes out of bounds → full rebuild.
        val drop = if (oldCols == other.cols && oldRows == other.rows) offset - oldOffset else -1
        if (drop < 0 || drop > oldSize) {
            normal.clear()
            for (j in offset until other.normal.size) {
                normal.addLast(cloneLine(other.normal[j]))
            }
            changed = true
        } else {
            if (drop > 0) changed = true
            repeat(drop) { normal.removeFirst() }
            var dstIdx = 0
            for (j in offset until other.normal.size) {
                val src = other.normal[j]
                if (dstIdx < oldSize - drop) {
                    val dst = normal[dstIdx]
                    if (dst.identity !== src.identity || dst.version != src.version) {
                        normal[dstIdx] = cloneLine(src)
                        changed = true
                    } else if (dst.wrapped != src.wrapped) {
                        dst.wrapped = src.wrapped
                        changed = true
                    }
                } else {
                    normal.addLast(cloneLine(src))
                    changed = true
                }
                dstIdx++
            }
        }
        copySourceOffset = offset

        // Alternate-screen rows are also reused incrementally based on identity/version (previously, every frame fully cloned all rows,
        // Alt-screen programs like vim and htop copy every output frame as-is (O(number of rows × number of columns)).
        if (alt.size != other.alt.size) {
            alt = Array(other.rows) { cloneLine(other.alt[it]) }
            changed = true
        } else {
            for (r in other.alt.indices) {
                val src = other.alt[r]
                val dst = alt[r]
                if (dst.identity !== src.identity || dst.version != src.version) {
                    alt[r] = cloneLine(src)
                    changed = true
                } else if (dst.wrapped != src.wrapped) {
                    dst.wrapped = src.wrapped
                    changed = true
                }
            }
        }
        if (copyStateFieldsFrom(other)) changed = true
        if (changed) markChanged()
        return changed
    }

    /** Copy the display-state fields; returns whether any field actually changed. */
    private fun copyStateFieldsFrom(other: TerminalBuffer): Boolean {
        var changed = false
        // Comparison and assignment of enumeration/Int/Boolean scalar fields in groups
        if (altScreen != other.altScreen) {
            altScreen = other.altScreen
            changed = true
        }
        if (cursorRow != other.cursorRow) {
            cursorRow = other.cursorRow
            changed = true
        }
        if (cursorCol != other.cursorCol) {
            cursorCol = other.cursorCol
            changed = true
        }
        if (cursorVisible != other.cursorVisible) {
            cursorVisible = other.cursorVisible
            changed = true
        }
        if (savedCursorRow != other.savedCursorRow) {
            savedCursorRow = other.savedCursorRow
            changed = true
        }
        if (savedCursorCol != other.savedCursorCol) {
            savedCursorCol = other.savedCursorCol
            changed = true
        }
        if (savedPendingWrap != other.savedPendingWrap) {
            savedPendingWrap = other.savedPendingWrap
            changed = true
        }
        if (savedCharset != other.savedCharset) {
            savedCharset = other.savedCharset
            changed = true
        }
        if (scrollTop != other.scrollTop) {
            scrollTop = other.scrollTop
            changed = true
        }
        if (scrollBottom != other.scrollBottom) {
            scrollBottom = other.scrollBottom
            changed = true
        }
        if (autoWrap != other.autoWrap) {
            autoWrap = other.autoWrap
            changed = true
        }
        if (originMode != other.originMode) {
            originMode = other.originMode
            changed = true
        }
        if (insertMode != other.insertMode) {
            insertMode = other.insertMode
            changed = true
        }
        if (applicationCursorKeys != other.applicationCursorKeys) {
            applicationCursorKeys = other.applicationCursorKeys
            changed = true
        }
        if (bracketedPaste != other.bracketedPaste) {
            bracketedPaste = other.bracketedPaste
            changed = true
        }
        if (mouseTracking != other.mouseTracking) {
            mouseTracking = other.mouseTracking
            changed = true
        }
        if (mouseSgr != other.mouseSgr) {
            mouseSgr = other.mouseSgr
            changed = true
        }
        if (defaultFgRgb != other.defaultFgRgb) {
            defaultFgRgb = other.defaultFgRgb
            changed = true
        }
        if (defaultBgRgb != other.defaultBgRgb) {
            defaultBgRgb = other.defaultBgRgb
            changed = true
        }
        if (defaultCursorRgb != other.defaultCursorRgb) {
            defaultCursorRgb = other.defaultCursorRgb
            changed = true
        }
        if (applicationKeypad != other.applicationKeypad) {
            applicationKeypad = other.applicationKeypad
            changed = true
        }
        if (cursorStyle != other.cursorStyle) {
            cursorStyle = other.cursorStyle
            changed = true
        }
        if (cursorColor != other.cursorColor) {
            cursorColor = other.cursorColor
            changed = true
        }
        if (focusEvents != other.focusEvents) {
            focusEvents = other.focusEvents
            changed = true
        }
        if (alternateScroll != other.alternateScroll) {
            alternateScroll = other.alternateScroll
            changed = true
        }
        if (mouseUrxvt != other.mouseUrxvt) {
            mouseUrxvt = other.mouseUrxvt
            changed = true
        }
        if (currentLink != other.currentLink) {
            currentLink = other.currentLink
            changed = true
        }
        if (!tabStops.contentEquals(other.tabStops)) {
            tabStops = other.tabStops.copyOf()
            changed = true
        }
        if (lastPrintedCodePoint != other.lastPrintedCodePoint) {
            lastPrintedCodePoint = other.lastPrintedCodePoint
            changed = true
        }
        if (currentCharset != other.currentCharset) {
            currentCharset = other.currentCharset
            changed = true
        }
        if (pendingWrap != other.pendingWrap) {
            pendingWrap = other.pendingWrap
            changed = true
        }
        return changed
    }

    private fun cloneLine(src: TerminalLine): TerminalLine {
        // Use `cells.size` instead of `cols`: to prevent a momentary mismatch between `cols` and `cells` during resizing.
        val n = src.cells.size
        val dst = TerminalLine(n)
        for (i in 0 until n) dst.cells[i].copyFrom(src.cells[i])
        dst.wrapped = src.wrapped
        // Preserve logical identity and content version: Incremental line-by-line comparison across buffers depends on this
        dst.identity = src.identity
        dst.version = src.version
        dst.shared = false
        return dst
    }

    private fun resizeLine(
        line: TerminalLine,
        newCols: Int,
    ) {
        if (line.cells.size == newCols) return
        val newCells = Array(newCols) { TerminalCell() }
        val n = minOf(line.cells.size, newCols)
        for (i in 0 until n) {
            newCells[i].copyFrom(line.cells[i])
        }
        // Correction: If truncation causes the trailing part of a wide character to be left hanging, remove it.
        line.cols = newCols // Must sync with `cells`: `cloneLine` reads `cells` in a loop based on `cols`
        line.cells = newCells
        line.touch()
    }

    private fun copyInto(
        dst: TerminalLine,
        src: TerminalLine,
        n: Int,
    ) {
        for (i in 0 until n) dst.cells[i].copyFrom(src.cells[i])
    }

    companion object {
        private fun defaultTabStops(cols: Int): BooleanArray {
            val arr = BooleanArray(cols)
            var i = 8
            while (i < cols) {
                arr[i] = true
                i += 8
            }
            return arr
        }
    }
}
