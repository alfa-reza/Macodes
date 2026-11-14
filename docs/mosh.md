# Mosh Implementation Notes (dev.macodes.mosh)

> **Summary:** implementation notes for the pure-Kotlin Mosh client —
> AES-128-OCB wire crypto, the SSP transport state machine (fragmentation,
> RTT estimation, ACK/throwaway), the shadow terminal that reuses our own
> emulator, the local-echo prediction engine, and roaming via UDP port rotation.

## Why We Built Our Own

To keep the protocol implementation platform-independent, `dev.macodes.mosh`
implements the SSP protocol, AES-128-OCB, zlib fragmentation and direct UDP in
pure Kotlin, without depending on the official client's native binary.
The implementation is based on the publicly documented protocol behavior and
wire format, and stays wire-compatible with the official client.

## Implementation Basis

An independent Kotlin rewrite: the SSP wire protocol (the wire format and proto
field numbers are required for interoperability), AES-128-OCB (RFC 7253, with
matching RFC vector tests), zlib (RFC 1950/1951).
The mosh references noted in the code are behavioral comparisons
(wire-compatibility constraints), not code copies.

## Layering and Independence

mosh has two layers, with a deliberately clean boundary:

- **Protocol layer (zero dependencies, extractable as a standalone library)**:
  `MoshTransport` / `UserStream` / `Fragmentation` / `Messages` / `Ocb` / `Aes` /
  `MoshCrypto` / `KmpMoshSession` — does not import `dev.macodes.term`, and depends
  only on the Kotlin standard library and kotlinx. To reuse it in other projects,
  this layer can be split into its own module.
- **Shadow layer (necessarily depends on the terminal emulator)**:
  `ShadowTerminal` / `PredictionLayer` reuse the `term/` emulator to maintain a
  mirror of the server-side frame buffer — the mosh protocol requires the client
  to hold terminal state; reusing our own emulator instead of writing another one
  is a deliberate decision (same behavioral origin + the benefit of line-level
  incremental sync), not a coupling defect.

Discipline: the protocol layer must not `import dev.macodes.term` (see AGENTS.md).

## Component Inventory

| File | Responsibility |
|------|------|
| `MoshCrypto.kt` / `Ocb.kt` / `Aes.kt` | AES-128-OCB datagram encryption/decryption |
| `MoshTransport.kt` | SSP transport layer: sender + receiver state machine |
| `UserStream.kt` | local input state stream (diff = queue suffix, subtract = drop known prefix) |
| `Fragmentation.kt` | instruction fragmentation + zlib compression / reassembly |
| `Messages.kt` | hand-written transportinstruction / userinput / hostinput proto codec |
| `ShadowTerminal.kt` | shadow of the server-side terminal state (reuses the term/ emulator) |
| `PredictionLayer.kt` | local predicted echo |
| `KmpMoshSession.kt` | session event loop, port rotation, lifecycle |
| `MoshPlatform.kt` | expect: UDP socket + zlib |

## Encryption

- Session key: the 22-character base64 key printed by `mosh-server` during SSH bootstrap (16 bytes, `==` padding removed). Length + canonical encoding are validated (pseudo keys with non-zero trailing bits are rejected).
- AES-128-OCB: table-driven AES (**no constant-time guarantee** — see the threat model in `crypto/README.md`; the key is delivered over the SSH channel, which is the one exception to that caveat on the production path). The L table follows the protocol convention; correctness is covered by independent standard test vectors (`OcbTest`).
- Wire format: `8B nonce low bits || OCB ciphertext || 16B tag`; nonce = `4B 0 || 8B big-endian sequence number`; plaintext = `2B timestamp || 2B timestamp_reply || payload`.
- **Direction bit**: the highest bit of the sequence number distinguishes direction (TO_CLIENT=1). Received packets have their direction bit checked, preventing a packet the client already sent from being replayed back as legitimate data.
- The session terminates when the encrypted block count reaches 2^47 (OCB birthday bound).

## SSP Transport Layer

- **Time**: all monotonic milliseconds (injected `nowMs()`), immune to wall-clock drift.
- **RTT**: Jacobson/Karels estimation; send interval = SRTT/2 clamped to 20–250ms; RTO 50–1000ms; timestamp 16-bit wraparound difference.
- **Instruction**: `(protocol_version=2, old_num, new_num, ack_num, throwaway_num, diff, chaff)`. Fragmented by MTU (IPv4 1252 / IPv6 1216, minus the 12-byte Connection header + 16-byte OCB), zlib compressed; on EMSGSIZE it falls back to the conservative MTU 472.
- **Receiver**: old packets only update timestamps/RTT and never enter the program; out-of-order insertion by new_num; ack absorbs the already-confirmed sent states; throwaway cleanup; rate-limited for 15s once the receive state exceeds 1024 (state quench).
- **Sender**: sent states capped at 32 (trimmed from the middle); `rationalizeStates` subtracts the prefix the peer already knows; prospective resend (falls back to the earliest unconfirmed state if the diff is smaller); chaff adds 0–16 random bytes to obfuscate instruction length; the CE congestion mark subtracts 500ms from the timestamp so the peer lowers its frame rate.
- **Empty diff (pure ack/heartbeat) does not trigger a redraw**, avoiding a full UI copy every 3s.
- **shutdown handshake**: num = ULONG_MAX marker, 16 attempts / 10s timeout; a clean exit once both sides have acked.

## Shadow Terminal (ShadowTerminal)

Reuses the project's own emulator, evolving per `HostMessage` diff (`HostBytes` / `Resize` / `EchoAck`):

- `fork()` = `buffer.shallowFork()` (COW shallow fork, O(rows)) + a new emulator instance — forking likewise only deep-copies the buffer.
- `buffer` has a Mutex: the session coroutine's applyDiff/resize and the UI copy thread are mutually exclusive (reading rows mid-resize would go out of bounds).
- The shadow buffer's scrollback is unbounded (`Int.MAX_VALUE`): the UI buffer trims itself, and truncating the shadow would misalign diffs.

**Intentional shadow semantics** (explicitly documented in the `ShadowTerminal.applyDiff` comment; do not change them to "align with the server"):

| Behavior | Server default semantics | This project's shadow |
|------|------------------------|-----------|
| Alternate screen (1049) | ignored (the leftover image after leaving vim is its famous flaw) | fully supported |
| resize | top-anchored, drops bottom rows | xterm-style bottom-anchored, prefers dropping blank rows below the cursor |
| scrollback | none | retained |

diffs only affect the visible area by row number, so the semantic differences do not break the protocol — but the shadow feels better.

## Local Predicted Echo (PredictionLayer)

The key to "instant" typing under high RTT, a simplified version of the prediction engine:

- **Trigger hysteresis**: on when send_interval >30ms, off at ≤20ms (may only turn off when there is no active prediction); underlining (flagging) on at >80ms, off at ≤50ms.
- **Prediction carrier**: replays pending input on a COW fork of the confirmed state; whitelist — printable characters (including UTF-8), 0x7f backspace ("\b \b" semantics), CR (shell canonical mode echoes \r\n), CSI/SS3 C/D arrow keys; **any other control byte → abandon this segment** (conservative policy).
- **The alternate screen only predicts printable-character segments**: control bytes have varying semantics in full-screen programs (in vim normal mode x deletes a character, not a backspace).
- **Absorption**: echo_ack >= the carrying frame number means the echo has arrived (or the fact of "no echo", e.g. password input), and the prediction is discarded; unabsorbed predictions are replayed on every new confirmed state, avoiding "appear → disappear → reappear" flicker.
- **Abandonment**: large pastes (>128 bytes) are not predicted; a glitch hanging for 3s returns to the confirmed state; a resize invalidating the geometry resets.

## Session and Roaming (KmpMoshSession)

- Event loop coroutine (Default) + one blocking read coroutine per socket (IO); UI input/resize/close are merged in via a Channel.
- **Port rotation** (mosh client semantics): 10s without a peer ack and 10s without changing the source port → switch to a new socket; the old socket stays to receive packets, and is pruned once the new port has been stable for 60s; at most 10 at a time.
- **Never actively times out after connecting** (the core of roaming): network loss is sustained by retransmission (ACTIVE_RETRY window) + port rotation, and resumes seamlessly when the network returns (WiFi↔cellular, tunnels, sleep/wake); the server likewise never times out. Only in the `still_connecting` phase does 15s without a peer packet count as failure.
- Link health: seconds since the last received packet, callback only when the value changes (idle heartbeat ~3s, normally 0~3); the UI shows a "lost contact" banner at 5s.

## Theme Injection (Mosh-specific)

mosh-server **swallows** the OSC 10/11 color queries sent by the remote TUI — the
queries never reach the phone, so applications like herdr can only get the host
terminal's theme, and under a light phone theme the remote still renders as dark.

Solution: write the phone's terminal colors (OSC 10/11 + OSC 4 responses for the
basic 16 colors) into the mosh input stream as "user input"
(`buildThemeSyncPayload`), and herdr parses and adopts them as if it had received
a terminal response. Constraints:

- Injected only when the host has a startup command configured (TUI session) and `moshThemeSync` is enabled — a plain shell's readline does not parse OSC and would echo the bytes as input, producing garbage.
- Injected 1.2s after connecting: any earlier and readline treats it as input, any later and herdr shows a gray overlay.

## Testing

- Unit tests (`commonTest/mosh/`): `OcbTest` (independent standard decryption vectors, not a self-consistent round-trip), `SspTest`, `TransportTest`, `PredictionTest`.
- **No mosh-server integration tests**: CI does not install mosh-server, and androidUnitTest only covers sshj/SFTP integration. When changing the send/receive logic in MoshTransport/KmpMoshSession, it is recommended to start a real mosh-server locally for manual verification (or first run the unit tests + connect to a real host from Android).

## Known Trade-offs

- Table-driven AES has no constant-time guarantee (see the threat model in crypto/README.md).
- The prediction engine is a simplified version: segments containing unsupported control bytes on the normal screen are not predicted; no per-cell absorption.
- The ECN CE bit is not exposed by the platform yet, so `congestionExperienced` is always false (the field and handling path are already in place).
