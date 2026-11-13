# AGENTS.md — Termish

Kotlin Multiplatform SSH client with an Android development focus. It uses a
pure-Kotlin terminal emulator, Compose Multiplatform UI, sshj + BouncyCastle for
Android SSH, and a pure-Kotlin Mosh client (`dev.termish.mosh`).
Stack: Kotlin 2.1.21 · Compose Multiplatform 1.8.1 · AGP 8.9.2 · Gradle 8.14.2.

The README introduces the app; full build/test docs live in `CONTRIBUTING.md`
(Build & Test). Workflow tasks are defined in the root `build.gradle.kts`;
`Makefile` targets are thin aliases. Tests and lint run locally; CI is reserved
for building, packaging and publishing.

## Common commands

```bash
make run                 # build, install and launch the debug APK
make test                # unit tests (crypto RFC vectors + terminal emulator)
make test-integration    # transport integration tests (auto-starts local sshd)
make lint                # Android lint
make release             # signed release APK + AAB (requires .env signing secrets)
make bump V=1.0.1        # bump versions (preview: DRY=1)
```

The previous development machine used a KVM-hosted Android emulator with an
Intel Arc A380 GPU and `-gpu host` to avoid CPU-heavy software rendering. Choose
an emulator GPU mode appropriate for the current machine.

Test sshd: `./scripts/test-sshd.sh` (127.0.0.1:22222, generates ephemeral ed25519 keys).
The previous development environment used a `termish-demo` Docker container;
credentials and ports were kept in local, gitignored `.aiadb/` assets. The Android
emulator reaches the host at `10.0.2.2`.

## Architecture & conventions

- **`commonMain/term/` is a pure-Kotlin, zero-platform-dependency terminal
  emulator** (buffer / state machine / color / selection) — never pull platform
  APIs or Compose dependencies into it.
- **`commonMain/mosh/` protocol layer is term-free**: `MoshTransport` /
  `UserStream` / `Fragmentation` / `Messages` / `Ocb` / `Aes` / `MoshCrypto` /
  `KmpMoshSession` must never `import dev.termish.term` — only the shadow
  layer (`ShadowTerminal`, `PredictionLayer`) may use the emulator. This keeps
  the protocol layer extractable as a standalone library.
- Platform code lives behind expect/actual seams: `ssh/SshSession`, `util/`,
  `data/SecretStore`; `androidMain/` owns the Android JVM transport engine.
- Treat **IME composing text as a first-class citizen**: composing text never
  reaches the wire, only committed text is diffed, and backspace semantics
  differ between composing and committed text. Read `TerminalScreen` /
  `TerminalView` before touching input logic.
- UI design tokens live in `ui/theme/` (Dimens, palettes) — no ad-hoc dp/alpha
  literals in new UI code.
- User-facing strings go through `AppStrings` (Chinese + English), never hardcoded.
- **Code style**: Kotlin official style; import short names rather than calling
  fully qualified names, and sort imports alphabetically. `.editorconfig` and
  ktlint enforce the baseline; use `make lint-kt` to check and `ktlintFormat` to fix.
- New screenshots in `docs/screenshots/` use `<topic>-{en,zh}.png` or `.webp`
  naming, with matching English and Chinese pairs. Keep personal accounts and
  private host details out of screenshots.

## Documentation maintenance

- Keep `README.md` consistent in structure and facts.
- Use English for contributor documentation in `docs/`.
- Update `docs/terminal-emulator.md`, `docs/mosh.md`, or
  `docs/input-pipeline.md` when the corresponding behavior changes.
- Preserve third-party copyright notices, `NOTICE`, `LICENSES/`, and licenses
  bundled with assets. Original project source and documentation are MIT-licensed.

## Development workflow

Validate from fast to slow. Use debug builds and an emulator for normal work;
build a release at feature milestones or when preparing for acceptance.

```bash
# Unit tests: required for term/, mosh/, crypto/ and logic changes
./gradlew :composeApp:testDebugUnitTest

# Build, install and launch a debug build
make run

# Integration tests: required for transport changes
make test-integration

# Signed release: at milestones or for acceptance
make release    # composeApp/build/outputs/{apk,bundle}/release/
```

- Prefer emulator installation when one is available. Check on a real device for
  Chinese/Japanese IME composition, Mosh roaming, background keepalive,
  reconnection, performance and battery behavior.
- Run unit tests before the emulator for `term/` or `mosh/` changes.
- A release build is not required for every change.

### Local checks and release

- Run `./gradlew ktlintFormat`, followed by `make test lint lint-kt`, for local
  verification. Run `make test-integration` for transport changes. For Rust
  service changes run the service's `scripts/service-test.sh`; for release tooling
  run `node --test scripts/tests/*.test.mjs`. Report PASS / FAIL / SKIP for
  emulator, end-to-end and real-device checks as applicable.
- Releases require explicit user authorization. Verify version consistency,
  signing configuration, package signatures and artifact checksums; do not
  publish a release tag if checks fail.
- CI builds, packages and publishes on `v*` tags or manual runs; it does not
  repeat local unit tests, integration tests or lint. Do not claim a release
  succeeded without verifying it.

## Testing discipline

- Any change to `term/` (escape sequences, buffer, wide-char behavior) requires a
  matching unit test in `commonTest/`.
- Any change to the transport layer (sshj / mosh / SFTP) requires
  `make test-integration`.
- Integration tests self-detect 127.0.0.1:22222 and skip gracefully when sshd
  is absent; skipping is not a failure.

## Debug & edit discipline

- Use `TermLog` / `TermTrace` for debugging, not temporary `println` calls;
  remove temporary instrumentation after debugging.
- After bulk script edits, compile and inspect each replacement in the diff for
  semantic errors, shadowed variables, formatting and fully qualified names.
- Explain the impact before destructive environment operations such as
  `pm clear` or stopping a demo server.
- Run `./gradlew ktlintFormat` before `./gradlew ktlintCheck` to fix import
  ordering, indentation, wrapping and unused imports first.
- When generating Kotlin strings with Python, write `$var` / `${expr}` directly
  for interpolation; escaping the dollar sign produces literal variable names.

## E2E scenario testing

- `.aiadb/cases/` contains local, gitignored natural-language scenarios and is
  not distributed with the repository.
- Run relevant scenarios after connection, session, notification or network
  changes; add scenarios for new screens or features.
- Report PASS / FAIL / SKIP per scenario and distinguish application bugs from
  operation errors.

## Traps

- After editing `.env` / signing secrets run `make gradle-stop` (the Gradle daemon
  does not pick up new environment variables).
- Once a build is published, its signing private key must not change: doing so
  breaks signature consistency and upgrade installs. Keep backups off-repo.
- `.env` and `*.jks` are git-ignored; never expose real secrets or force-add them.
