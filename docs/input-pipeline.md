# Input pipeline (IME / keyboard / touch)

> **Summary:** how keyboard input reaches the remote shell — a hidden
> `BasicTextField` as the IME entry point, composition-safe CJK input (composing
> text never hits the wire; only committed text is diffed), split backspace
> semantics, sticky CTRL/ALT, bracketed paste, and the touch-to-mouse-event
> mapping for TUIs with mouse reporting enabled.

Termish's input pipeline is built around one principle: **IME composing state (pinyin) is a first-class citizen** — composing text
never goes on the wire, only committed text is sent, and the remote line is never polluted by pinyin. The implementation is
concentrated in `ui/TerminalScreen.kt` (state management), `ui/TerminalView.kt` (touch gestures), `ui/KeyToolbar.kt` (function keys).

## Input entry point: hidden BasicTextField

- A 1dp transparent `BasicTextField` floats in the toolbar area, serving only as the IME entry point and **rendering no visible UI** — what you type is reflected by the remote echo (the remote echo is the real state; a local display would duplicate it and get in the way).
- `KeyboardType.Text` (not Ascii): keeps the Chinese candidate bar; `capitalization = None`, `autoCorrect = false`.
- `singleLine = false` multiline mode: captures the IME Enter (`\n` → sends CR).
- Tapping the canvas/⌨ key does `requestFocus()` + a delayed 150ms `show()`: focus changes and input-connection establishment are asynchronous, so an immediate show is often ignored; `onFocusChanged` does one more fallback show.
- During IME composition **composing text is never sent to the remote**.

## Commit diff algorithm

The committed-text baseline `committedText` plus the current field value are diffed by **longest common prefix**:

```
p = length of the longest common prefix
send oldText.length - p bytes of 0x7f (delete the differing tail)
send newText.substring(p) (the added part, passed through sendTyped for CTRL/ALT/newline)
```

- Pure tail insertions/deletions are exact operations; mid-string edits cannot be mapped to the remote cursor, so they are handled as "delete the tail and resend".
- The field buffer is cleared when it holds >64 characters or contains `\n` (prevents long-paste buildup + resets after Enter).

## Backspace semantics (three cases)

| Case | Behavior |
|------|------|
| IME composing | Not intercepted: the IME deletes the pinyin itself (composing text was never sent to the remote anyway) |
| Field has committed content | Not intercepted: platform deletion → onValueChange diff sends (cursor/selection maintained by the system) |
| Field is empty | `onPreviewKeyEvent` intercepts Backspace and sends 0x7f directly — so content on the remote line after Enter or a quick command is still deletable |

## Character sending (sendTyped)

- ALT sticky → prefix `ESC` (0x1b).
- CTRL sticky + a–z → `(ch.code and 0x1f)`.
- `\n` → CR (0x0d).
- Everything else → raw UTF-8 bytes.

**Sticky modifier consumption rule**: after any key is sent (toolbar onKey / onChar / onPaste) CTRL/ALT reset immediately,
so no residue turns the next letter into ⌃D/⌃C and accidentally exits the session.

## bracketed paste

Toolbar PST (or system paste): when the remote has DECSET 2004 enabled, wrap in `ESC[200~ … ESC[201~`,
so vim/nvim does not treat pasted content as typed (avoiding broken auto-indent). The OSC 52 reverse channel (remote writes the clipboard)
is controlled by a settings toggle and writes silently without a prompt.

## Focus events

When DECSET 1004 is enabled, field focus/blur sends `CSI I` / `CSI O` (vim/tmux refresh their UI state from this).

## Function-key toolbar (fixed two rows 8+8, no expandable layer)

```
Row 1: CTRL  ALT  ESC  TAB  ⌃C  ↑  ⌃L  ⌨
Row 2: ⌃D    PST  /    ⌃E   ←  ↓  →   ENT
```

- ↑ and ↓ align vertically, ⌨ and ENT sit in the top-right/bottom-right thumb zones.
- CTRL/ALT sticky + system keyboard letters gives combinations like ⌃A/⌃E/⌃R, so no dedicated buttons; symbol keys come from the system keyboard; canvas inertial scrolling replaces PgUp/PgDn.
- Arrow keys read the current session buffer's `applicationCursorKeys` (DECCKM) when sending: normal mode uses
  `ESC [ A/B/C/D` (CSI), application mode uses `ESC O A/B/C/D` (SS3), never mixed into `ESC [ O …`.
  HOME/END likewise switch between CSI H/F and SS3 H/F. The mode is controlled by the remote `CSI ? 1 h/l`;
  switching tabs on a live session, re-entering after leaving the terminal page, and a healthy connection returning to the foreground all preserve each buffer, without relying on UI recomposition to refresh the mode.
- Low-frequency keys (F1–F12 / HOM / END / PGUP / PGDN / DEL / ⌫) were removed together with the fixed two-row toolbar design;
  if they need to be exposed again, the escape mappings are in the early expandable-layer implementation (removed from the code, still available in git history)

## Touch → terminal mouse events (TUI mouse reporting)

When the remote enables mouse reporting (1000/1002/1003), canvas gestures are taken over and mapped to mouse events; when it is off, events are not consumed
and the default behavior of focusing the keyboard / selection / scrollback is kept. Encoding depends on the mode: X10 (byte offset 32, max 223),
SGR 1006, urxvt 1015 (mutually exclusive with 1006).

| Gesture | Mapping | Details |
|------|------|------|
| Tap | Down + Up in the same cell | Release uses the pressed cell, so finger jitter is not judged as a drag by herdr's 1-cell drag threshold |
| One-finger vertical drag | Wheel (64/65) | Locked to the dominant direction (prevents sideways jitter from being misread); **wheel rows are clamped to start at row 1** — row 0 is herdr's tab bar in the desktop layout, and scrolling to 0 switches tabs like crazy |
| One-finger horizontal / two-finger drag | Down + drag-motion(32) + Up | Text selection, dragging split lines, dragging inside a pane (requires 1002/1003) |
| Vertical drag release | Inertial wheel | Velocity estimated over a ~150ms window (trimming stationary events from a long press), with a decay loop re-sending |

Down is not sent before the gesture intent is clear (tap = click, vertical = pure wheel), to avoid "pressed then released in another cell" being
judged by herdr as a drag selection and copying → OSC 52 → repeated clipboard prompts.

## Scrollback (non-mouse mode)

- `scrollable` fling inertia; the scrollbar can be dragged on the right edge.
- DECSET 1007 alternate scroll: the alternate screen has no scrollback to scroll, so the scroll gesture is turned into arrow keys for the remote full-screen program (less/vim).
- When the keyboard appears the canvas shifts up by cursor position (without changing the PTY size, avoiding full-screen programs relayouting repeatedly as the keyboard opens and closes); the shift calculation lives inside the `graphicsLayer` block and does not trigger recomposition.

## Quick commands and startup command

- A host can be configured with quick commands (e.g. common ops commands); after connecting they are shown as chips above the canvas, and tapping one sends it.
- The startup command (e.g. `tmux new -A -s main`) runs automatically after a successful connection, which together with auto-reconnect restores the server-side session in place.

## Related settings

Toolbar visibility, auto-reconnect, TOFU first-time confirmation, OSC 52 clipboard, haptic feedback — see the settings page (apply-on-change, saves immediately).
