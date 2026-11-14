<h1 align="center">Macodes</h1>

<p align="center"><strong>Your remote workspace, in your pocket.</strong></p>
<p align="center">An Android workspace for SSH, Mosh, files, and coding agents.</p>

<p align="center">
  <a href="https://github.com/alfa-reza/Macodes/releases/latest"><strong>Download for Android</strong></a> ·
  <a href="#get-started">Get started</a> ·
  <a href="#documentation">Documentation</a> ·
  <a href="CONTRIBUTING.md">Contribute</a>
</p>
<p align="center">
  <a href="https://github.com/alfa-reza/Macodes/releases/latest"><img src="https://img.shields.io/github/v/release/alfa-reza/Macodes?color=10b981" alt="Latest release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="Apache 2.0 license" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84" alt="Android 8.0 and later" />
</p>

## A workspace that travels with you

Macodes brings the tools you use on remote machines to your phone. Connect to a
host, open a terminal, transfer a file, or continue a coding
agent conversation—all from one place. Your commands and development tools run
on machines you choose; no Macodes account or hosted relay is required.

### What you can do

- **Work in a real terminal.** Connect over SSH or Mosh, open multiple sessions,
  use your favorite terminal applications, and keep work alive with tmux.
- **Move and inspect files.** Browse, search, upload and download over SFTP;
  preview images and play supported videos.
- **Use coding agents your way.** Open a terminal-first Herdr workspace or use
  native Agent chat with supported CLIs including Codex, Claude Code, Gemini CLI,
  OpenCode and Pi. Agent accounts or provider credentials are separate.
- **Make the terminal yours.** Choose light or dark themes, terminal palettes,
  quick commands, English or Chinese, and phone-friendly input controls.

## Get started

1. Install the APK from [GitHub Releases](https://github.com/alfa-reza/Macodes/releases/latest)
   on Android 8.0 or later. Release assets and checksums are available on the
   release page when published.
2. Open **Hosts**, add your SSH address and credentials, and verify the host-key
   fingerprint on first connection.
3. Choose **Terminal**, **Files**, **Herdr**, or **Agent** from the
   host menu. Some features offer guided installation of their remote components.
4. For Mosh, select it in the host settings. The server needs `mosh-server` and a
   reachable UDP port. Use a persistent session such as `tmux new -A -s main`
   when you want programs to survive disconnection.

> **Moving from Termish?** Macodes has a different Android application ID and
> installs separately. Hosts, settings, trusted fingerprints and credentials in
> Termish's private storage are not automatically transferred. Keep the old app
> and its data until you have safely set up and verified your hosts in Macodes;
> uninstalling or clearing Termish removes its local data. There is currently no
> automatic migration between the apps.

## How it connects

Macodes connects directly to your host over SSH or Mosh. Saved credentials use
Android's platform-protected storage. SSH host-key verification helps you confirm
that you are connecting to the intended machine.

The optional **Agent Bridge** is uploaded over SFTP and runs under your remote
user account; its communication travels through SSH. GitHub Actions builds
currently bundle Linux Agent Bridge payloads; macOS host payloads are not
included in those APKs.

Remote screen is no longer included. Updating the app does not stop or uninstall
screen services previously installed on remote hosts; manage those on each host.

AI and speech providers receive the content needed for features you configure
and may charge separately. Agent conversations are stored on the remote host.
For details, read the [privacy policy](docs/appstore/privacy-policy.md) and
[security policy](SECURITY.md).

## Build from source

Install JDK 17, the Android SDK and Rust via rustup. Set `ANDROID_HOME` or the
SDK path in `local.properties`, then:

```bash
git clone https://github.com/alfa-reza/Macodes.git
cd Macodes
./gradlew :composeApp:assembleDebug
```

Debug builds do not need release signing keys. See [CONTRIBUTING.md](CONTRIBUTING.md)
for prerequisites, tests, formatting, and development conventions.

## Documentation

| Explore | Guide |
| --- | --- |
| Architecture | [Project architecture](docs/architecture.md) |
| Remote connections | [SSH](docs/ssh-transport.md) · [Mosh](docs/mosh.md) · [SFTP](docs/sftp.md) |
| Terminal | [Emulator](docs/terminal-emulator.md) · [Input pipeline](docs/input-pipeline.md) |
| Remote companion | [Agent Bridge](agentBridge/README.md) |
| Security | [Cryptography](composeApp/src/commonMain/kotlin/dev/macodes/crypto/README.md) · [Security policy](SECURITY.md) |
| Project | [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md) |

## Contributing and support

Macodes is independently developed. Issues, documentation improvements and
focused pull requests are welcome; see the [contribution guide](CONTRIBUTING.md)
before getting started.

[Report a bug or request a feature](https://github.com/alfa-reza/Macodes/issues)
· [Contact](mailto:research@alfareza.my.id)

## License and acknowledgements

Macodes is available under the [Apache License 2.0](LICENSE). It builds on work
from the MIT-licensed Termish project. Its original copyright notice and other
third-party notices are preserved in [NOTICE](NOTICE) and [LICENSES](LICENSES/).
