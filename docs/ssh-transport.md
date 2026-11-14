# SSH Transport Layer (ssh/)

> **Summary:** the platform-neutral `SshSession` contract and the sshj+
> BouncyCastle engine on Android. Covers the auth chain (password /
> publickey / passphrase / keyboard-interactive), the single-threaded reader
> discipline that keeps escape-sequence decoding race-free, mosh-server
> bootstrap, and system probing.

## Division of Labor

`commonMain/ssh/` defines only the platform-neutral contract; the engine is
injected via expect/actual:

| Layer | File | Responsibility |
|----|------|------|
| Contract | `SshSession.kt` | `SshSession` / `SshCallbacks` / `SshConnection` / auth and fingerprint models |
| Contract | `SftpSession.kt` | SFTP abstraction (see sftp.md) |
| Contract | `MoshSession.kt` | mosh client abstraction + `parseMoshConnect` / bootstrap command / system probing |
| JVM engine | `androidMain/…/SshSessionSshj.kt` | sshj + BouncyCastle (Android) |
| Factory | `SshEngine.android.kt` | `actual fun createSshSession` |

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
- `detectSystemFromOutput`: prefers os-release's `ID=` (ubuntu / debian / …),
  otherwise normalizes from `uname -s` to linux / macos / freebsd / openbsd

## Testing

- `commonTest/ssh/`: `EncryptedPemTest` (encryption detection for the three PEM
  formats), `MoshConnectParseTest` (MOSH CONNECT parsing), `SystemDetectTest`
- `androidUnitTest/ssh/`: `SshjIntegrationTest` / `SftpIntegrationTest` against a
  real sshd (127.0.0.1:22222, started automatically by `scripts/test-sshd.sh`),
  **self-detect, SKIP when sshd is absent does not count as a failure**
