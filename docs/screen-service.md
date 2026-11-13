# Remote screen service

> **Summary:** macOS uses a Python session relay, native ScreenCaptureKit/CoreGraphics capture, VideoToolbox encoding, and session-scoped power assertions. Linux keeps the precompiled Rust X11/Wayland service. Both use authenticated, loopback-only H.264 video over SSH. Closed-lid recovery requires real-device validation and macOS capture consent.

## Phone installation and dependencies

The phone reuses SSH login, probes the system, architecture and graphical session, and uploads
in chunks over SFTP to a private temporary file. After the installer verifies SHA-256,
executability and deployment version, it installs or reuses FFmpeg, generates or reuses the token,
lets Rust validate the configuration and atomically enable the executable, then registers the
graphical session service and verifies the loopback port. Unsupported or unpackaged architectures
return an explicit error.
The FFmpeg configuration keeps the package manager's absolute-path entry and symlink, without
resolving to a specific version directory, so upgrading Homebrew dependencies does not leave the
old binary in use. If an existing configuration points to a stale Cellar version, either reinstall
the service to update the path, or change `ffmpeg` in the configuration to the current stable entry
and restart the service.
The SSH install command explicitly uses `/bin/sh`, so unmatched-glob rules in the account's login
shell (such as zsh) do not abort the installation; progress and error output are read together,
keeping the final success marker or failure reason.
On Linux, the old Python script and its caches are removed after the native service starts and
verifies successfully. On macOS, `screen-relay.py` is kept and updated; a failed installation
restores the original script, original launch configuration, original background program and
backend marker; configuration, token and logs are retained.

The macOS `dev.termish.screen` GUI LaunchAgent runs
`/usr/bin/python3 ~/Library/Application Support/termish/screen-relay.py`. From deployment version 63
onwards, the Helper's `--capture-native FPS WIDTH BITRATE` subprocess captures and encodes first;
when ScreenCaptureKit yields no first frame or is unavailable it tries a CoreGraphics display
stream, and VideoToolbox outputs Annex-B H.264. When the native subprocess is absent, the
AVFoundation fallback remains, with the device index identified from FFmpeg's video device list;
falling back to the camera or treating an audio index as the screen is forbidden.
Python uses the standard library to call CGEvent, with no PyObjC; the installer does not start a
menu bar service.
The native toolkit uploaded by the phone is still verified for SHA-256, signature and deployment
version; the Python script is deployed with the authenticated SSH installation body.
X11 uses Rust x11grab/XTEST, and text paste depends on xclip.
On Linux, NVENC, QSV and VAAPI are probed by default with a synthetic colour block; FFmpeg listing
an encoder does not mean the driver can initialise. Probing has an overall time limit and is
cancellable when the connection drops; when the driver or device is unavailable it falls back to
libx264. If the hardware encoder exits or produces no output for a long time during actual
streaming, it switches back to software encoding within the same TCP session and keeps the frame
sequence; a failure of software capture itself still ends the connection.
The system needs the matching graphics driver and device access permissions; the service does not
force-install or replace drivers.
Wayland uses the Rust D-Bus RemoteDesktop/ScreenCast Portal to obtain screen and input permission in
one authorised session, hands the authorised PipeWire FD to GStreamer, and feeds the video pipeline
into FFmpeg for encoding; no Xwayland root-window capture is needed.
The Portal response subscription is made before the request, so a fast authorisation response is
not lost; coordinates use the logical size of the authorised stream.
In the `CreateSession` response, `session_handle` is a string (`s`) per the Portal compatibility
convention, and is validated before being converted to the object path (`o`) that later methods
need; it must not be decoded directly as the object-path variant.
When a connection ends, its Portal session is closed; a reconnect waits for the old connection to
finish cleanup and then obtains a fresh authorisation, avoiding reuse of a defunct session. A
refusal from `OpenPipeWireRemote` also clears the cache. Adjusting the bitrate within a connection
keeps reusing the current authorisation.

Native screen capture on macOS needs the Helper's system screen-recording permission. The service
attempts an actual capture first, without an extra pre-check blocking capture; only after a failure
does it distinguish a missing permission (status 4) from a temporarily unavailable display device.
A missing permission does not lead to endless retries and repeated dialogs; the phone prompts the
user to allow the Helper in System Settings and then reconnect. System Python's control permission
and the native capture permission are managed separately.
Control uses the CGEvent event-sending permission of the system Python identity; without the
control permission the screen can still be watched, the event-sending permission is requested once
on the first actual control, and heartbeats do not trigger permission requests. After a missing
control permission, an explicit reconnect from the phone refreshes the same LaunchAgent, so an
existing permission takes effect in the new process. The program does not modify the TCC database
or system security settings.

The Wayland Portal authorisation flow is unchanged. The service still runs in the graphical login
session, so lock screen, sleep and logout restrictions apply.

## Build

`rust/src/service.rs` manages connections and encoders, `protocol.rs` handles THA1/THC1/THF1/THV2,
and `platform/` implements each desktop platform. `service.properties` is the single source of the
deployment version and default ports. `tools/service-build/` is the shared Rust build tooling, which
generates payload manifests, Kotlin install scripts and Compose resources.

The Mac build machine installs the Xcode SDK, rustup and four targets:

```bash
rustup target add aarch64-apple-darwin x86_64-apple-darwin \
  aarch64-unknown-linux-musl x86_64-unknown-linux-musl
sh scripts/service-build.sh build-screen --all
sh scripts/service-build.sh build-agent --all
make screen-service
```

The Darwin installer toolkit still supports `TERMISH_SCREEN_MACOS_SIGN_IDENTITY`, which can sign
with a fixed Developer ID certificate and verifies the whole app bundle; when unset, ad hoc signing
is used and the manifest keeps the signing information. Native capture also lives inside that
bundle, so official distribution should use a fixed Developer ID certificate, otherwise updating an
ad hoc bundle loses the screen-recording authorisation identity. Local development can be accepted
with an existing Apple Development identity, but that must not be claimed as a completed public
distribution signature. Certificate private keys are never committed to the repository.

The official tag flow imports the `Termish-X.Y.Z-remote-services.tar.gz` prebuilt by the signing
machine from the same-named Release draft, verifies the commit SHA, source fingerprint and per-file
digest inside the archive, and checks that the signing certificates of both Mac Helpers match
`.github/macos-signing-certificate.sha1`. The archive contains only distributable artifacts and
manifests, no private keys; CI does not re-sign or downgrade to ad hoc. The current identity in
this version is Apple Development; signature verification and Developer ID notarisation are
separate verification items.

After the signing machine completes all checks, the bump and the commit locally, it builds the
services and the APK/AAB with the same certificate:

```bash
TERMISH_SCREEN_MACOS_SIGN_IDENTITY='certificate identity of the signing machine' sh scripts/service-build.sh build-screen --all
sh scripts/service-build.sh build-agent --all
python3 scripts/remote-payloads.py verify-macos
python3 scripts/remote-payloads.py pack /tmp/Termish-X.Y.Z-remote-services.tar.gz "$(git rev-parse HEAD)"
```

Immediately after pushing the tag, create the same-named Release draft and upload that archive; CI
waits at most two minutes and stops publication if the package is missing, the signature does not
match or the source is inconsistent. The publish job keeps that archive, uploads the signed Android
package and checksums, and then makes the Release public.
When updating the signing identity, rotate the certificate fingerprint explicitly and re-verify
upgrade authorisation; never pass the certificate private key to GitHub.

Linux needs only the matching musl target and uses Rust's bundled linker, with no cross C compiler
or X11 development libraries. By default it builds the targets that are installed and usable on the
local machine; imported artifacts with the same source fingerprint are kept. CI builds all four
targets on the Mac first, then passes them to the Android build as an artifact. Source changes
during the build, or manifest and binary SHA mismatches, all fail the build.

`screenServiceBuild` depends on `screenServiceRustBuild` and packages before Kotlin compilation and
resource copying. Manifests live in `screenService/build/binaries/`, generated Kotlin in
`composeApp/build/generated/screenService/kotlin/`, and file resources in
`composeResources/files/termish-screen/`. Generated artifacts are not committed.

The deployment version is changed via `RELAY_VERSION` in `service.properties`, independent of the
app version.

## Configuration and service paths

The install directory is `~/Library/Application Support/termish/`; configuration lives outside the
signed bundle, and upgrades keep the token and custom paths:

```text
Termish Helper.app/        # macOS background app (still screen-service on Linux)
  Contents/Info.plist
  Contents/MacOS/Termish Helper
  Contents/Resources/AppIcon.icns
screen-service.NOTICE      # full third-party copyright notices
screen-service.backend     # macOS: python-macos; Linux: rust
screen-service.json        # configuration, 0600
```

The Darwin payload filename keeps `termish-screen-Darwin-*`, and its content is a gzip tar of the
signed app bundle; the Linux payload is still an ELF. Installation verifies the uploaded SHA first,
then unpacks and verifies the signature and version; the old service is stopped only after
verification passes. A failed start restores the old app bundle and LaunchAgent; on success the old
bare binary is removed. JSON and the LaunchAgent XML are generated by Rust, and Chinese characters,
quotes and shell/XML characters are not expanded; an invalid configuration never overwrites the
original file.

```bash
"$HOME/Library/Application Support/termish/Termish Helper.app/Contents/MacOS/Termish Helper" --version
# the Linux or standalone Rust debug binary still supports these flags:
screen-service --config /path/to/screen-service.json --check-config
screen-service --config /path/to/screen-service.json
screen-service --display-state
# Linux: same-account desktop-session D-Bus management, not a network management port
screen-service --manage
screen-service --control status
screen-service --control pause     # resume / disconnect / restart / quit
screen-service --check-encoder     # encode only a test colour block, without capturing the desktop or binding a port
```

`--version`/`--help` need no FFmpeg or token; `--check-config` does not bind a port, start an
encoder or request authorisation, and does not print the token.

| Config | Default and purpose |
| --- | --- |
| `port` | 17321 probing, 17323 TCP video, IPv4 loopback only |
| `ffmpeg` | resolved to an absolute path at install time |
| `capture_source` | `desktop`; `test_pattern` is available for diagnostics and only generates a test pattern, without capturing the desktop or injecting input |
| `menu_bar` | `true` by default for Linux desktop capture; `false` by default for `test_pattern`. The macOS Python session service does not start a menu bar; the native menu can be diagnosed independently |
| `encoder` | `auto` by default on Linux; `software`, `nvenc`, `qsv` and `vaapi` are selectable, and an unavailable requested hardware encoder still falls back to software; macOS stays on VideoToolbox |
| `token_file` | `~/.termish-screen.token`, 256-bit token |
| `log_file` | macOS `~/Library/Logs/termish-screen.err`, Linux `~/.termish-screen.err` |
| `encoder_pid_file` | `~/.termish-screen-ffmpeg.pid`, for diagnostics and cleaning up its own child processes |
| `stream_config_file` | `~/.termish-screen.conf`, phone FPS/quality settings |

Phone deployments keep the default ports; standalone debugging can override them with
`--port`/`--ffmpeg`. The former UDP compatibility endpoint has been removed, and the phone uses the
TCP protocol.

## Lifecycle and diagnostics

macOS uses the `dev.termish.screen` LaunchAgent and Linux uses a same-named systemd user service,
falling back to desktop autostart when unavailable. The Linux launcher re-probes the graphical
session and Xauthority.

Linux implements StatusNotifierItem and D-Bus Menu natively in Rust, reusing the service's same
lifecycle state; KDE and GNOME with a matching tray host enabled can show the Termish icon. It
re-registers automatically when the host starts late or restarts.
When GNOME has no tray support, open **Termish Helper** / **Termish 服务管理** from the app
list, which offers the same management actions.
The installer deploys the branded SVG and a desktop entry; the management window depends on zenity,
and when it is missing the existing sudo authorisation flow is shared with the other system
dependencies.
Service status and menu actions are exposed only through the current account's session D-Bus (the
service name includes the port), adding no public or loopback management port.
Without a session bus the remote screen service still runs; the management entry point is
unavailable and the reason is logged.

The Linux menu shows X11 desktop availability or the Wayland Portal authorisation state; viewing
the permission state does not trigger an authorisation window.
The permission entry point provides authorisation guidance for the current platform; on Wayland the
screen is still selected through a system window while connecting, and control is allowed.
logind reads `LockedHint` only for the current user's active, local graphical sessions, excluding
SSH and the login screen.
X11 reads the DPMS sleep state and tries to wake on connection; Wayland does not use Xwayland DPMS
as a stand-in for the physical screen state.
The phone reads the lock-screen/sleep indicator on both platforms. This is a status indicator and
does not mean Wayland permits capture or input while locked; it does not modify security policy and
does not guarantee that every desktop supports unlocking, so real GNOME/KDE lock screens still need
per-environment acceptance.

macOS restores the original background service and no longer shows the Helper menu bar
automatically. The phone's disconnect, reconnect and update entry points remain available; quitting
a connection releases the current encoder and mouse buttons, then allows a new connection.

"Restart service" releases input, closes the connection and cleans up the encoder first, then the
service supervisor starts a new process.
The Linux systemd unit sets `TERMISH_SYSTEMD_SERVICE=1` and `Restart=on-failure`; the restart action
exits with a failure code so the supervisor starts a replacement process, while a normal exit does
not restart immediately. Desktop autostart or standalone runs start a replacement process with the
original arguments.
The macOS LaunchAgent sets `RunAtLoad=true` and `KeepAlive={SuccessfulExit:false}`; an abnormal exit
restarts, a normal exit does not start immediately.
Quitting does not remove the autostart configuration, so it still starts automatically at the next
login; reinstalling from the phone or explicitly starting the service also starts it again.

No encoder starts and no status or video is returned before authentication; when a healthy client
holds the service, `THS1 + 3` is returned.
`THS1 + 4` means the actual capture failed and the macOS screen-recording permission is missing;
the phone stops auto-reconnecting and prompts for authorisation.
The first packet of a connection is still the unframed `THS1 + status`; later permission updates
are `[4B big-endian length=5][THS1 + status]`, sent by the sole video writer thread between
complete video packets, and the phone updates the control-permission prompt after parsing them.
Failure to renew the lease for 6 seconds closes the connection and releases the encoder; a recovery
triggered by feedback keeps the TCP connection and frame sequence and sends a new key frame.
Disconnect or quit sends the remaining mouse-release events and cleans up FFmpeg/GStreamer; Linux
child processes are set to receive a parent-death signal.
The Wayland Portal session is closed when the service exits.

On macOS, `PreventUserIdleDisplaySleep`, `PreventUserIdleSystemSleep` and the user-activity
assertion are created only after THA1 authentication; disconnect, heartbeat timeout and service
exit all release them. The operation needs no sudo, does not modify persistent power settings and
does not unlock the system.
After a display reconfiguration or the native capture exiting, the service recreates the subprocess
and keeps the TCP connection, frame sequence and power lease; a static screen repeats the most
recent frame so that the transport and render watchdogs do not treat it as a stalled stream. A
subprocess with no output times out and is reaped.
Closing the lid does not guarantee that the system provides a display stream; a synthetic pattern
or a successful wake API return cannot substitute for real-capture acceptance.

Logs record the version, port, refusals, encoder start/recovery and shutdown reasons, and never the
token or input content.
Check `--check-config`, the service process and the first frame first, then the phone's decoding,
feedback and reconnection logs. Migrating the service does not mean the earlier intermittent
disconnects are all resolved.

## Verification

```bash
./gradlew screenServiceTest
make test-integration
# Linux: isolated D-Bus mock (no real desktop or authorisation needed)
cargo test --locked --manifest-path screenService/rust/Cargo.toml \
  portal_scopes_video_and_input_and_passes_owned_fd -- --ignored --test-threads=1
# Linux: real capture, Tab, Unicode clipboard and disconnect release on a standalone virtual desktop
xvfb-run -a -s '-screen 0 640x360x24' cargo test --locked \
  --manifest-path screenService/rust/Cargo.toml real_x11 -- --ignored --test-threads=1
# private session bus: tray protocol, host restart, pause/disconnect/quit and systemd exit codes
cargo test --locked --manifest-path screenService/rust/Cargo.toml \
  --test linux_desktop -- --ignored --test-threads=1 --skip management_window
# English and Chinese management window with no tray host, on a virtual desktop
xvfb-run -a -s '-screen 0 960x720x24' cargo test --locked \
  --manifest-path screenService/rust/Cargo.toml management_window -- --ignored --test-threads=1
# initialisation succeeds but real hardware encoding fails: the connection stays up, and the frame sequence keeps increasing after the fallback
xvfb-run -a -s '-screen 0 640x360x24' cargo test --locked \
  --manifest-path screenService/rust/Cargo.toml hardware_failure -- --ignored --test-threads=1
```

Tests need Rust, Python 3 and FFmpeg; the Linux scenarios additionally need dbus-daemon, Xvfb,
xauth, xev, xdotool, xclip, zenity and desktop-file-utils; Chinese window screenshots need CJK
fonts.
The macOS Python regression runs with `python3 -m unittest discover -s screenService/macos/tests -v`,
verifying authentication, frame format, heartbeat, occupancy protection, feedback-triggered restart
and encoder cleanup. Synthetic tests use a separate configuration, token and random port. Phone
tests verify SFTP installation and TCP video parsing. A real installation affects the account's
existing service, so it is SKIP by default and runs only when
`TERMISH_TEST_INSTALL_SCREEN=1` is set explicitly. Real system authorisation, lock screen/sleep and
Wayland desktop behaviour need manual verification.
End-to-end cases and reports are stored locally in `.aiadb/` and are not distributed with the
repository.
On a Mac, `scripts/service-test.sh screenService` also verifies native VideoToolbox pattern encoding,
Annex-B parameter sets and FFmpeg decoding; this item does not access the desktop and needs no
screen-recording permission. Lid close after authorisation, lock screen, display reconfiguration and
power-assertion release after disconnect still need real verification.
