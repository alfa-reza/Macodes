package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.TerminalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Special keys. CTRL/ALT are sticky modifier keys that affect only subsequent input.
 *  Low-frequency keys (F1–F12 / HOME / END / PGUP / PGDN / DEL / ⌃Z, etc.) are grouped under
 *  The toolbar “⋯” overflow panel (MoreKeySheet) no longer occupies a permanent key position;
 *  See specialKeyBytes below for the escape mapping. */
enum class SpecialKey(
    val label: String,
) {
    ESC("ESC"),
    TAB("TAB"),
    CTRL("CTRL"),
    ALT("ALT"),
    UP("↑"),
    DOWN("↓"),
    LEFT("←"),
    RIGHT("→"),
    CTRL_C("⌃C"),
    CTRL_D("⌃D"),
    CTRL_L("⌃L"),
    CTRL_E("⌃E"),
    ENTER("ENT"),

    /** Shift+Tab (ESC[Z): Invert TUI menu selection; essential for toggling between Claude Code modes (cannot be typed on the on-screen keyboard).
     *  Long-press the persistent Tab to trigger. */
    SHIFT_TAB("⇧⇥"),

    /** ⌃\ (SIGQUIT): triggered by a long press of ⌃C/⌃D; kills stubborn processes that do not respond even to ⌃C. */
    CTRL_BACKSLASH("⌃\\"),

    // ---- Overflow panel button (⋯ Expand) ----
    CTRL_R("⌃R"),
    CTRL_Z("⌃Z"),
    DEL("DEL"),
    HOME("HOME"),
    END("END"),
    PGUP("PGUP"),
    PGDN("PGDN"),
    F1("F1"),
    F2("F2"),
    F3("F3"),
    F4("F4"),
    F5("F5"),
    F6("F6"),
    F7("F7"),
    F8("F8"),
    F9("F9"),
    F10("F10"),
    F11("F11"),
    F12("F12"),
}

fun specialKeyBytes(
    key: SpecialKey,
    applicationCursorKeys: Boolean,
): ByteArray =
    when (key) {
        SpecialKey.ESC -> byteArrayOf(0x1b)
        SpecialKey.TAB -> byteArrayOf(0x09)
        SpecialKey.UP -> cursorKeySeq('A', applicationCursorKeys)
        SpecialKey.DOWN -> cursorKeySeq('B', applicationCursorKeys)
        SpecialKey.RIGHT -> cursorKeySeq('C', applicationCursorKeys)
        SpecialKey.LEFT -> cursorKeySeq('D', applicationCursorKeys)
        SpecialKey.CTRL_C -> byteArrayOf(0x03)
        SpecialKey.CTRL_D -> byteArrayOf(0x04)
        SpecialKey.CTRL_L -> byteArrayOf(0x0c)
        SpecialKey.CTRL_E -> byteArrayOf(0x05)
        SpecialKey.CTRL_R -> byteArrayOf(0x12)
        SpecialKey.CTRL_Z -> byteArrayOf(0x1a)
        SpecialKey.ENTER -> byteArrayOf(0x0d)
        SpecialKey.SHIFT_TAB -> byteArrayOf(0x1b, 0x5b, 0x5a) // ESC [ Z
        SpecialKey.CTRL_BACKSLASH -> byteArrayOf(0x1c) // SIGQUIT
        SpecialKey.DEL -> byteArrayOf(0x7f)
        SpecialKey.HOME -> cursorKeySeq('H', applicationCursorKeys)
        SpecialKey.END -> cursorKeySeq('F', applicationCursorKeys)
        SpecialKey.PGUP -> escapeSeq("5~")
        SpecialKey.PGDN -> escapeSeq("6~")
        SpecialKey.F1 -> byteArrayOf(0x1b, 0x4f, 0x50) // ESC O P
        SpecialKey.F2 -> byteArrayOf(0x1b, 0x4f, 0x51) // ESC O Q
        SpecialKey.F3 -> byteArrayOf(0x1b, 0x4f, 0x52) // ESC O R
        SpecialKey.F4 -> byteArrayOf(0x1b, 0x4f, 0x53) // ESC O S
        SpecialKey.F5 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x35, 0x7e) // ESC [15~
        SpecialKey.F6 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x37, 0x7e) // ESC [17~
        SpecialKey.F7 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x38, 0x7e) // ESC [18~
        SpecialKey.F8 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x39, 0x7e) // ESC [19~
        SpecialKey.F9 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x7e) // ESC [20~
        SpecialKey.F10 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x31, 0x7e) // ESC [21~
        SpecialKey.F11 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x33, 0x7e) // ESC [23~
        SpecialKey.F12 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x34, 0x7e) // ESC [24~
        SpecialKey.CTRL, SpecialKey.ALT -> ByteArray(0)
    }

private fun escapeSeq(suffix: String): ByteArray = ("\u001b[$suffix").encodeToByteArray()

/** DECCKM selects SS3 (ESC O) instead of CSI (ESC [) for cursor keys. */
private fun cursorKeySeq(
    final: Char,
    applicationCursorKeys: Boolean,
): ByteArray = byteArrayOf(0x1b, if (applicationCursorKeys) 0x4f else 0x5b, final.code.toByte())

/** Hold-to-rapid-fire parameter (simulates the feel of a physical keyboard): There’s latency before rapid firing begins, and the faster you press, the faster it goes—without getting out of control. */
private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * Terminal function key toolbar: Fixed at two rows + expandable to four rows.
 *
 * Row 1 (Control): ESC TAB ⌃C ⌃L {} ↑ ⌨ ▾ (ESC in the upper-left, ⌨ in the upper-right thumb area;
 * ▾ Expand/collapse rows 3/4; preserve within the session; ↑ Vertical alignment of column 6 with ↓ on row 2).
 * Row 2 (Modify/Edit): CTRL ALT ⌃D / ← ↓ → ENT (CTRL/ALT bottom left, same as physical keyboard)
 * Bottom row; ↑/↓ vertically aligned in column 6; / is sent directly by onChar).
 * Row 3 (expanded): PST ⇧⇥ DEL HOME END PGUP PGDN ⌃\\
 * Row 4 (expanded): F1–F7 + ⎇ (Git panel; F8–F12 are rarely used and do not occupy keys).
 * Each row is 8 columns of equal-width square keys.
 *
 * - Arrow keys/⌫ support **holding down for rapid fire** (60 ms per press after 400 ms)
 * - Long press macros: **ESC twice** (Claude Code edit history); **TAB → ⇧⇥** (TUI reverse selection/mode toggling);
 *   **⌃C/⌃D → ⌃\\** (SIGQUIT: Kill stubborn processes)
 * - Hold down CTRL/ALT and press a letter on the system keyboard to type compositions such as ⌃A/⌃E/⌃R (without occupying a key).
 * - Symbol keys are provided by the system keyboard; canvas inertial scrolling replaces PgUp/PgDn (physical keys are provided to expand rows).
 *
 * Hoist the expanded state to the caller ([expanded]/[onExpandedChange]); fixed two-row height via
 * [onBaseHeightChanged] Callback: canvas laid out by the fixed height only; expanded rows 3/4 overlay the canvas
 * Rather than resizing the canvas—do not trigger a PTY resize; the TUI should not reflow or cause screen flickering.
 */
@Composable
fun KeyToolbar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onKey: (SpecialKey) -> Unit,
    onToggleKeyboard: () -> Unit = {},
    onPaste: () -> Unit = {},
    /** Command snippet insertion panel (the “{}” key on row 1). */
    onSnippets: () -> Unit = {},
    /** Git panel entry point (the “⎇” key that expands on row 4; an alternative entry point via the canvas FAB). */
    onGit: () -> Unit = {},
    /** Ordinary character keys (such as /) are sent directly as terminal input. */
    onChar: (String) -> Unit = {},
    /** Row 3/4 expanded state (Note: The canvas is laid out at a fixed height; when a row is expanded, it covers the canvas without resizing it). */
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    /** Fixed two-row height callback (px; stays fixed when expanded) — used for the connect threshold (stable canvas size). */
    onBaseHeightChanged: (Int) -> Unit = {},
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Row 3/4 (▾ Expand): Direct access to low-frequency keys; maintain 8-key width per row.
        // Must be placed above the persistent block: rows expand from the bottom edge of the persistent block and extend
        // upward over the canvas (without compressing, resizing or reflowing the TUI) — placed below, they would make the
        // whole toolbar taller and push the fixed two rows into the canvas area, covering the terminal content (v1.1.7 regression).
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                KeyButton("PST", false, theme) { onPaste() }
                KeyButton("⇧⇥", false, theme) { onKey(SpecialKey.SHIFT_TAB) }
                KeyButton("DEL", false, theme) { onKey(SpecialKey.DEL) }
                KeyButton("HOME", false, theme) { onKey(SpecialKey.HOME) }
                KeyButton("END", false, theme) { onKey(SpecialKey.END) }
                KeyButton("PGUP", false, theme) { onKey(SpecialKey.PGUP) }
                KeyButton("PGDN", false, theme) { onKey(SpecialKey.PGDN) }
                KeyButton("⌃\\", false, theme) { onKey(SpecialKey.CTRL_BACKSLASH) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                KeyButton("F1", false, theme) { onKey(SpecialKey.F1) }
                KeyButton("F2", false, theme) { onKey(SpecialKey.F2) }
                KeyButton("F3", false, theme) { onKey(SpecialKey.F3) }
                KeyButton("F4", false, theme) { onKey(SpecialKey.F4) }
                KeyButton("F5", false, theme) { onKey(SpecialKey.F5) }
                KeyButton("F6", false, theme) { onKey(SpecialKey.F6) }
                KeyButton("F7", false, theme) { onKey(SpecialKey.F7) }
                // Git panel entry (alternative entry for the canvas FAB; F8–F12 are rarely used and do not occupy space)
                KeyButton("⎇", false, theme) { onGit() }
            }
        }
        // Fixed block: Rows 1–2, aligned with the bottom edge of the toolbar (position fixed; does not change when expanded)
        Column(
            Modifier
                .onSizeChanged { onBaseHeightChanged(it.height) }
                .padding(horizontal = 4.dp)
                .padding(top = 2.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Row 1: ESC in the top-left corner (intuitive placement on a physical keyboard); TAB always available (for frequent shell autocompletion; long press = ⇧⇥);
            // {} snippet / ⌨ / ▾ Expand to the right (thumb area)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                RepeatKeyButton(
                    "ESC",
                    theme,
                    onTap = { onKey(SpecialKey.ESC) },
                    onLongPress = {
                        onKey(SpecialKey.ESC)
                        onKey(SpecialKey.ESC)
                    },
                )
                KeyButton(
                    "TAB",
                    false,
                    theme,
                    onLongPress = { onKey(SpecialKey.SHIFT_TAB) },
                ) { onKey(SpecialKey.TAB) }
                RepeatKeyButton(
                    "⌃C",
                    theme,
                    onTap = { onKey(SpecialKey.CTRL_C) },
                    onLongPress = { onKey(SpecialKey.CTRL_BACKSLASH) },
                )
                KeyButton("⌃L", false, theme) { onKey(SpecialKey.CTRL_L) }
                KeyButton("{}", false, theme) { onSnippets() }
                // ↑ Keep column 6 in vertical alignment with row 2 ↓ (original design constraint)
                KeyButton("↑", false, theme, repeat = true) { onKey(SpecialKey.UP) }
                KeyButton("⌨", false, theme) { onToggleKeyboard() }
                // Expand/Collapse: Material arrow icon (22dp: 48dp touch target paired with a 24dp icon)
                // Use values within the specification that are larger and clearer than the original text characters, but do not fill the entire key like "30dp" does.
                KeyButton(
                    "",
                    expanded,
                    theme,
                    icon = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    iconSize = 22.dp,
                ) { onExpandedChange(!expanded) }
            }
            // Row 2: CTRL/ALT bottom left (same as the bottom row of a physical keyboard); ↑/↓ vertical alignment in column 6; ENT bottom right
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                KeyButton("CTRL", ctrlActive, theme) { onToggleCtrl() }
                KeyButton("ALT", altActive, theme) { onToggleAlt() }
                RepeatKeyButton(
                    "⌃D",
                    theme,
                    onTap = { onKey(SpecialKey.CTRL_D) },
                    onLongPress = { onKey(SpecialKey.CTRL_BACKSLASH) },
                )
                KeyButton("/", false, theme) { onChar("/") }
                KeyButton("←", false, theme, repeat = true) { onKey(SpecialKey.LEFT) }
                KeyButton("↓", false, theme, repeat = true) { onKey(SpecialKey.DOWN) }
                KeyButton("→", false, theme, repeat = true) { onKey(SpecialKey.RIGHT) }
                KeyButton("ENT", false, theme) { onKey(SpecialKey.ENTER) }
            }
        } // Permanent block measurement complete (row 3/4 above, not included in onBaseHeightChanged)
    }
}

/**
 * Reusable KeyButton: Supports three types of tactile feedback.
 * - repeat=true: Hold down to fire continuously (using arrow keys or other navigation keys)
 * - onLongPress: long-press macros (e.g., ESC sent twice, ⌃C→⌃\)
 * - These are mutually exclusive: rapid-fire input takes priority; if there is no rapid-fire input but the onLongPress duration is present, the macro is triggered once upon a long press.
 */
@Composable
private fun RowScope.KeyButton(
    label: String,
    active: Boolean,
    theme: TerminalTheme,
    repeat: Boolean = false,
    /** Icon keys (such as the expand arrow): Render large Material icons instead of text. */
    icon: ImageVector? = null,
    iconSize: Dp = 24.dp,
    // Note the parameter order: `onTap` must be the last parameter (trailing lambdas are widely used in the calling code),
    // Placing `onLongPress` at the end by mistake causes the trailing lambda to be bound to the long press callback—resulting in single-tap failure and the directional keys becoming completely unresponsive.
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit = {},
) {
    val bg = if (active) theme.cursor() else Color.Transparent
    val fg = if (active) theme.background() else theme.foreground()
    // Hold-to-send state machine: During `pressing=true`, `LaunchedEffect` sends repeatedly
    var pressing by remember { mutableStateOf(false) }
    if (repeat) {
        LaunchedEffect(pressing) {
            if (!pressing) return@LaunchedEffect
            delay(REPEAT_INITIAL_DELAY_MS)
            while (isActive && pressing) {
                onTap()
                delay(REPEAT_INTERVAL_MS)
            }
        }
        DisposableEffect(Unit) {
            onDispose { pressing = false }
        }
    }

    Box(
        Modifier
            .weight(1f, fill = false)
            // Square buttons: Height follows width, but width is capped at 48dp (Android touch target limit) ——
            // On wide-screen or landscape displays, keep the keys arranged in a centered square; do not stretch them into a flat block (user feedback:
            // (The toolbar buttons are no longer square.) ⚠️ Do not use `weight(fill=true)` + `aspectRatio`:
            // On wide screens, key widths are forced to be evenly scaled up; `aspectRatio` ignores height constraints and returns width × width,
            // Content is centered and truncated due to overflow caused by an excessively tall key (root cause of landscape mode misalignment in v1.5.1)
            .widthIn(max = 48.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .then(
                if (repeat) {
                    // pointerInput solution: Fully manage press/release events yourself to enable continuous firing while holding down the button
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressing = true
                            onTap()
                            // Wait for lifting or cancellation; the rapid-fire key has no long press semantics, so no single-click disambiguation is performed.
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val released = ev.changes.all { !it.pressed }
                                    val consumed = ev.changes.any { it.isConsumed }
                                    if (released || consumed) break
                                }
                            } catch (_: CancellationException) {
                                // On pointer-up, `awaitPointerEvent` throwing Cancellation is a normal path
                            } finally {
                                pressing = false
                            }
                        }
                    }
                } else {
                    // combinedClickable: Click + optional long press macro
                    Modifier.combinedClickable(
                        onClick = onTap,
                        onLongClick = onLongPress,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
        } else {
            Text(
                label,
                color = fg,
                // Maintain visual consistency across two font sizes: symbol keys (↑↓←→ ⌨ / ⎇) and 22dp icons
                // Same tier (≈14 sp visually); multi-character keys (ESC/CTRL/F1…) 11 sp.
                // Simple categorization by length: single character = symbol; all others = text tag
                style =
                    if (label.length == 1) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                maxLines = 1,
            )
        }
    }
}

/** Long press macro-specific aliases (no rapid-fire). */
@Composable
private fun RowScope.RepeatKeyButton(
    label: String,
    theme: TerminalTheme,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) = KeyButton(label, false, theme, repeat = false, onLongPress = onLongPress, onTap = onTap)
