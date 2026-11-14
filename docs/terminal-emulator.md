# Terminal emulator (term/)

> **Summary:** design and capability notes for the pure-Kotlin VT100/xterm
> emulator — color model, dual screen buffer with COW line sharing and
> (identity, version) line-level incremental sync, wide-char bookkeeping,
> the supported escape-sequence matrix, and the renderer's text-layout cache.
> Any change here must come with a unit test in `commonTest/`.

`commonMain/term/` is the project's core asset: a pure-Kotlin, zero-platform-dependency, unit-testable terminal emulator.
Four files, with clear responsibilities:

| File | Responsibility |
|------|------|
| `TerminalColor.kt` | color encoding, xterm 256-color palette, cell attribute bits, minimal wcwidth |
| `TerminalBuffer.kt` | screen buffer: normal + alternate screen, scrollback, cursor/mode state |
| `TerminalEmulator.kt` | incremental UTF-8 decoding + VT100/xterm state machine + OSC/DCS handling |
| `TerminalSelection.kt` | text selection and copy |

The renderer `ui/TerminalView.kt` lives outside term/ (Compose dependency), but its rendering performance conventions are
tightly coupled to term/'s line model, so they are recorded here as well.

## Color model

- Cell color is an `0xRRGGBB` Int; `DEFAULT_FG = -1` / `DEFAULT_BG = -2` / `DEFAULT_CURSOR = -3` are sentinel values, resolved to the theme default color at render time.
- `TerminalPalette.BASIC_16` (VGA standard) + 256-color palette (16 basic + 216 cube `0/95/135/175/215/255` + 24 grayscale `8+10n`).
- `CellAttr` bitmask: BOLD/DIM/ITALIC/UNDERLINE/BLINK/INVERSE/HIDDEN/STRIKE.
- `CharWidth.wcwidth`: common full-width/CJK/emoji ranges return 2, everything else 1 (a simplified implementation sufficient for mobile SSH, not full Unicode TR11).

## Buffer model

### Dual screen

- **Normal screen**: `ArrayDeque<TerminalLine>`, the last `rows` lines are the visible screen and the lines before them are scrollback (capped at `maxScrollbackLines`, UI buffer 10,000; mosh shadow terminal `Int.MAX_VALUE` — the shadow must mirror the server completely or the diff misaligns).
- **Alternate screen**: fixed `rows×cols`, no scrollback (vim/tmux/htop and other full-screen programs). 1049 clears on entry (xterm behavior), 1047/47 do not.

### Line-level COW and incremental sync (the key to rendering performance)

Each `TerminalLine` carries:

- `version: Long` — incremented when cells are actually rewritten (`touch()`);
- `identity: Any` — logical line identity, preserved across clone/copy;
- `shared: Boolean` — COW flag: lines with true cannot be modified in place and must be cloned before writing (`mutableLine`).

Two core APIs:

- `shallowFork()`: line objects are shared and copied on write, O(lines) instead of O(cells) — used for mosh shadow state forking (copy-on-write over shared line objects). Forking constructs with `rows=0` and then appends the source lines, avoiding phantom blank lines accumulating across generations of forks.
- `copyContentFrom(other)`: **line-level incremental sync** — lines with the same identity and an unchanged version are reused directly, and only changed lines are cloned. When the UI buffer copies state from the mosh shadow each frame, unchanged lines are zero-copy; returns whether there was a visual change, and skips redraw when there is none.

### Wide characters (2 cells end to end)

- `width` (1/2) is precomputed at write time; rendering no longer calls wcwidth.
- The tail (`isWideTail`) **inherits the head's color/attributes**: otherwise the right half of a wide character shows the default background, appearing as a black block covering half the glyph on lines with a background color (e.g. message bars in agent TUIs).
- Overwrite semantics: writing onto a tail clears the head first; overwriting a wide-char head clears the tail marker; when a tail covers an existing wide-char head, that head's original tail marker is cleared.
- Writing a wide character at the end of a line: with DECAWM on, wrap; with it off, overwrite the last cell like a narrow character.
- `pendingWrap`: writing a narrow character at the end of a line only marks a deferred wrap, consumed when the next character arrives (xterm semantics; with DECAWM off it is clamped to overwrite at the end of the line).

### resize (xterm style)

- Screen gets shorter (keyboard appears): prefer discarding **blank lines below the cursor**, keeping the cursor and prompt in the visible area; any shortfall in shrinkage naturally pushes top lines into scrollback.
- Screen gets taller: take lines back from scrollback or create new blank lines.
- When adjusting line width, shared lines are cloned first (COW); if truncation leaves a wide-char tail dangling, it is cleared.
- Changes to resize/wide-char logic in `term/` must be accompanied by `TerminalBufferTest` updates.

## State machine and sequence support

`TerminalEmulator`: incremental UTF-8 decoding (invalid sequences become U+FFFD or start over) + a ten-state state machine
(GROUND / ESCAPE / ESC_INTERMEDIATE / CSI_ENTRY / CSI_PARAM / CSI_INTERMEDIATE /
OSC / OSC_ESC / DCS / DCS_ESC).

### C0 control

| Sequence | Behavior |
|------|------|
| NUL, BEL | ignored |
| BS | backspace (undoing pendingWrap takes priority) |
| HT | jump to the next tab stop (every 8 columns by default) |
| LF, VT, FF | line feed (constrained by the scroll region) |
| CR | carriage return |
| SO / SI | switch G1 / G0 character set |
| other C0, DEL | ignored |
| C1 (0x80–0x9F) | ignored (unsupported, must not be drawn as characters) |

### ESC sequences

`7` DECSC / `8` DECRC (save/restore the cursor **and pendingWrap, character set** — tmux depends on the complete state);
`D` IND, `E` NEL, `M` RI, `H` HTS, `c` RIS, `=` DECKPAM, `>` DECKPNM;
`(` / `)` character set designation (`0` = DEC special graphics), `#8` DECALN.

### CSI

| Category | Sequences |
|------|------|
| Cursor movement | CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP(HVP)/VPA |
| Erase | ED 0–3 (including clearing scrollback), EL 0–2, ECH |
| Insert/delete | IL/DL/ICH/DCH |
| Scrolling | SU/SD, DECSTBM (`r`) |
| Cursor save | SCOSC/SCORC (`s`/`u`) |
| Attributes | SGR (see below), DECSCUSR (`q`, 1–6) |
| Replies | DSR (5/6), DA (`ESC[?1;2c`), DA2 (`ESC[>1;2;0c`), DECRQM (`$p`) |
| Other | REP (`b`, repeat the last character, max 1024) |

### Modes (SM/RM)

| Mode | Meaning |
|------|------|
| 4 | IRM insert mode |
| ?1 | DECCKM application cursor keys |
| ?6 | DECOM origin mode |
| ?7 | DECAWM auto-wrap |
| ?25 | DECTCEM cursor visibility |
| ?47 / ?1047 / ?1049 | alternate screen (1049 saves/restores the cursor on enter/exit) |
| ?1048 | save/restore cursor |
| ?2004 | bracketed paste |
| ?1000 / ?1002 / ?1003 | mouse reporting (X10 / button drag / all events) |
| ?1004 | focus events (CSI I / CSI O) |
| ?1006 / ?1015 | SGR / urxvt mouse coordinate format (mutually exclusive) |
| ?1007 | alternate-screen wheel to arrow keys |
| 20 | LF→CRLF: **intentionally ignored** |

### SGR

`0/1/2/3/4/5/7/8/9`, `21→underline`, `22/23/24/25/27/28/29`, `30–37`, `38;5;n`, `38;2;r;g;b`,
`39`, `40–47`, `48;5;n`, `48;2;r;g;b`, `49`, `90–97`, `100–107`.

### OSC

| OSC | Behavior |
|-----|------|
| 0/1/2 | title change |
| 4;idx;? | palette query (multiple pairs allowed, replied in the xterm `rgb:rrrr/gggg/bbbb` format) |
| 8 | hyperlink start/end |
| 10/11 | default foreground/background color **query** (TUIs such as herdr decide contrast from this, so a reply is required; setting is not supported yet) |
| 12 | cursor color query/set (only `#RRGGBB`) |
| 52 | clipboard write/query (controlled by a settings toggle, base64) |
| other | ignored |

### DCS

- `$q` DECRQSS: replies to DECSTBM / SGR (default state only) / DECSCUSR queries, invalid queries get `0$r`.
- All other DCS (sixel / tmux passthrough, etc.) are ignored, with a 64KB buffer cap to prevent memory bloat.

## Rendering (ui/TerminalView.kt)

- **Line-level text layout cache**: `(identity, version)` key + LRU of 256 lines; redrawing an unchanged line only replays
  `TextLayoutResult`, skipping text measurement — full-screen text layout is the most expensive part of every frame, and during scrolling almost all of the full-screen translation hits the cache.
- Style cache: TextStyle cached by (ARGB, bold, narrow/wide font); BOLD + the basic 8 colors brightened to 8–15 (xterm convention).
- Cells with only a background color and the default foreground pick black/white foreground automatically by background luminance (deep background + dark text no longer becomes a black block in light themes).
- Fonts: JetBrains Mono is bundled; Android provides the CJK fallback.
- Cursor: DECSCUSR steady styles (2/4/6) are always on, blinking styles alternate on a 530ms phase; under a block cursor the character is redrawn inverted.
- Target column-count mode: back-computes the font size from a 12sp reference measurement and corrects the column count by measuring each time (see `effectiveFontSizeSp`).

## Test discipline

Any `term/` change (escape sequences, buffer behavior, wide characters) must come with a `commonTest/` unit test:
`TerminalBufferTest` / `TerminalEmulatorTest` / `TerminalColorTest` / `TerminalSelectionTest`.
