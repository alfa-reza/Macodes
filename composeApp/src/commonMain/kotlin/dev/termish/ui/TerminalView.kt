package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.termish.term.CellAttr
import dev.termish.term.DEFAULT_BG
import dev.termish.term.DEFAULT_CURSOR
import dev.termish.term.DEFAULT_FG
import dev.termish.term.TerminalBuffer
import dev.termish.term.TerminalCell
import dev.termish.term.TerminalLine
import dev.termish.term.TerminalPalette
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.cjkFontFamily
import dev.termish.util.monospaceFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Pre-computed mapping of the 16 basic color values to the ANSI index (replaces the per-frame linear `indexOf` operation)
private val BASIC_16_INDEX: Map<Int, Int> =
    TerminalPalette.BASIC_16.mapIndexed { i, v -> v to i }.toMap()

private fun cellColor(
    c: Int,
    theme: TerminalTheme,
): Color {
    val idx = BASIC_16_INDEX[c]
    return if (idx != null) theme.ansi(idx) else Color(0xFF000000.toInt() or c)
}

/** Choose a black or white foreground based on the background brightness to ensure readability when the background color is set remotely and the default foreground is retained. */
private fun contrastTextColor(bg: Color): Color {
    val l = bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f
    return if (l >= 0.5f) Color.Black else Color.White
}

private fun cellColors(
    cell: TerminalCell,
    theme: TerminalTheme,
): Pair<Color, Color> {
    // xterm convention: BOLD + base 8 colors → highlight to 8–15
    val fgIdx = if (cell.attrs and CellAttr.BOLD != 0 && cell.fg in 0..7) cell.fg + 8 else cell.fg
    val bg = if (cell.bg == DEFAULT_BG) theme.background() else cellColor(cell.bg, theme)
    val fg =
        when {
            cell.fg != DEFAULT_FG -> cellColor(fgIdx, theme)
            // The remote server only sets the background color (for code blocks/highlighted areas); by default, the foreground color automatically adjusts to black or white based on the background’s brightness,
            // Avoid rendering dark backgrounds and dark text as black blocks on light themes.
            cell.bg != DEFAULT_BG -> contrastTextColor(bg)
            else -> theme.foreground()
        }
    return if (cell.attrs and CellAttr.INVERSE != 0) Pair(bg, fg) else Pair(fg, bg)
}

private fun runFontWeight(attrs: Int): FontWeight? = if (attrs and CellAttr.BOLD != 0) FontWeight.Bold else null

/**
 * Add Unicode code points across platforms. Use project-specific names to avoid conflicts with members of the same name in the JVM StringBuilder.
 * shadowing the extension, causing the JVM and Kotlin/Native to resolve to different implementations.
 */
private fun StringBuilder.appendUnicodeCodePoint(cp: Int) {
    if (cp <= 0xFFFF) {
        append(cp.toChar())
    } else {
        val x = cp - 0x10000
        append((0xD800 + (x shr 10)).toChar())
        append((0xDC00 + (x and 0x3FF)).toChar())
    }
}

/**
 * Code points → String. Do not depend on the JVM-specific `StringBuilder.appendCodePoint(cp)`:
 * Kotlin/Native’s `StringBuilder` does not have a public `appendCodePoint` member (the stdlib only contains
 * (internal implementation), it will parse to the private extension of the `Unit` returned by this file → `Unit.toString()`,
 * On iOS, each wide character is rendered as the literal string "kotlin.Unit" (the root cause of Chinese garbled characters); on the JVM, however, due to
 * The `java.lang.StringBuilder` member shadows the private extension and is exactly right — a platform-semantics fork; disabled.
 */
private fun codePointToString(cp: Int): String =
    when {
        cp <= 0xFFFF -> cp.toChar().toString()
        else -> {
            val x = cp - 0x10000
            val hi = 0xD800 + (x shr 10)
            val lo = 0xDC00 + (x and 0x3FF)
            "${hi.toChar()}${lo.toChar()}"
        }
    }

/** One measured text item per row: x is the pixel offset within the row, and y is determined by the row position at the time of drawing. */
private class CachedTextRun(
    val layout: androidx.compose.ui.text.TextLayoutResult,
    val x: Float,
)

private class LineText(
    val runs: List<CachedTextRun>,
)

/** Cache the measured text run by (row identity, version): the row content remains unchanged (same identity,
 *  When the version has not been bumped, repainting/translating only replays the layout and skips text measurement—fullscreen text layout is
 *  The most expensive part of each frame. Only visible rows are drawn, so the LRU capacity should be several times the number of visible screens.
 *  The outer `remember(style, wideStyle, theme)` is responsible for overall invalidation (a change in font size or theme triggers a rebuild). */
private class LineTextCache(
    private val capacity: Int = 256,
) {
    private class Entry(
        val version: Long,
        val text: LineText,
    )

    // LinkedHashMap preserves insertion order: re-inserts at the end during get/put operations for LRU implementation
    // (The `LinkedHashMap` in Kotlin Common does not have `accessOrder` or `removeEldestEntry`.)
    private val map = LinkedHashMap<Any, Entry>()

    fun get(line: TerminalLine): LineText? {
        val e = map.remove(line.identity) ?: return null
        map[line.identity] = e // Reinsert at the end (mark as recently used)
        return if (e.version == line.version) e.text else null
    }

    fun put(
        line: TerminalLine,
        text: LineText,
    ) {
        map.remove(line.identity)
        map[line.identity] = Entry(line.version, text)
        if (map.size > capacity) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove() // Remove the longest-unused items
            }
        }
    }
}

private fun currentStartAbs(
    buffer: TerminalBuffer,
    scrollOffset: Int,
): Int {
    val scrollback = buffer.scrollbackSize()
    val offset = scrollOffset.coerceIn(0, scrollback)
    return (buffer.totalLines() - buffer.rows - offset).coerceAtLeast(0)
}

/** OSC 8 whitelist of protocols that can be opened via links: Only allow standard Web and email to prevent remote content from triggering file://, intent://, etc. */
private fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme in setOf("http", "https", "mailto")
}

@Composable
fun TerminalView(
    controller: TerminalController,
    theme: TerminalTheme,
    fontSizeSp: Float,
    targetCols: Int = 0,
    /** Height (px) of keyboard/toolbar overlap at the bottom of the canvas: Used to translate the canvas upward to ensure the cursor remains visible. */
    coveredBottomPx: Float = 0f,
    /** Whether the soft keyboard is up: pan the canvas upward only while the keyboard is up, to avoid the always-on toolbar pushing the top (e.g. herdr's switch menu) off the screen. */
    keyboardVisible: Boolean = false,
    /** Callback after measuring the actual canvas dimensions for the first time (used to establish connections based on actual rows and columns, avoiding starting the PTY at 80x24). */
    onReady: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    /**
     * Is the canvas size stable (permanent elements upstream in the layout chain have been accounted for)? When false, do not resize,
     * Do not report [onReady] — the first-frame intermediate size (e.g., toolbar not yet measured, bottom padding not yet
     * will start the PTY / resize with a wrong row count, and TUI (herdr) jumps with the full re-layout.
     */
    sizeStable: Boolean = true,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    var scrollOffset by remember { mutableStateOf(0) }
    // Rolling Decimal Accumulator (Conversion by Row)
    var scrollAccum by remember { mutableFloatStateOf(0f) }
    // Set the flag after the first valid canvas measurement (connect with the real rows/cols)
    var readySent by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Scope of the inertial scroll wheel animation: `rememberCoroutineScope` — on some platforms, scheduling does not guarantee immediate execution,
    // use an explicit `Main` scope and cancel it when the composition is destroyed
    val inertiaScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(Unit) {
        onDispose { inertiaScope.cancel() }
    }

    val fontFamily = monospaceFontFamily()
    val cjkFont = cjkFontFamily()
    // Target column width mode: Glyph width is linearly proportional to font size; use 12sp to measure the required font size.
    // `monospaceFontFamily()` returns a new instance on every composition
    // Using this as a key will cause the sample here and below to be recalculated for each frame.
    val effectiveFontSizeSp =
        remember(canvasSize.width, targetCols, fontSizeSp) {
            if (targetCols > 0 && canvasSize.width > 0) {
                val ref = textMeasurer.measure("0".repeat(16), TextStyle(fontFamily = fontFamily, fontSize = 12.sp))
                val refCellW = ref.size.width.toFloat() / 16f
                val desiredCellW = canvasSize.width.toFloat() / targetCols
                var font = (12f * desiredCellW / refCellW).coerceIn(4f, 32f)
                // Text measurements are rounded to whole pixels; directly calculated `cellW` values may be slightly too large, resulting in the actual number of columns being one less than the target.
                // (Leave a blank space on the right side of the screen). Test and adjust step by step: if there are not enough columns, use a ratio to reduce the font size and leave some margin.
                var guard = 0
                while (guard < 5) {
                    val sample =
                        textMeasurer.measure(
                            "0".repeat(16),
                            TextStyle(fontFamily = fontFamily, fontSize = font.sp),
                        )
                    val measuredCellW = sample.size.width.toFloat() / 16f
                    val cols = (canvasSize.width / measuredCellW).toInt().coerceAtLeast(1)
                    if (cols >= targetCols || font <= 4f) break
                    font = (font * (desiredCellW / measuredCellW) * 0.99f).coerceIn(4f, 32f)
                    guard++
                }
                font
            } else {
                fontSizeSp
            }
        }

    val fontSize = effectiveFontSizeSp.sp
    val style = TextStyle(fontFamily = fontFamily, fontSize = fontSize)
    // JetBrains Mono does not include CJK glyphs: Use cjkFontFamily() for wide characters (Chinese/Japanese/Korean/full-width/emoji).
    // (Explicitly load PingFang SC on iOS; use the system fallback on other platforms) to prevent missing characters from showing as blank blocks or garbled text.
    // Character width is still measured using the JetBrains Mono standard, with wide characters naturally occupying 2 spaces.
    val wideStyle = style.copy(fontFamily = cjkFont)
    // Use a longer sample string to test single-cell width: Avoid cumulative errors caused by rounding the pixel width of a single character at small font sizes.
    val sample = remember(effectiveFontSizeSp) { textMeasurer.measure("0".repeat(16), style) }
    val cellW = (sample.size.width.toFloat() / 16f).coerceAtLeast(1f)
    val cellH = (sample.size.height.toFloat() * 1.2f).coerceAtLeast(1f)
    val textTop = (cellH - sample.size.height) / 2f
    // The closure is created only once during the initial composition; directly capturing cellW/cellH will
    // Lock old values: After changing the font size, rotating, or resizing, tap → causes all grid conversions to become misaligned.
    // The `State` instance returned by `rememberUpdatedState` is stable; the old closure still reads real-time values.
    val currentCellW by rememberUpdatedState(cellW)
    val currentCellH by rememberUpdatedState(cellH)

    // Cache TextStyle based on (ARGB, bold, narrow/wide font) to avoid calling `style.copy` for every run in every frame.
    val textStyleCache = remember(style, wideStyle) { HashMap<Long, TextStyle>() }

    fun cachedTextStyle(
        base: TextStyle,
        fg: Color,
        bold: Boolean,
    ): TextStyle {
        val key =
            ((fg.toArgb().toLong() and 0xffffffffL) shl 2) or
                (if (bold) 2L else 0L) or
                (if (base === wideStyle) 1L else 0L)
        return textStyleCache.getOrPut(key) {
            base.copy(color = fg, fontWeight = if (bold) FontWeight.Bold else null)
        }
    }

    val buffer = controller.buffer
    val latestCanvasSize by rememberUpdatedState(canvasSize)
    val uriHandler = LocalUriHandler.current

    // Row-level text rendering cache: The key (identity, version) is guaranteed to change when the content changes via COW/incremental copying.
    // when the style/theme changes, the remember rebuild invalidates everything wholesale; on a hit, redrawing just
    // Replay TextLayoutResult; when scrolling, full-screen translating hits almost all elements.
    val lineTextCache = remember(style, wideStyle, theme) { LineTextCache() }

    // Measure the entire text of a row: for narrow characters, count them consecutively as (fg, attrs); for wide characters, measure them individually.
    // Be consistent with the traversal rules for "pass" with a background or underline, as described below.
    fun measureLineText(line: TerminalLine): LineText {
        val runs = ArrayList<CachedTextRun>()
        var i = 0
        while (i < buffer.cols) {
            val cell = line.cells[i]
            if (cell.isWideTail) {
                i++
                continue
            }
            if (cell.codePoint == ' '.code && cell.attrs and (CellAttr.UNDERLINE or CellAttr.INVERSE) == 0) {
                i++
                continue
            }

            val (fgColor, _) = cellColors(cell, theme)
            val runStyle = cachedTextStyle(style, fgColor, cell.attrs and CellAttr.BOLD != 0)
            var j = i
            val sb = StringBuilder()
            while (j < buffer.cols) {
                val c2 = line.cells[j]
                if (c2.isWideTail || c2.fg != cell.fg || c2.attrs != cell.attrs) break
                if (c2.width != 1) break
                if (c2.codePoint == ' '.code && c2.attrs and (CellAttr.UNDERLINE or CellAttr.INVERSE) == 0) break
                sb.appendUnicodeCodePoint(c2.codePoint)
                j++
            }
            if (sb.isNotEmpty()) {
                runs.add(CachedTextRun(textMeasurer.measure(sb.toString(), runStyle), i * cellW))
            }
            // Wide characters are measured separately.
            if (j < buffer.cols && !line.cells[j].isWideTail && line.cells[j].width == 2) {
                val wide = line.cells[j]
                runs.add(
                    CachedTextRun(
                        textMeasurer.measure(
                            codePointToString(wide.codePoint),
                            cachedTextStyle(
                                wideStyle,
                                cellColors(wide, theme).first,
                                wide.attrs and CellAttr.BOLD != 0,
                            ),
                        ),
                        j * cellW,
                    ),
                )
                j += 2
            }
            i = j
        }
        return LineText(runs)
    }

    // Touch → Terminal mouse events (X10/SGR): After enabling mouse reporting in TUI applications such as herdr, vim, and htop,
    // Touch gestures are mapped to left-click, drag, and release; scroll gestures are mapped to the scroll wheel. Coordinates are 1-based grid coordinates.
    fun sendMouseEvent(
        btn: Int,
        col: Int,
        row: Int,
        release: Boolean,
    ) {
        val c = (col + 1).coerceIn(1, buffer.cols)
        val r = (row + 1).coerceIn(1, buffer.rows)
        if (buffer.mouseUrxvt) {
            // urxvt 1015: CSI b;x;y M/m, no proprietary prefix
            controller.sendBytes("\u001b[$btn;$c;$r${if (release) "m" else "M"}".encodeToByteArray())
        } else if (buffer.mouseSgr) {
            controller.sendText("\u001b[<$btn;$c;$r${if (release) "m" else "M"}")
        } else {
            // X10: press sends the button number, release sends 3; coordinates are bytes offset by 32, max 223
            if (c > 223 || r > 223) return
            val b = if (release) 3 else btn
            controller.sendBytes(
                byteArrayOf(
                    0x1b,
                    '['.code.toByte(),
                    'M'.code.toByte(),
                    (32 + b).toByte(),
                    (32 + c).toByte(),
                    (32 + r).toByte(),
                ),
            )
        }
    }

    fun pointerCell(pos: Offset): Pair<Int, Int> =
        ((pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)) to
            ((pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1))

    // Fill with blank rows when content does not fill a full screen: Share the same instance (read-only) to ensure the stability of the row cache key,
    // Avoid cache garbage generated by creating new instances every frame
    val emptyLine = remember(buffer.cols) { TerminalLine(buffer.cols) }

    // Recalculate rows and columns and sync PTY when dimensions or font sizes change; sizeStable=false (upstream layout
    // undetermined; the canvas size is still intermediate) — skip everything; re-run the effect once when it stabilizes
    LaunchedEffect(canvasSize, effectiveFontSizeSp, sizeStable) {
        if (!sizeStable || canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = (canvasSize.width / cellW).toInt().coerceAtLeast(1)
        val rows = (canvasSize.height / cellH).toInt().coerceAtLeast(1)
        controller.resize(cols, rows, canvasSize.width, canvasSize.height)
        if (!readySent) {
            readySent = true
            onReady(cols, rows)
        }
    }

    Canvas(
        modifier
            // When the on-screen keyboard pops up and the cursor is obscured by the keyboard region, translate the cursor upward to ensure it remains visible (without changing the PTY).
            // Size: Avoid repeated reflow caused by fullscreen programs expanding and contracting with keyboard input. Toolbars and navigation bars should remain fixed and not translate;
            // Do not translate while the user is scrolling the scrollback. Put the translation math inside the `graphicsLayer` block: `frame` (output)
            // Changes to `scrollOffset` only trigger a layer update; the entire `TerminalView` is no longer subject to reassembly.
            .graphicsLayer {
                @Suppress("UNUSED_VARIABLE")
                val redraw = controller.frame
                val panUp =
                    if (!keyboardVisible || scrollOffset != 0 || size.height <= 0 || coveredBottomPx <= 0f) {
                        0f
                    } else {
                        val cursorBottomY =
                            (buffer.absCursorRow() - currentStartAbs(buffer, scrollOffset) + 1) * cellH
                        val visibleBottomY = size.height - coveredBottomPx
                        if (cursorBottomY > visibleBottomY) {
                            (cursorBottomY - visibleBottomY).coerceAtMost(size.height)
                        } else {
                            0f
                        }
                    }
                translationY = -panUp
            }.onSizeChanged { size ->
                canvasSize = size
            }
            // Touch → Mouse events: When TUI reporting is enabled (1000/1002/1003), it takes over gesture handling,
            // Otherwise, do not consume the event; maintain the default behavior of keeping focus on keyboard input, selection, or scrollback.
            // Gesture semantics (single event source in mouse mode; scrollable elements sync their yieldings):
            //   - Tap → left button down/up (release is sent at the pressed cell, so finger jitter is not judged a drag by herdr's 1-cell drag threshold)
            //   - Single-finger vertical drag → scroll wheel (swipe down = scroll back = wheel up 64, swipe up = down = 65); the drop point follows the finger
            //   - Single-finger horizontal drag / Two-finger drag → Mouse drag motion (1002/1003, selecting text, dragging dividers, dragging within a pane)
            .pointerInput(Unit) {
                // Inertia of vertical scroll wheel gestures: After lifting the finger, the scroll wheel continues to scroll with decreasing speed until it stops.
                var inertiaJob: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Inertia stops as soon as the finger lands. Must be after `awaitFirstDown`: `awaitEachGesture`
                    // Re-enter this block immediately after returning from the previous gesture block (call the block first within the loop, then wait for all-up),
                    // If you cancel first, the momentum from the initial launch will be cut off in the same frame (whatever the delay was).
                    inertiaJob?.cancel()
                    if (buffer.mouseTracking <= 0) return@awaitEachGesture
                    down.consume()
                    val downId = down.id
                    val downCell = pointerCell(down.position)
                    var (col, row) = downCell
                    // Gesture direction (null = not yet determined): Determine the semantic meaning based on the dominant direction of the overall displacement and lock it in,
                    // Prevent horizontal finger jitter from causing vertical scrolling to be mistakenly interpreted as horizontal drag (herdr will treat
                    // (Select and copy). Vertical scrolling → only scroll with the mouse wheel; horizontal scrolling → only scroll with Down+motion.
                    var vertical: Boolean? = null
                    var totalDx = 0f
                    var totalDy = 0f
                    // Defer sending Down until the gesture intent is clear:
                    //   - Light tap → Press and release within the same cell (tap)
                    //   - Single-finger vertical drag → send only the scroll wheel (no press/release): herdr treats “press, then release on a different cell”
                    //     It interprets this as dragging to select and copy → OSC 52 → app repeatedly displays the message “Content has been written to the clipboard on the remote device.”
                    //   - Horizontal/multi-finger drag → additionally send Down + drag-motion + Up
                    var downSent = false
                    var sentMotion = false
                    var sentWheel = false

                    fun sendDownIfNeeded() {
                        if (!downSent) {
                            downSent = true
                            sendMouseEvent(0, downCell.first, downCell.second, release = false)
                        }
                    }
                    var scrollAccumY = 0f
                    var prevX = down.position.x
                    var prevY = down.position.y
                    var lastPos = down.position
                    // Recent event time/location window, used to estimate the velocity when the finger is lifted
                    val recent = ArrayDeque<Pair<Long, Float>>()
                    var movedCell = false
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // Use a second finger → Toggle to mouse drag semantics
                        if (event.changes.count { it.pressed } > 1) multiTouch = true
                        val change = event.changes.firstOrNull { it.id == downId } ?: continue
                        // Determine when the action is complete based on the `pressed` state rather than `changedToUp()`: extremely fast taps may be interpreted by Compose
                        // Merge into a single frame (when down and up are in the same frame, previousPressed=false; changedToUp never holds true),
                        // Otherwise, events are lost, the gesture loop freezes, and all subsequent taps are ignored.
                        if (!change.pressed) {
                            if (downSent) {
                                val upPos = if (movedCell && sentMotion) change.position else down.position
                                val (uc, ur) = pointerCell(upPos)
                                sendMouseEvent(0, uc, ur, release = true)
                            } else if (!sentWheel) {
                                // Pure light tap (no displacement, no scroll wheel): additionally send down+up at the same cell to preserve tap semantics
                                val (uc, ur) = pointerCell(down.position)
                                sendMouseEvent(0, uc, ur, release = false)
                                sendMouseEvent(0, uc, ur, release = true)
                            } else {
                                // Vertical scroll gesture: Use the speed at which the finger is lifted to simulate inertial scrolling (decay),
                                // The direction should match the swipe direction, and the landing point should be fixed at the end of the row (starting from the first row).
                                val velocityY =
                                    if (recent.size >= 2) {
                                        val newest = recent.last()
                                        val oldest = recent.first()
                                        val dtMs = (newest.first - oldest.first).coerceAtLeast(16L)
                                        (newest.second - oldest.second) * 1000f / dtMs
                                    } else {
                                        0f
                                    }
                                if (kotlin.math.abs(velocityY) >= 150f) {
                                    val btn = if (velocityY > 0f) 64 else 65
                                    val (wc, wr) = pointerCell(lastPos)
                                    val wheelRow = maxOf(wr, 1)
                                    val startV = kotlin.math.abs(velocityY)
                                    inertiaJob?.cancel()
                                    inertiaJob =
                                        inertiaScope.launch {
                                            var vel = startV
                                            var accum = 0f
                                            while (vel > 50f) {
                                                val dt = 16f
                                                accum += vel * dt / 1000f
                                                val notches = (accum / currentCellH).toInt()
                                                if (notches != 0) {
                                                    repeat(kotlin.math.abs(notches)) {
                                                        sendMouseEvent(btn, wc, wheelRow, release = false)
                                                    }
                                                    accum -= notches * currentCellH
                                                }
                                                vel *= 0.94f
                                                delay(16)
                                            }
                                        }
                                }
                            }
                            break
                        }
                        change.consume()
                        val dx = change.position.x - prevX
                        val dy = change.position.y - prevY
                        prevX = change.position.x
                        prevY = change.position.y
                        lastPos = change.position
                        recent.addLast(change.uptimeMillis to change.position.y)
                        // Retain only events from the last ~150 ms: static events during the long press period when dragging after a long press
                        // This will skew the speed estimate (long press for 500 ms + quick drag → average speed approaches 0),
                        // Inertia is never triggered. After time-based clipping, the speed reflects the actual drag speed.
                        val nowMs = change.uptimeMillis
                        while (recent.isNotEmpty() && nowMs - recent.first().first > 150L) {
                            recent.removeFirst()
                        }
                        totalDx += dx
                        totalDy += dy
                        // Determine direction and lock after moving more than one space (horizontal jitter does not change vertical gestures).
                        if (vertical == null &&
                            !multiTouch &&
                            (kotlin.math.abs(totalDx) >= currentCellW || kotlin.math.abs(totalDy) >= currentCellH)
                        ) {
                            vertical = kotlin.math.abs(totalDy) >= kotlin.math.abs(totalDx)
                        }
                        val (nc, nr) = pointerCell(change.position)
                        if (nc != col || nr != row) {
                            col = nc
                            row = nr
                            movedCell = true
                            // Horizontal-dominant/multi-finger → Mouse drag (dividing lines, selections); Vertical-dominant → Converted to mouse wheel
                            val wantMotion = buffer.mouseTracking >= 1002 && (multiTouch || vertical == false)
                            if (wantMotion) {
                                sendDownIfNeeded()
                                sendMouseEvent(32, col, row, release = false)
                                sentMotion = true
                            }
                        }
                        // Single-finger vertical scroll → scroll wheel (one step per `cellH`), with the scroll position following the finger’s current location
                        if (!multiTouch) {
                            scrollAccumY += dy
                            if (vertical == true) {
                                val notches = (scrollAccumY / currentCellH).toInt()
                                if (notches != 0) {
                                    val btn = if (notches > 0) 64 else 65
                                    val (wc, wr) = pointerCell(change.position)
                                    // When quickly swiping up to the top, the last batch of scroll wheel events lands on row 0 — in desktop layout that is
                                    // In herdr’s tab bar, each scroll wheel click triggers a tab switch (resulting in rapid-fire tab switching after releasing the wheel).
                                    // Scroll wheel control starting from row 1: the pane area is fully scrollable; Chrome never accepts the scroll wheel;
                                    // Tapping the tab key to navigate the Down/Up path remains unaffected.
                                    val wheelRow = maxOf(wr, 1)
                                    repeat(kotlin.math.abs(notches)) {
                                        sendMouseEvent(btn, wc, wheelRow, release = false)
                                    }
                                    scrollAccumY -= notches * currentCellH
                                    sentWheel = true
                                }
                            }
                        }
                    }
                }
            }.pointerInput(Unit) {
                detectTapGestures(
                    onTap = tap@{ pos ->
                        // TUI Mouse Mode: Tap events are reported entirely by the mouse handler mentioned above; they are not intercepted locally.
                        if (buffer.mouseTracking > 0) return@tap
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        var opened = false
                        if (absRow in 0 until buffer.totalLines()) {
                            val link = buffer.absLine(absRow).cells[col].link
                            if (link != null && isSafeLinkScheme(link)) {
                                uriHandler.openUri(link)
                                opened = true
                            }
                        }
                        // Click a blank space to collapse the selection
                        if (!opened && controller.selection.isActive) {
                            controller.selection.clear()
                        }
                    },
                    onDoubleTap = tap@{ pos ->
                        // TUI mouse mode (herdr/vim, etc.): The selection is managed by the remote end,
                        // Double-clicking to select a word locally causes conflict with the selection on the remote server, and accidental long presses or slow swipes may copy the entire row.
                        if (buffer.mouseTracking > 0) return@tap
                        // Double-click to select a word and copy it
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        if (absRow < buffer.totalLines()) {
                            val line = buffer.absLine(absRow)

                            fun isWordCell(idx: Int): Boolean {
                                val c = line.cells[idx]
                                if (c.isWideTail) return false
                                val cp = c.codePoint
                                return cp < 128 && (cp.toChar().isLetterOrDigit() || cp.toChar() in "._-~/:@%")
                            }
                            if (isWordCell(col)) {
                                var s = col
                                while (s > 0 && isWordCell(s - 1)) s--
                                var e = col
                                while (e < buffer.cols - 1 && isWordCell(e + 1)) e++
                                controller.selection.start(absRow, s)
                                controller.selection.extend(absRow, e)
                                val text = controller.selection.selectedText()
                                if (text.isNotEmpty()) onCopy(text)
                            }
                        }
                    },
                    onLongPress = longPress@{ pos ->
                        // Same as above: Disable long-press word selection in local mouse mode (swiping after a long press may accidentally copy the entire row).
                        if (buffer.mouseTracking > 0) return@longPress
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        controller.selection.start(absRow, col)
                        controller.selection.extend(absRow, buffer.cols - 1)
                        val text = controller.selection.selectedText()
                        if (text.isNotEmpty()) {
                            onCopy(text)
                        }
                    },
                )
            }
            // Scrollback: scrollable comes with fling inertia damping;
            // delta represents the direction of finger swipe (downward swipe with delta > 0 → content moves down with the finger → view history → scrollOffset increases)
            .scrollable(
                orientation = Orientation.Vertical,
                state =
                    rememberScrollableState { delta ->
                        // TUI Mouse Mode: Gestures are handled entirely by the mouse handler above (to prevent dragging from triggering
                        // (drag-motion triggers another blend of wheel events); local scrolling only works in non-mouse mode
                        if (buffer.mouseTracking > 0) return@rememberScrollableState delta
                        // DECSET 1007 alternate scroll: there is no scrollback to scroll in the alternate screen,
                        // Convert scroll gestures to arrow keys for use in remote fullscreen applications (such as Less or Vim).
                        if (buffer.altScreen && buffer.alternateScroll) {
                            scrollAccum += delta
                            val rows = (scrollAccum / cellH).toInt()
                            if (rows != 0) {
                                val key = if (rows > 0) "\u001b[B" else "\u001b[A"
                                repeat(kotlin.math.abs(rows)) {
                                    controller.sendBytes(key.encodeToByteArray())
                                }
                                scrollAccum -= rows * cellH
                            }
                            return@rememberScrollableState delta
                        }
                        scrollAccum += delta
                        val rows = (scrollAccum / cellH).toInt()
                        if (rows != 0) {
                            scrollOffset = (scrollOffset + rows).coerceIn(0, buffer.scrollbackSize())
                            scrollAccum -= rows * cellH
                        }
                        delta
                    },
            )
            // Scrollbar dragging: Only consume events when the right edge is hit; leave the rest to `scrollable`.
            // Yield in mouse mode (TUI UI elements may be positioned at the right edge, such as the tab button in herdr);
            // Default `requireUnconsumed=true`: Upstream mouse handling will no longer be triggered for events that have already been consumed.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (buffer.mouseTracking > 0) return@awaitEachGesture
                    val barZone = size.width - 24.dp.toPx()
                    if (down.position.x >= barZone && buffer.scrollbackSize() > 0) {
                        drag(down.id) { change ->
                            val sb = buffer.scrollbackSize()
                            val h = latestCanvasSize.height
                            if (sb > 0 && h > 0) {
                                val total = buffer.totalLines().toFloat()
                                val trackH = h.toFloat()
                                val thumbH = (buffer.rows / total * trackH).coerceAtLeast(24f)
                                val ratio = (change.position.y / (trackH - thumbH)).coerceIn(0f, 1f)
                                scrollOffset = (ratio * sb).toInt().coerceIn(0, sb)
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        // Monitor the redraw counter to trigger a redraw (otherwise, Compose will skip the redraw because the content to be drawn has not changed).
        @Suppress("UNUSED_VARIABLE")
        val redraw = controller.frame

        // Each drawing draws the visible area based on the latest buffer/scroll position.
        val scrollback = buffer.scrollbackSize()
        val offset = scrollOffset.coerceIn(0, scrollback)
        val totalLines = buffer.totalLines()
        val startAbs = (totalLines - buffer.rows - offset).coerceAtLeast(0)

        // Background (alpha is always 1; used solely to trigger a redraw to establish redraw observation)
        drawRect(theme.background().copy(alpha = if (redraw >= 0L) 1f else 1f))

        val lines = ArrayList<TerminalLine>(buffer.rows)
        for (r in 0 until buffer.rows) {
            val abs = startAbs + r
            lines.add(if (abs < totalLines) buffer.absLine(abs) else emptyLine)
        }

        // When the keyboard pops up or the font size changes, there is a transient one-frame mismatch between the canvas dimensions and the number of rows and columns in the buffer,
        // Drawing text out of bounds causes `drawText` to crash due to negative internal constraints; here, text that is out of bounds is uniformly skipped.
        fun drawTextInBounds(
            text: String,
            topLeft: Offset,
            textStyle: TextStyle,
        ) {
            if (topLeft.x < 0f || topLeft.y < 0f || topLeft.x >= size.width || topLeft.y >= size.height) return
            drawText(textMeasurer = textMeasurer, text = text, topLeft = topLeft, style = textStyle)
        }

        // Cached layout replay version: Skip measurement and directly render TextLayoutResult
        fun drawLayoutInBounds(
            run: CachedTextRun,
            topLeft: Offset,
        ) {
            if (topLeft.x < 0f || topLeft.y < 0f || topLeft.x >= size.width || topLeft.y >= size.height) return
            drawText(textLayoutResult = run.layout, topLeft = topLeft)
        }

        // Background color run
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                val bgRaw = cell.bg
                var j = i
                while (j < buffer.cols && line.cells[j].bg == bgRaw) j++
                if (bgRaw != DEFAULT_BG) {
                    val (_, bgColor) = cellColors(cell, theme)
                    drawRect(
                        color = bgColor,
                        topLeft = Offset(i * cellW, y),
                        size = Size((j - i) * cellW, cellH),
                    )
                }
                i = j
            }
        }

        // Text run: Cache measurement results by row (identity, version)—for unchanged rows, only replay the layout,
        // When scrolling, the entire screen’s content is translated and almost entirely cached, eliminating the need to reflow the fullscreen text every frame.
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            val lineText =
                lineTextCache.get(line)
                    ?: measureLineText(line).also { lineTextCache.put(line, it) }
            for (run in lineText.runs) {
                drawLayoutInBounds(run, Offset(run.x, y + textTop))
            }
        }

        // Underscore
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                if (cell.attrs and CellAttr.UNDERLINE != 0 && !cell.isWideTail) {
                    var j = i
                    while (j < buffer.cols &&
                        line.cells[j].attrs and CellAttr.UNDERLINE != 0 &&
                        !line.cells[j].isWideTail
                    ) {
                        j++
                    }
                    val (fgColor, _) = cellColors(cell, theme)
                    drawRect(
                        color = fgColor,
                        topLeft = Offset(i * cellW, y + cellH - 2f),
                        size = Size((j - i) * cellW, 1.5f),
                    )
                    i = j
                } else {
                    i++
                }
            }
        }

        // OSC 8: Underline hyperlinks (draw the underline separately from the SGR underline to avoid dependency on attribute positions)
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                if (cell.link != null && !cell.isWideTail) {
                    var j = i
                    while (j < buffer.cols && line.cells[j].link == cell.link && !line.cells[j].isWideTail) j++
                    val (fgColor, _) = cellColors(cell, theme)
                    drawRect(
                        color = fgColor.copy(alpha = 0.65f),
                        topLeft = Offset(i * cellW, y + cellH - 2f),
                        size = Size((j - i) * cellW, 1.5f),
                    )
                    i = j
                } else {
                    i++
                }
            }
        }

        // Selection of highlight
        if (controller.selection.isActive) {
            val sel = controller.selection
            val selTop = minOf(sel.startRow, sel.endRow)
            val selBottom = maxOf(sel.startRow, sel.endRow)
            for (abs in selTop..selBottom) {
                val r = abs - startAbs
                if (r !in 0 until buffer.rows) continue
                val fromCol =
                    if (abs == sel.startRow && abs == sel.endRow) {
                        minOf(sel.startCol, sel.endCol)
                    } else if (abs == sel.startRow) {
                        sel.startCol
                    } else if (abs == sel.endRow) {
                        sel.endCol
                    } else {
                        -1
                    }
                if (fromCol == -1) {
                    drawRect(theme.selection(), Offset(0f, r * cellH), Size(buffer.cols * cellW, cellH))
                } else {
                    val toCol = if (abs == sel.endRow) maxOf(sel.startCol, sel.endCol) else buffer.cols - 1
                    drawRect(
                        theme.selection(),
                        Offset(fromCol * cellW, r * cellH),
                        Size((toCol - fromCol + 1) * cellW, cellH),
                    )
                }
            }
        }

        // cursor
        val cursorAbs = buffer.absCursorRow()
        val cursorScreenRow = cursorAbs - startAbs
        // Protocol visibility (DECSET 25) and rendering flickering due to phase overlap:
        // Steady-state styles (2/4/6) remain lit; flashing styles alternate according to phase.
        val steadyCursor = buffer.cursorStyle == 2 || buffer.cursorStyle == 4 || buffer.cursorStyle == 6
        if (buffer.cursorVisible &&
            cursorScreenRow in 0 until buffer.rows &&
            (steadyCursor || controller.cursorBlinkPhase)
        ) {
            val cursorColor =
                if (buffer.cursorColor != DEFAULT_CURSOR) {
                    Color(0xFF000000.toInt() or buffer.cursorColor)
                } else {
                    theme.cursor()
                }
            val cursorColPx = buffer.cursorCol * cellW
            val cursorRowPx = cursorScreenRow * cellH
            when (buffer.cursorStyle) {
                // DECSCUSR: Underscore / Vertical Cursor
                3, 4 ->
                    drawRect(
                        color = cursorColor,
                        topLeft = Offset(cursorColPx, cursorRowPx + cellH - 2f),
                        size = Size(cellW, 2f),
                    )
                5, 6 ->
                    drawRect(
                        color = cursorColor,
                        topLeft = Offset(cursorColPx, cursorRowPx),
                        size = Size(2f, cellH),
                    )
                else -> {
                    drawRect(
                        color = cursorColor,
                        topLeft = Offset(cursorColPx, cursorRowPx),
                        size = Size(cellW, cellH),
                    )
                    // Characters under a block cursor are shown in reverse color (underline/vertical bar cursors do not obscure characters)
                    if (cursorAbs in 0 until totalLines) {
                        val line = buffer.absLine(cursorAbs)
                        if (buffer.cursorCol < buffer.cols) {
                            val cell = line.cells[buffer.cursorCol]
                            if (!cell.isWideTail && cell.codePoint != ' '.code) {
                                val (fg, bg) = cellColors(cell, theme)
                                drawTextInBounds(
                                    codePointToString(cell.codePoint),
                                    Offset(cursorColPx, cursorRowPx + textTop),
                                    (if (cell.width == 2) wideStyle else style)
                                        .copy(color = bg, fontWeight = runFontWeight(cell.attrs)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Scrollbar (shows on the right when there is history; draggable)
        if (scrollback > 0) {
            val barW = 6f
            val barX = size.width - barW - 4f
            val trackH = (size.height - 4f).coerceAtLeast(1f)
            val total = totalLines.toFloat().coerceAtLeast(buffer.rows.toFloat())
            val thumbH = (buffer.rows / total * trackH).coerceAtLeast(24f)
            val thumbY = (offset.toFloat() / scrollback * (trackH - thumbH)).coerceIn(0f, trackH - thumbH)
            drawRect(theme.foreground().copy(alpha = 0.12f), Offset(barX, 2f), Size(barW, trackH))
            drawRect(theme.foreground().copy(alpha = 0.45f), Offset(barX, 2f + thumbY), Size(barW, thumbH))
        }
    }
}
