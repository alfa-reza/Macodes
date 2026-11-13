# Termish Agent Bridge

> **Summary:** a bundled Rust companion runs Agent CLIs behind the user's SSH connection. It provides persistent sessions, streaming events, bounded history and native approval forwarding without a public listening port.

## Installation and running

The phone probes the system and architecture over SSH, uploads the bundled binary over SFTP, verifies
SHA-256 and the service version, then enables it atomically.
macOS/Linux arm64 and x86_64 are supported; the remote host needs no Rust compiler. The Agent itself
still needs a login or provider configuration, and a missing CLI can be installed through the phone
into the user directory with npm, without sudo.

```text
Termish → SSH exec → termish-agent connect → private Unix socket → Rust daemon → Agent CLI
```

Build and test:

```bash
sh scripts/service-build.sh build-agent --all  # Mac + Xcode SDK: builds all four targets at once
./gradlew agentBridgeBuild                    # builds and packages the targets available locally
./gradlew agentBridgeTest                     # protocol and adapter tests without a model account
sh scripts/service-test.sh agentBridge        # the same tests without Gradle
```

Binaries and manifests live in `agentBridge/build/binaries/`; generated Kotlin lives in
`composeApp/build/generated/agentBridge/kotlin/`; Compose file resources live in
`composeApp/src/commonMain/composeResources/files/termish-agent/`. Generated artifacts are not
committed.

## Storage and lifecycle

```text
~/.local/share/termish-agent/
├── current/termish-agent
├── current/termish-agent.NOTICE
├── data/sessions/<id>.json
├── npm/                         # optional user-directory CLI installation
└── runtime/rust/{agent.sock,agent.pid,agent.log,agent.version}
```

Since version 0.9.0 the service stores sessions in independent JSON files; SQLite records from older
versions are neither migrated nor read.
Every save lands through a temporary file and an atomic replacement; directories are 0700, files and
sockets 0600, umask 077.
The native runtime directory is separate from the old service, so an old socket is never mistaken for
the Rust service.
After installing and successfully starting the native service, the phone checks the account that owns
the process, the full command line and the Python interpreter, stops that account's old Python
companion, and deletes `current/termish-agent.pyz`.
If the old process does not exit, the old files are kept and the installation is reported as failed;
old SQLite data and current Rust sessions are not deleted.

`ensure-running` starts the daemon on demand; `restart` asks the old Rust instance to exit through the
private socket and then starts a new one.
No systemd or root is needed. An SSH relay disconnect does not terminate Agent tasks; reconnecting
retrieves running text, tools and approvals.
Exiting or restarting the service cancels its own tasks and cleans up the child process group, after
which persisted sessions can continue.

## Protocol and adapters

NDJSON carries traffic between SSH stdin/stdout and the socket, protocol version 3, with an 8 MiB
per-request limit.
Supported methods: `system.hello`, Agent inspection/installation, session
create/list/rename/model settings/pagination/delete, native history discovery and import, prompt
send/cancel, approval replies and event replay.

Adapters cover the Codex App Server, Claude Code stream-json, Gemini CLI stream-json, OpenCode JSON
and Pi RPC. Events uniformly express text, thinking, tool start/end, errors, cancellation,
settlement, installation output and busy state, preserving turn/activity/message IDs, ordering and
timestamps.

Codex command, file-change and permission requests, and Pi confirm/select/input/editor requests, are
forwarded to the phone for approval; pending approvals can still be answered after an SSH reconnect.
Timed-out, cancelled or unsupported approvals are rejected, and the service never adds
auto-approve flags. Command resolution covers PATH, common user install locations and NVM. Real
CLI/model behavior still needs verification in an account environment.

Native history can discover and import sessions from Codex, Claude, Gemini, OpenCode and Pi, keeping
the native resume ID and leaving the original files untouched. Attachments come from the workspace's
`.termish/attachments/`; out-of-tree paths and symlink escapes are rejected. Records are deleted with
the session, shared attachments are not removed early, and expired unreferenced files are reclaimed at
startup.

Provider configuration IDs and adapter types are transmitted separately. An API key is only used for
the current task's environment variables or a model query, never written into the session, and errors
and events are redacted. `providers.fetchModels` uses the remote curl; the Agent itself may still keep
its own credential store. DeepSeek is supported through the corresponding interfaces of Claude Code,
OpenCode and Pi.
