# Contributing to Macodes

Macodes is an Apache-2.0-licensed mobile SSH/Mosh client and native Agent workspace,
based on the MIT-licensed Termish (see NOTICE).
Bug reports, documentation improvements and pull requests are welcome.
You can contribute in English.

## Get started

```bash
git clone https://github.com/ttermish/termish.git
cd termish
```

| Component | Development requirements |
| --- | --- |
| Android | JDK 17, Android SDK, and an emulator or device for UI checks |
| Remote services and build tool | Rust via rustup; supported target libraries; Xcode SDK for macOS payloads; Python 3 for macOS relay tests |
| R2 release tools | Node.js 22 and curl with `--aws-sigv4` support |

Set the Android SDK path in `local.properties` or `ANDROID_HOME`.
Debug builds and tests do not require the maintainer's signing keys, cloud
accounts, private machines or AI provider credentials. Agent integration testing
uses your own configured provider account.

## Build & Test

Run these commands from the repository root.

### Android

```bash
./gradlew :composeApp:assembleDebug            # Build a debug APK
make run                                     # Build, install and launch on a device/emulator
```

### Tests

```bash
./gradlew :composeApp:testDebugUnitTest         # Kotlin unit tests
./gradlew agentBridgeTest                      # Rust Bridge protocol and adapter tests
./gradlew screenServiceTest                    # Rust screen protocol, configuration and lifecycle tests
./gradlew screenServiceRustBuild               # Build precompiled remote Rust payloads
make test-integration                         # Start local sshd and run transport tests
```

Kotlin tests cover the terminal emulator, Mosh, crypto RFC vectors and app logic.
Transport tests detect a local sshd on `127.0.0.1:22222` and skip when it is absent.
For manual transport checks, `./scripts/test-sshd.sh` starts the test server with
ephemeral keys. Bridge tests can also run without Gradle:
`sh scripts/service-test.sh agentBridge`.
Screen service tests can also run with
`sh scripts/service-test.sh screenService`.
Mobile builds now need Rust via rustup and at least one supported service target.
For example, Linux builders use `rustup target add x86_64-unknown-linux-musl`;
macOS builders can build all four targets as described in the screen service guide.

### iOS

iOS is not built in this repository. The Android and shared-KMP code keep
their platform seams, so iOS support can be added later by registering an
iOS target and filling `iosMain` without touching `commonMain`.

Run `make help` for other tasks. Release signing is only needed for release
builds; [`.env.example`](.env.example) documents the optional configuration.
Maintainers can find Android release mirroring configuration in the
[R2 release guide](docs/r2-release.md).

## Where changes belong

- `composeApp/src/commonMain/kotlin/dev/termish/term/` contains the pure-Kotlin terminal
  emulator. Keep platform and UI dependencies out of this module.
- `composeApp/src/commonMain/kotlin/dev/termish/mosh/` contains the Mosh protocol
  and prediction layers. Preserve the boundary documented in [AGENTS.md](AGENTS.md).
- `composeApp/src/commonMain/kotlin/dev/termish/ui/` contains shared UI. Use the
  existing theme tokens and put user-facing copy in `AppStrings` in both languages.
- Platform code belongs behind the existing SSH, storage and utility interfaces.
- `agentBridge/rust/` contains the Rust companion and its tests. Target binaries are
  built into the mobile app; see [the Bridge guide](agentBridge/README.md).
- `screenService/` contains the standalone remote-screen service and installer.
  Gradle generates the bundled Kotlin installer before compilation; see the
  [screen service guide](docs/screen-service.md).

## Validate your change

Before a code PR, run `make test` and `make lint`. Format Kotlin with
`./gradlew ktlintFormat` before `make lint-kt`.

- Terminal or Mosh behavior: add a regression test for the affected behavior.
- SSH, SFTP or Mosh transport: run `make test-integration`.
- Agent protocol: check both the Rust Bridge and Kotlin client.
- UI: check the affected platform, both languages and both themes; attach screenshots.
- Documentation-only changes: check links and factual accuracy; no app build is needed.

Report skipped checks and their reason in the PR. Use your own test hosts and
accounts. Never attach passwords, private keys, access tokens or unredacted
terminal history to a public report. See [SECURITY.md](SECURITY.md) for private
vulnerability reporting.

## Documentation

Keep [README.md](README.md) consistent in structure and facts.
Detailed development documents in `docs/` are in English.
Update the relevant architecture, terminal, Mosh or input-pipeline document when
behavior changes. Changes to Agent Bridge belong in its protocol documentation.

## Submit a pull request

1. For a substantial feature, open an [issue](https://github.com/ttermish/termish/issues)
   to describe the user problem and proposed scope before implementation.
2. Fork the repository and create a branch for a focused change.
3. Explain the resulting behavior and the checks you ran in the PR template.
4. Keep unrelated refactors, generated build output and personal settings out of the PR.

Contributions and commit messages are in English.
Contributions use the project's [Apache License, Version 2.0](LICENSE). Preserve
third-party notices in `NOTICE`, `LICENSES/` and bundled resources, including the
upstream Termish MIT notice in `LICENSES/Termish-MIT.txt`. Community participation
follows the [Code of Conduct](CODE_OF_CONDUCT.md).

Version bumps, signing and release tags are managed by the maintainers. A normal
contribution does not need a version bump or access to release credentials.
