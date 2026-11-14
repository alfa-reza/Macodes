# Architecture and Threading Model

> **Summary:** module layout of the KMP codebase, the expect/actual seams,
> the per-platform transport table, and the threading model that keeps the
> terminal buffer safe: SSH output is queued and consumed serially on the main
> thread (bounded queue + backpressure); the Mosh shadow terminal is owned by a
> session coroutine and synced to the UI buffer via a conflated channel.

## Module layout

```
composeApp/
├── commonMain/                # shared across all platforms (core assets)
│   ├── term/                  # pure-Kotlin terminal emulator (zero platform deps, see terminal-emulator.md)
│   ├── mosh/                  # pure-Kotlin mosh client (SSP protocol, see mosh.md)
│   ├── ssh/                   # platform-agnostic session abstractions: SshSession / SftpSession / MoshSession
│   │                          #   + expect factories createSshSession / createSftpSession
│   ├── crypto/                # pure-Kotlin crypto primitives (unaudited — see its README; production uses only Sha256)
│   ├── data/                  # Host / AppSettings @Serializable models, HostRepository
│   │                          #   (multiplatform-settings + JSON), SecretStore expect
│   ├── herdr/                 # herdr control-plane domain: HerdrProbe (probing) / HerdrApi (command construction) /
│   │                          #   HerdrMonitor (polling) / HerdrModels (snapshot models)
│   ├── ui/                    # AppRoot navigation, SessionManager, TerminalScreen/View,
│   │                          #   KeyToolbar, SFTP, Host/Connections/Settings pages, theme/
│   │                          #   TerminalController (session state / UI observation point) + SessionConnector
│   │                          #   (connection orchestration: three-mode connect/reconnect/herdr bootstrap fallback —
│   │                          #   state ownership lives in the controller, the connector reads/writes via same-package internal)
│   └── util/                  # SessionKeepAlive / NetworkChange / Dispatchers /
│                              #   MonoFont / AppLifecycle and other expect seams
├── androidMain/               # MainActivity + SessionService (foreground service keepalive) + Keystore
│   ├── ssh/SshSessionSshj     # sshj + BouncyCastle (ed25519 / chacha20 / RSA / kb-int)
│   └── mosh/MoshPlatform.android # UDP socket + java.util.zip implementation
├── commonTest/                # crypto RFC vectors + terminal emulator + mosh unit tests
└── androidUnitTest/           # local JVM tests: session/repository/sshj/SFTP (real sshd, 127.0.0.1:22222)
```

Android local tests run through `:composeApp:testDebugUnitTest`, and also include `commonTest`.
Session and controller tests use Robolectric to provide the Android environment; repository tests use an in-memory `MapSettings`.

The repository root's `screenService/` holds the macOS Python session service, ScreenCaptureKit/CoreGraphics native capture, the Linux Rust X11/Wayland service, configuration and install scripts.
Gradle generates `ScreenServiceAssets` before Kotlin compilation; the phone side keeps the existing SSH install/probe entry points;
the service runs inside the remote graphical session, with video and control reaching loopback ports over SSH direct-tcpip.
See [remote screen service](screen-service.md) for source layout, configuration and logs.

`agentBridge/rust/` holds the native Agent companion service; the phone likewise uploads the bundled binary per OS and architecture.
Control flow goes over SSH exec/NDJSON and a private Unix socket; new sessions persist atomically as JSON, with no migration from the old SQLite.
Build tooling lives in `tools/service-build/`; both service kinds generate the Kotlin manifest and file resources before compilation.

## Platform split

| Platform | SSH engine | Authentication | Mosh client | Key storage |
|------|----------|------|-------------|----------|
| Android | sshj + BouncyCastle | password / publickey / keyboard-interactive | pure Kotlin `dev.termish.mosh` (direct UDP) | Android Keystore (AES-GCM) |

## expect/actual seams

Platform code is only allowed behind these seams:

| Seam | commonMain | androidMain |
|------|-----------|-------------|
| `ssh.createSshSession` | expect | sshj engine |
| `ssh.createSftpSession` | expect | SftpSessionSshj |
| `data.SecretStore` | expect | Keystore AES-GCM |
| `util.SessionKeepAlive` | expect | foreground service + wakelock |
| `util.NetworkChange` | expect | ConnectivityManager |
| `util.Dispatchers` | expect | IO/Default |
| `util.MonoFont` | expect | bundled JetBrains Mono |
| `mosh.MoshUdpSocket` / zlib | expect | JVM socket + java.util.zip |
| `ui.FilePicker/FileSaver/DirectorySaver` | expect | SAF/DocumentFile |

## Threading model (conventions after the 2026-08 refactor)

The constraint in one sentence: **all reads and writes of `TerminalBuffer` (render / resize / selection / emulator writes) are serialized on the main thread**.

### SSH output path

```
sshj reader coroutine (IO thread)
    └─ onOutput/onStderr (suspending callbacks)
         └─ TerminalController.enqueueOutput
              └─ Channel<ByteArray>(256)   ← bounded queue
                   └─ main-thread consumer coroutine
                        ├─ emulator.write(chunk)     ← the only write entry into the buffer
                        ├─ drained in batches within an 8ms frame budget, redraw coalesced
                        └─ frame++ (Compose redraw signal)
```

- **Backpressure**: when the queue is full `send` suspends → the reader stops reading the socket → the TCP window closes. No bytes lost and no memory blow-up (the `cat` big-file case).
- **Serialization**: sshj's two readers, stdout/stderr, share a scheduler with `limitedParallelism(1)`, so escape sequences are never interleaved.
- The consume loop uses `receiveCatching` + `tryReceive` + `yield()` when the budget is spent; the jitter of one redraw per packet under a flood of small packets is eliminated.

### Mosh output path

```
KmpMoshSession event-loop coroutine (Default thread)
    ├─ shadow terminal (ShadowTerminal.buffer, guarded by Mutex)
    └─ onStateUpdate → Channel<ShadowTerminalView>(CONFLATED)
                        └─ main-thread consume: uiBuffer.copyContentFrom(view.buffer)
                             └─ per-line (identity, version) incremental comparison, no redraw without visual change
```

- CONFLATED keeps only the latest shadow state; at most one main-thread copy is in flight for bursty updates.
- The copy holds the shadow lock: the session coroutine cannot read mid applyDiff/resize, otherwise a half-done resize would leave inconsistent row state and go out of bounds.

### Write path

- sshj: `sendData` goes through a `limitedParallelism(1)` writer dispatcher (avoids socket writes on the main thread + guarantees input ordering).
- mosh: `sendData` posts to the event Channel, and the session coroutine pushes it into `UserStream` uniformly.
- Auth/host-key dialogs: `onPrompt` suspends with a 120s timeout treated as cancel; `verifyHostKey` blocks synchronously with a 120s timeout treated as reject — prevents a connection thread from hanging forever.

### Controller lifecycle

- `TerminalController` owns an `ioDispatcher + SupervisorJob` scope; `close()` preserves reentry capability (can reconnect), `destroy()` closes the queue and cancels the scope (reclaiming delayed tasks such as reconnect backoff / theme injection / stability-period reset).
- Automatic reconnect tasks all hang off `reconnectJob` and are cancelled on close, so a delayed coroutine cannot revive a session after it is closed.

## Session keepalive and network events

- Android: `SessionService` uses a `connectedDevice` foreground service + PARTIAL_WAKE_LOCK; active sessions are registered idempotently by `sessionId`, and it stops after the last session closes. On returning to the foreground it runs an SSH health check first and reconnects only when the check fails.
- Network switch: on a "new network ready" event SSH runs a health check first and reconnects quickly only when the check fails (30s immunity window + 15s debounce + monotonic clock); mosh does not rebuild, relying on UDP roaming to self-heal.

## Persistence

- Non-secret data (host list / settings / recent sessions) → `multiplatform-settings` (JSON, `ignoreUnknownKeys`).
- Secret data (passwords / private keys) → `SecretStore` (Android Keystore).
- Parse failures never lose data: the raw string is backed up to a `*.corrupt.<ts>` key (at most 3 copies).
