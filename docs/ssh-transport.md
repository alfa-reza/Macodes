# SSH Transport Layer (ssh/)

> **Summary:** the platform-neutral `SshSession` contract and the two
> engines behind it — sshj+BouncyCastle on Android and a hand-
> written libssh2 cinterop engine on iOS. Covers the auth chain (password /
> publickey / passphrase / keyboard-interactive), the single-threaded reader
> discipline that keeps escape-sequence decoding race-free, non-blocking
> polling on iOS, mosh-server bootstrap, and system probing.

## Division of Labor

`commonMain/ssh/` defines only the platform-neutral contract; the engine is
injected via expect/actual:

| Layer | File | Responsibility |
|----|------|------|
| Contract | `SshSession.kt` | `SshSession` / `SshCallbacks` / `SshConnection` / auth and fingerprint models |
| Contract | `SftpSession.kt` | SFTP abstraction (see sftp.md) |
| Contract | `MoshSession.kt` | mosh client abstraction + `parseMoshConnect` / bootstrap command / system probing |
| JVM engine | `androidMain/…/SshSessionSshj.kt` | sshj + BouncyCastle (Android) |
| iOS engine | `iosMain/…/SshSessionLibssh2.kt` | libssh2 + statically linked OpenSSL, hand-written cinterop |
| Factory | `SshEngine.jvm.kt` / `SshEngine.ios.kt` | `actual fun createSshSession` |

## SshSession Contract

```kotlin
interface SshSession {
    fun connectAndStart(columns: Int, rows: Int): SessionInfo  // blocking: connect + auth + PTY + shell
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)
    fun sendData(data: ByteArray)
    fun connectAndRun(command: String, timeoutMs: Long): CommandResult  // one-shot exec (mosh bootstrap)
    fun probeSystem(): String?   // opens a temporary exec channel on the authenticated connection, no re-auth
    fun close()
    fun isActive(): Boolean
}
```

**Callback contract (important)**: `onOutput`/`onStderr`/`onPrompt` are
**suspending functions** — the engine calls them in the reader coroutine, and the
upper layer can apply backpressure at the boundary (`TerminalController.enqueueOutput`
posts to a bounded queue and suspends when it is full → the engine stops reading
the socket → the TCP window converges, so `cat`ting a large file loses no bytes
and does not blow up memory). `verifyHostKey` is called **synchronously blocking**
during the handshake; the two 120s timeout fallbacks are described in architecture.md.

## JVM Engine (sshj)

Transport / KEX / encryption are all delegated to the battle-tested sshj +
BouncyCastle; this class only handles lifecycle and callbacks.

### Auth Chain (tried in order)

1. **Private key** (`AuthPublickey`): `isEncryptedPem` statically determines
   whether the PKCS#8 / legacy PEM / OpenSSH formats are encrypted (for OpenSSH it
   manually parses the `openssh-key-v1` header's ciphername rather than guessing);
   an encrypted private key first asks for the passphrase via `onPrompt` (asked
   only once, never written to disk); if the passphrase prompt is cancelled,
   `privateKeyError` is recorded and the reason is appended to the final error
2. **Password** (`AuthPassword`)
3. **keyboard-interactive** (`AuthKbi`) as a fallback: two-factor / servers forcing KBI

On auth failure with a private key load failure, the error message carries a
"private key load failed: …" hint, so the user does not just see "authentication
failed" without knowing the private key never took part.

### Threading Discipline

- **reader**: the stdout / stderr readers share a `limitedParallelism(1)` dispatcher —
  if `onOutput`/`onStderr` were called concurrently, they would enter the
  lock-free `TerminalEmulator` state machine at the same time (escape sequences
  interrupted, UTF-8 corrupted across chunks), so they **must be fed to the upper
  layer serially**
- **writer**: `limitedParallelism(1)` write dispatcher — avoids socket writes on the
  main thread (Android throws NetworkOnMainThreadException) + guarantees input ordering
- polls `getExitStatus` within 2s of the channel closing, reporting the exit code to the UI

### Fingerprints

`Buffer.PlainBuffer().putPublicKey(key)` serializes the public key blob, then
SHA-256 / MD5, matching OpenSSH's fingerprint convention.

## iOS Engine (libssh2)

662 lines of hand-written cinterop — the file in the whole project that needs the
most careful maintenance. libssh2's cinterop mapping is incomplete and it is not
thread-safe; every workaround exists for these two facts.

### Threading Model: single-threaded serial dispatch

`serialDispatcher = Dispatchers.Default.limitedParallelism(1)` — **the read loop /
writes / resize / keepalive / cleanup are all dispatched to the same thread**,
ruling out concurrent access to a single `LIBSSH2_SESSION` (libssh2 is not
thread-safe; concurrency crashes outright).

- the read loop is a resident coroutine: non-blocking `channel_read_ex`,
  `delay(30)` on `EAGAIN` while also sending the application-level keepalive (the
  keepalive must be sent from the read thread — the fundamental reason it can only
  live here)
- `sendData`/`resize` are posted from the UI thread to the serial thread for
  execution, never touching libssh2 on the calling thread
- `close()` is made async: wait for the reader to exit before cleanup (guarding
  against use-after-free); on a dead link `channel_close` may loop on EAGAIN for
  seconds, and it must not block the UI thread

### Two-Phase Blocking ↔ Non-blocking

- **Handshake / auth / exec bootstrap**: blocking mode (`session_set_blocking(1)`), straightforward code
- **Interactive shell**: switch to non-blocking + polling — the only way to respond to `close()` and send keepalives on the same thread
- `retryUntilSuccess`: EAGAIN retries under a uniform 10s timeout

### Three cinterop Workarounds (each one a trap already hit)

1. **`tcpConnect` hand-written non-blocking connect**: cinterop has no ready-made
   connect timeout. `getaddrinfo` + `O_NONBLOCK` + `poll(POLLOUT)`, then a second
   `connect` after readiness checking for `EISCONN` (a standard POSIX trick,
   avoiding the cross-platform type burden of `getsockopt(SO_ERROR)`). The timeout
   matches sshj's `setConnectTimeout` — a black-hole address fails fast instead of
   waiting for 60s+ of kernel TCP retries while the UI keeps spinning
2. **`writeAll`'s UTF-8 boundary**: `libssh2_channel_write_ex`'s cinterop mapping
   maps the buffer to `String` (UTF-8 encoding + explicit length), so rc may stop
   in the middle of a UTF-8 character; `bytesDone` can only advance by whole
   characters, and residual bytes are resent with the whole character (very
   unlikely, tolerated)
3. **`termish_sftp_write` native helper**: see sftp.md — libssh2_sftp_write only
   exposes the String variant, so binary upload must go through `unsigned char*`

### Host Fingerprint Convention

The blob returned by `libssh2_session_hostkey` already includes the algorithm
string prefix (consistent with `libssh2_hostkey_hash(SHA256)`, i.e. the OpenSSH
fingerprint convention) — SHA-256 the whole blob directly, **do not manually
prepend the type prefix** (the fingerprint would then not match known_hosts).

### KBI Global Callback

`staticCFunction` cannot capture variables → `kbiHandler` is a process-level
singleton guarded by `kbiMutex`: concurrent KBI auth across sessions is serialized,
preventing prompts from being routed to the wrong session.

## Mosh Bootstrap (connectAndRun)

mosh mode uses the `connectAndRun` one-shot exec channel:

```bash
mosh-server new -c 256 -p <fixed port> -l LANG=en_US.UTF-8      # fixed port (NAS/port forwarding)
mosh-server new -c 256 -l LANG=en_US.UTF-8                      # automatic port (60000-61000)
```

- `-c 256`: remote TERM=xterm-256color, consistent with local rendering capability
  (`-c 8` would make remote programs degrade to 8 colors)
- `MOSH CONNECT <port> <key>` in the output is parsed by `parseMoshConnect`; the UDP
  port / OCB key are delivered securely over the SSH channel
- the temporary SSH session used for bootstrap is routed to a **bootstrap callback
  that swallows onClosed** — otherwise the onClosed fired when bootstrap ends would
  misread CONNECTING as a user close and immediately destroy the freshly launched
  mosh-client (there is a detailed comment in TerminalController)

## System Probing (Termius-style auto-detection)

`probeSystem()` opens a temporary exec channel on the **authenticated connection**
and runs `cat /etc/os-release 2>/dev/null; uname -s`, without re-authenticating or
interrupting the interactive shell:

- sshj: `client.startSession()` on a new channel, 5s timeout
- libssh2: the session is not thread-safe, so probing must be dispatched to the
  read thread via `withContext(serialDispatcher)`
- `detectSystemFromOutput`: prefers os-release's `ID=` (ubuntu / debian / …),
  otherwise normalizes from `uname -s` to linux / macos / freebsd / openbsd

## Testing

- `commonTest/ssh/`: `EncryptedPemTest` (encryption detection for the three PEM
  formats), `MoshConnectParseTest` (MOSH CONNECT parsing), `SystemDetectTest`
- `androidUnitTest/ssh/`: `SshjIntegrationTest` / `SftpIntegrationTest` against a
  real sshd (127.0.0.1:22222, started automatically by `scripts/test-sshd.sh`),
  **self-detect, SKIP when sshd is absent does not count as a failure**
- the iOS engine has no automated tests (CI does not build iOS) — libssh2 changes
  must be verified on a real device/simulator, see the iOS build process in AGENTS.md
