# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/),
versioning follows [Semantic Versioning](https://semver.org/lang/zh-CN/).

## [Unreleased]

## [1.8.14] - 2026-10-19

### Added

- **File manager SVG preview**: Tapping an SVG on Android / iOS opens the image directly, with transparency, viewBox sizing, and the existing fit-to-screen / original-size toggle
- **Bounded SVG rendering**: Preview files are capped at 8 MiB and rasterized to at most 2048 px on the longest side; corrupted files show a preview failure and remain downloadable; uses a static renderer that does not execute SVG scripts

### Known limitations

- SVG previews are static: animations and interactivity are not executed; iOS default font parsing is incomplete, so some `<text>` text may be missing — use an SVG with text converted to paths or view after downloading

## [1.8.13] - 2026-10-18

### Added

- **File manager online video playback**: Tapping a video opens the native Android / iOS player, reading on demand over a dedicated SFTP connection, with play, pause, and seek — no need to download the whole file first
- **Playback session management**: Playback pauses when the app goes to the background; leaving the preview releases the playback connection, file tabs keep their own playback position, and video reads do not occupy the directory-browsing channel
- **Playback failure notice**: A bilingual message appears when the encoding is unsupported on the device or the connection fails; the file can still be downloaded via the more menu

### Verification notes

- Both Android / iOS emulators actually played H.264 + AAC MP4 files with Chinese and space-containing filenames; iOS pause, 10-second seek, background pause, and corrupted-file notice all passed; real SFTP >4 GB range reads and resource-release regressions passed
- Native progress-bar dragging still awaits acceptance on real devices, and Android background and cross-tab manual scenarios have not been fully executed; supported containers and encodings depend on the device's native decoding capability

## [1.8.12] - 2026-10-15

### Added

- **macOS native capture and restore**: Prefers ScreenCaptureKit, falling back to CoreGraphics when necessary, and encodes with VideoToolbox; the capture subprocess is rebuilt after display configuration changes, preserving the authentication channel and frame sequence numbers
- **Session power management**: After authentication it attempts to wake the display, keeps the system and display active while the video connection is up, and releases the power claim on disconnect, heartbeat timeout, or exit; persistent power settings are not modified

### Fixed

- **Video channel disconnect**: Removed AVFoundation's dependency of fixed screen indices on camera count; when native capture lacks the system screen-recording permission, a clear authorization prompt is shown and endless reconnecting stops
- **Mac Helper fixed-certificate signing**: This version signs the arm64/x86_64 Helper with an Apple Development certificate, and CI verifies the fixed certificate and source fingerprint before packaging as-is; Android continues using the original release signature. The private key is not uploaded to CI; this version is not yet a Developer ID notarized distribution

### Verification notes

- Native VideoToolbox pattern encoding, authenticated TCP packetization and FFmpeg decoding, permission denial, power cleanup, subprocess restore, and install rollback regressions passed; real lid-closed/lock-screen desktop capture could not be accepted because screen-recording access was denied on the test machine
- Screen service deployment version bumped to 63. After upgrading the phone, update the Mac service, allow Termish Helper screen recording in System Settings, then reconnect; at this stage automated tests cannot claim parity with ToDesk's full lid-closed behavior

## [1.8.11] - 2026-10-15

### Fixed

- **macOS remote video restore to the old implementation**: Restored the startup chain of the system Python GUI LaunchAgent + FFmpeg direct screen capture, and removed the new Helper app bundle's screen-recording precheck blocking the connection; the update script preserves interpreter identity and continues using the system's existing authorization
- **Service migration and reconnection**: Keeps authenticated SSH video, control, heartbeat, and quality feedback; on install failure it restores the old script, startup configuration, and background process, and reclaims its own encoder on disconnect and abnormal exit

### Verification notes

- Python service regression, real SSH/SFTP video parsing, macOS install migration, and failure rollback passed; the GUI system Python's screen-recording precheck on the current test machine is unauthorized, so real desktop capture has not completed acceptance
- Screen service deployment version bumped to 62; after upgrading the phone, updating the Mac service once from the remote video entry switches back to the old startup chain. First-time system authorization is still managed by macOS, and the Linux screen-capture implementation is unchanged

## [1.8.10] - 2026-10-14

### Fixed

- **Arrow keys typing letters**: Fixed the escape sequences for arrow keys and HOME/END in application cursor mode to use `ESC O` (SS3), avoiding the wrong `ESC [ O` sequence that left A/B/C/D characters behind
- **Stale cursor-mode updates**: The toolbar now reads the current session mode each time it sends a key, avoiding continued use of the old mode when the UI has not recomposed; session switches and healthy connections returning to the foreground keep their own state

### Verification notes

- Unit regression tests passed for both cursor modes, HOME/END, other function keys, per-fragment mode updates, session state isolation, and background restore; Android emulator install and launch, lint, signed package verification, and iOS shared-code builds passed
- Emulator real-SSH tab switching / foreground-background end-to-end checks have not completed acceptance; intermittent issues still need re-verification on the original device and remote environment

## [1.8.9] - 2026-10-13

### Fixed

- **macOS remote video connection failure**: The installation now preserves FFmpeg's stable entry point and symlinks, avoiding calls to the old version directory after a Homebrew dependency upgrade that made the encoder exit immediately on startup
- **Wayland unable to reconnect after disconnect**: The Portal authorization session is closed when a connection ends, and stale caches are cleared; a new connection waits for the old one to finish cleaning up, preventing the old connection from closing the new session

### Verification notes

- The macOS native service streamed continuously for 40 seconds, 2358 frames without disconnects; the install-path upgrade regression passed. Wayland session cleanup and stale rebuild have isolated regression tests; long real GNOME/KDE streaming still awaits real-device acceptance
- Screen service updated to deployment version 61; takes effect after updating the controlled-side service with the new app version

## [1.8.8] - 2026-10-06

### Fixed

- **Screen service install falsely reported as failed**: The install script is now executed explicitly with `/bin/sh`, fixing the case where a macOS account using zsh had no old cache, so the install aborted after the service started successfully and never reported success
- **Install error logs**: Progress and error output are merged and read to the end, so the final error log is no longer lost when the SSH channel closes

### Verification notes

- Regression tests passed for zsh with no cache, partial cache, repeated cleanup, special-character paths, stdin and error output, plus real SSH cleanup and Android/iOS debug builds and iOS emulator launch; the full upgrade flow on real devices has not been tested
- This change modifies the phone-side install flow; the screen service remains deployment version 60. Successfully installed services can reconnect after turning off remote video

## [1.8.7] - 2026-10-05

### Fixed

- **Wayland authorization window**: The session handle is now parsed as the string type required by the Portal interface, fixing the error where the connection reported `incorrect type` and disconnected before requesting the authorization window; the regression test now uses the real interface return type

### Changed

- **Build cost**: CI is triggered only by `v*` tags or manual runs and handles building, packaging, and publishing; tests and lint run locally, with the process documented in `AGENTS.md`, avoiding duplicate builds of `main` and tags and duplicate cloud checks

### Verification notes

- Linux unit tests, Portal regression with the real return type, four-architecture builds, and Android debug checks passed; the real authorization window creation was confirmed in the 66 KDE Wayland session, while video and input after approval still await real-device acceptance
- Screen service updated to deployment version 60; the controlled-side service must be updated with the new app version so an old app does not reinstall an old service

## [1.8.6] - 2026-10-05

### Added

- **Linux desktop management**: Added a system tray with the Termish icon and an app-list entry, supporting viewing connection status, pausing/resuming access, disconnecting, restarting/exiting the service, viewing logs, and opening the service directory; bilingual display, with a management window when no tray host is available
- **Linux hardware encoding**: Automatically detects the actual availability of NVENC, QSV, and VAAPI, falling back to software encoding when unavailable or failing at runtime; adds encoder diagnostics and selection configuration

### Fixed

- **Linux service lifecycle**: systemd restart and normal exit now use different exit statuses; fixed the install aborting when a user D-Bus exists but no systemd user manager, falling back to login autostart
- **Linux display status**: Completed local graphics-session lock status and X11 display power detection, so the phone side can read the controlled-side status

### Verification notes

- X11 video and input, real VAAPI encoding, systemd restart/exit, full install and LXQt tray operations, plus four-architecture builds, Android/iOS debug, and SSH/SFTP integration tests passed
- Screen service updated to deployment version 59, effective after installing/updating the service; capture and control after Wayland lock screen are still limited by the desktop environment, and real GNOME/KDE lock-screen, sleep, and unlock acceptance has not completed

## [1.8.5] - 2026-10-05

### Fixed

- **Old screen service leftovers**: After the native service installs and startup is verified, the old Python scripts and their bytecode caches are cleaned, while config, token, logs, and other files are kept
- **Old Agent background processes**: After the native Bridge starts successfully, it verifies the account, full command line, and interpreter, stops the old Python companion, and cleans old install files; if the old process has not exited, files are kept and a failure is reported, preserving session data

### Verification notes

- Tests passed for cleanup boundaries, process verification, exit failure, paths with spaces, repeated cleanup, and cache symlinks, plus Android debug and SSH/SFTP integration tests; real Mac backup and cleanup of old leftovers was performed, and the current Rust service keeps running normally
- This change modifies the phone-side install flow; the screen service remains version 58 and the Agent Bridge remains 0.9.0; cleanup runs when installing/reinstalling the service

## [1.8.4] - 2026-10-05

### Added

- **macOS menu bar**: Termish Helper shows a menu bar icon providing connection status, disconnecting the current connection, pausing/resuming access, screen-recording and control permission settings, restarting the service, viewing logs, and opening the service directory; displays Chinese or English per the system language

### Improved

- **Service exit and restart**: Menu actions reuse the existing session cleanup, releasing input and reclaiming the encoder; normal exit is no longer immediately relaunched by the LaunchAgent, abnormal exit still auto-restarts, and login autostart remains
- **Menu and connection in parallel**: The menu runs on the macOS main thread while connection management runs on a background thread, continuing to use the existing FFmpeg capture and encoding scheme

### Verification notes

- macOS native menu actions, pause/resume/disconnect/restart/exit with real synthetic video connections, isolated LaunchAgent lifecycle, four target builds, and SSH/SFTP integration tests passed; manual visual click acceptance of the windowless menu is incomplete
- Screen service updated to deployment version 58; pausing only applies to the current process and access resumes after restart. The macOS service still uses ad-hoc signing, so after upgrading, if permissions lapse, re-authorize Termish Helper and restart the service

## [1.8.3] - 2026-10-04

### Fixed

- **macOS reconnect after authorization**: After the screen-recording or accessibility permission missing notice, tapping "Reconnect" first restarts the screen service, refreshing the denial result cached before authorization, then restores video and control
- **Accessibility notice**: Added a "Reconnect" button, and the bilingual guidance now says to reconnect after authorizing; no longer defaults to asking users to delete the permission entry
- **Reconnect session cleanup**: A proactive reconnect first closes the old session, preventing the screen service restart from triggering that session's auto-reconnect

### Verification notes

- Confirmed on a real Mac that with both permissions granted the old process still reported denial, and after restart screen recording and control were restored; the user confirmed phone operation recovered
- This change modifies the phone-side reconnect flow; the background service remains version 57; Android/iOS builds, unit tests, and SSH integration tests passed. Full first-time authorization and permission-revocation flows on a new phone still need real-device verification

## [1.8.2] - 2026-10-04

### Fixed

- **macOS screen-recording permission**: Screen-recording authorization is pre-checked independently; without permission, FFmpeg is not started or restarted; the same service process requests screen-recording authorization only once, and the phone stops auto-reconnecting after receiving an explicit denial, avoiding a reconnect loop triggering popups
- **Permission notices**: Screen recording and accessibility are prompted separately, and when authorization lapses the guidance is to re-add the current background app, no longer conflating screen-recording issues with control permission issues

### Improved

- **Background app name and icon**: The macOS service is packaged as `Termish Helper.app` with the Termish logo, unifying app and process names while running in the background; the phone can still auto-install via SSH/SFTP
- **App bundle install**: Enabled after verifying the uploaded SHA-256, app signature, and deployment version; config and token are kept outside the bundle; on startup failure, the old app and service registration are restored
- **Release-signed builds**: Added a fixed-certificate signing entry, signing the complete app bundle with a stable app identity, then generating the install verification digest

### Verification notes

- Screen-recording gating, video protocol, SSH upload, bundle signing, install migration, and failure recovery tests, plus Android/iOS local builds, have passed; real first-time authorization, revocation, and permission popup display still need real-device confirmation
- This release is not yet wired to a Developer ID release certificate; the macOS service still uses ad-hoc signing, which cannot guarantee authorization survives upgrades. If permissions lapse after migration, re-authorize `Termish Helper.app` in System Settings

## [1.8.1] - 2026-10-04

### Fixed

- **macOS remote control permission**: Switched to event-send permission detection and handles main-thread system events, avoiding a persistent service continuing to use the old accessibility check after authorization; compatible with permission interfaces of older macOS versions
- **Duplicate authorization prompts**: The same service process requests control permission at most once; continuous operations or reconnects no longer repeatedly trigger the authorization popup
- **Phone permission status**: The controlled side checks control permission changes every second and updates the app notice over the video channel, supporting receiving granted and revoked status within the same connection

### Verification notes

- Automated tests, protocol unpacking tests, and Android/iOS local builds have passed; new-version first authorization, revocation, and actual phone mouse/keyboard operation still need real-device verification
- Upgrading updates the controlled-side program; macOS may require re-authorizing once for the new program identity. If an old process is stuck in the authorized state, restart the screen service and reconnect

## [1.8.0] - 2026-10-04

### Changed

- **Rust remote service**: The remote video service and Agent Bridge became native executables with built-in ARM64 and x86_64 binaries for macOS / Linux, continuing to support auto-install and update from the phone over SSH, with no manual Python deployment on the computer
- **Agent session storage**: New sessions use private JSON files; old Python Bridge session records are not migrated and the new version does not show them, while the Agent tools' own native history can still be imported
- **Build and release**: Removed Python sources, `.pyz` assets, and Python build dependencies; the remote service is packaged by a Rust tool, and R2 releases switched to Node.js and curl

### Fixed

- **Remote video lifecycle**: Completed connection authentication, session occupancy, disconnect reclamation, key release, and encoder restart handling; on Wayland authorization cancellation, requests and sessions are closed promptly; desktop screen recording and control still require system authorization
- **Agent connection and install**: Preserved background tasks, disconnect event replay, and approval reconnection; limited install logs and wait time, and improved process cleanup, attachment path validation, and sensitive-information redaction

## [1.7.3] - 2026-09-06

### Improved

- **Project introduction**: Rewrote the English and Chinese READMEs, highlighting the native Agent chat, SSH / Mosh, and SFTP, with download entry points and a three-step quick start up front
- **Core screenshots**: Curated three demo screenshot groups — Agent chat, remote terminal, and file manager — reducing repetitive display
- **Developer docs**: Consolidated full build and test steps into the contributing guide, clarifying Android / iOS acquisition, background connection limits, and external service configuration requirements

## [1.7.2] - 2026-09-06

### Fixed

- **Cross-session sending**: Send preparation, attachment progress, confirmation responses, and errors are bound to the originating session, so switching chats no longer writes messages or errors into the new page
- **Stop task**: The stop operation cancels that session's send preparation or remote task; after a first-send cancellation, the empty session is cleaned up, and attachments that fail before sending are reclaimed per that round's paths
- **Background task status**: Running and approval statuses of other sessions are tracked continuously, with background events re-sent and deduplicated, avoiding prematurely reclaiming listening connections after leaving the chat page
- **Agent install status**: Each tool tracks install progress independently, preventing duplicate installs, with failure retry and state recovery after reconnects; the bundled Bridge was updated to 0.8.1

### Changed

- **Open-source docs**: Improved the MIT license statement, contributing guide, code of conduct, and Issue/PR templates, with bilingual documentation and the website providing source and contribution entry points
- **Release configuration**: Android signing and R2 releases use repository-level Actions configuration, supporting bulk import of independent secrets from a local `.env`
- **Repository cleanup**: Removed the in-project `.agents` helper directory, internal evaluations, and one-off check reports

## [1.7.1] - 2026-09-05

### Removed

- **Desktop clients**: Removed the macOS / Linux / Windows apps and installers, keeping only Android and iOS; the original JVM unit tests and SSH/SFTP integration tests moved into Android local tests, and the remote video feature is retained

### Fixed

- **Terminal top layout**: Removed duplicate status-bar safe-area padding under the header, tightened the page header from 56dp to 48dp, and kept the necessary safe distance for the iOS Dynamic Island
- **Mobile build compatibility**: SSH command output uses a character-set API compatible with Android 8+; fixed the iOS video bridge configuration, platform extension imports, and read-only property implementation, plus nullable handling in the notification permission callback; the Markdown rendering library was aligned with Compose 1.8, fixing missing symbols in the final iOS app link

## [1.7.0] - 2026-08-25

### Added

- **Native Agent chat**: Enter a phone-native workspace for Codex, Claude Code, Gemini CLI, OpenCode, and Pi directly from the host card; the bundled pure-stdlib Python Bridge is auto-deployed over SFTP and streams conversation, thinking, and tool events through a private Unix socket inside SSH, with history session restore, cancellation, and guided Agent CLI installation
- **Agent artifact cards**: Chats display files generated in the turn — Markdown, PPT, Excel, Word, PDF, images, and more — as cards that open their containing directory
- **Agent native history import**: Can discover and import local sessions from remote Codex, Claude Code, Gemini CLI, OpenCode, and Pi, reusing the native session ID to continue context without rewriting the Agents' own history files
- **Agent provider configuration**: The settings page can add DeepSeek (API Key stored only in platform secure storage); each Agent can independently choose built-in login or a specified provider; the first batch supports Claude Code, OpenCode, and Pi
- **Agent input efficiency**: The input area supports phone attachments and remote directory selection, plus typing `/` to invoke the current Agent's built-in command completion; long attachment names have a limited preview width with ellipsis
- **Linux screen remote control**: Supports both X11/XTEST and native Wayland Portal/PipeWire paths, auto-detecting the desktop environment and allowing switching; completed dynamic desktop size, status reporting, and mouse injection
- **iOS remote video hardware decoding**: Integrated VideoToolbox to decode H.264, lowering mobile playback overhead

### Improved

- **Remote video adaptive transport**: Bitrate and frame rate adjust dynamically based on TCP backpressure, sending keepalive frames for static screens, making connections more stable on weak networks and idle desktops
- **Quick entry from host card**: Can enter Agent and remote video directly from the host card, reusing existing SSH sessions to reduce duplicate connections

### Fixed

- **Agent failures no longer swallow messages or hang loading forever**: On send failure the draft and attachments are kept, and login/provider errors display directly; when the Bridge adapter exits or errors, the turn is guaranteed to end, and no more ghost reconnects after disconnect
- **Preserve the Agents' own approval policy**: No longer forcibly overrides Codex, Claude Code, and Gemini CLI permission/approval parameters; phone interactive approval is not yet bridged, so when the headless protocol cannot report, it fails safely with an error
- **Virtual mouse left/right edge push symmetry**: Dragging to the left edge also continuously pans and enlarges the video, the mouse hotspot is clamped within the visible video area, and it no longer slides off screen
- **Virtual mouse dragging and coordinate conversion**: Fixed continuous dragging with the left button held, click offset after resolution or orientation changes, and inconsistent mapping between virtual mouse and finger modes
- **Remote video stream-break recovery**: Improved stream reconnect, session switching, and static-desktop timeout handling, avoiding misjudging disconnect when the video freezes
- **Agent process cards**: Restored the original card style; thinking and tool processes default to collapsed both while running and after completion, with reduced collapsed height
- **Agent message ordering and concurrency protection**: Realtime events are buffered during send-request handoff, avoiding assistant deltas arriving before user messages or final-state animation state being written back; the Bridge serializes startup windows per session, blocking multiple clients from overwriting the same turn concurrently

## [1.6.4] - 2026-08-24

### Fixed

- **PiP↔fullscreen black screen**: Surface changes always release the decoder and rebuild immediately from client-cached SPS/PPS (not relying on the remote repeating parameter sets in keyframes), and after rebuilding only IDR frames start playback; during expand/collapse animations no real video surface is attached (black placeholder, surface built only after the animation ends) — SurfaceView's surface does not follow graphicsLayer scaling, so the video no longer sticks at PiP size as a bright dot or goes black
- **Virtual mouse stuck at right edge / video shrinks**: Overshoot past the right edge accumulates persistently as an overall leftward video shift (push cap = the translation needed to push the video's right edge to the panel's left edge, growing automatically when zoomed — with the mouse near the right edge the video pans continuously and never slides off screen); the video container no longer has ancestor clipping (on some devices a SurfaceView clipped by an ancestor gets scaled instead of clipped)
- **Virtual mouse arrow can't reach the video edge**: The arrow tip lands exactly on the hotspot (the click position), with the tail beyond the canvas hidden by clipping — previously the whole graphic was inset, so the rightmost column couldn't be clicked

## [1.6.3] - 2026-08-23

### Fixed

- **Settings diagnostics toggle refreshes immediately**: The toggle takes effect on click (previously the TermLog property was not Compose state, so the visual didn't change until leaving and re-entering the page)
- **Git panel herdr workdir logging**: Added logs for herdr snapshot empty-session/parse-failure paths for easier debugging

## [1.6.2] - 2026-08-23

### Added

- **Fullscreen frame-rate/quality switching**: A tier menu in the top-right (30/60/120 fps × smooth/SD/HD); the relay supports dynamic parameters (each connection reads ~/.termish-screen.conf), and changes take effect automatically by rebuilding the session
- **Relay version negotiation** (RELAY_VERSION): The client detects the remote relay version and guides a one-tap upgrade on old versions — client and remote script versions now match (no more new client with an old relay failing to run new features)
- **Auto-reconnect on stream break**: Non-intentional stream breaks automatically rebuild the session after 3 seconds (keeping tier settings), with no manual reconnect needed

### Fixed

- Missed tier-switch callback (fullscreen overlay wasn't wired to onStreamConfigChange) — the fps menu didn't update in the top-right after tapping and the remote didn't take effect; quality only appeared to work due to local state updates
- Race between tier switching and auto-reconnect on stream break: the old uiState tier wasn't synced, and auto-reconnect overwrote the new tier back to 30
- PiP two-finger zoom switched to centroid anchoring (expanding toward the fingers) + boundary-aware clamping (when one side hits its limit the other keeps expanding, without leaving the screen)
- coerceIn(min>max) boundary crash (sliding could crash when the PiP was enlarged near the canvas size)
- Toolbar keys restored to square (48dp cap, centered on wide screens, no more stretched flat blocks)
- Docs: ktlint workflow conventions + updated repository public-status wording

## [1.6.1] - 2026-08-23

### Fixed

- **Linux desktop streaming (x11grab) full chain**: Install script pkill suicide (a command line containing its own text matched everything and killed itself) replaced with PID-file cleanup; DISPLAY is probed from the Xwayland process arguments (Wayland sessions have non-0 display numbers); X detection compatible with any X socket (Xwayland); relay error-log path made platform-aware (Linux has no ~/Library/Logs, and the open() exception stopped ffmpeg from launching — port listening but 0 bytes); passwordless sudo auto-apt-installs ffmpeg when missing
- Voice input: volume is written directly by the recording thread, removing main-thread scheduling delay (pauses <2s were misjudged as silence and auto-sent); silence auto-send threshold 2s→3s; normal sends no longer toast (only error/timeout/mistouch notices); connection-failure notice sanitized (no more URL/port exposure), timeout 10s→5s
- Fullscreen header back button aligned with the terminal page tab bar (status-bar height padding, no longer flush to the top)
- Mosh bootstrap failures always enter the install card (showing the specific reason), no longer silently degrading with no install entry
- herdr detection skips snap versions (sandbox restrictions are incompatible with the workbench)
- SFTP host picker shows hostnames (IP merged into the detail row)

## [1.6.0] - 2026-08-22

### Added

- Remote video PiP supports **two-finger pinch/spread zoom** (single-finger drag to move, bottom-right handle to resize, tap to fullscreen — all handled uniformly by the gesture layer)
- Fullscreen is now **true fullscreen**: overlays the tab bar and status bar areas, with Android immersive status bar hiding; top elements avoid the status bar (double insurance)
- Fullscreen back button changed to standard player style (plain arrow, consistent with the app-wide `KeyboardArrowLeft`), removing the "Collapse" text
- **Screen-off/lock-screen handling**: The relay watchdog senses screen power state (`CGDisplayIsAsleep`) and doesn't kill ffmpeg or drop the connection while the screen is off, with video auto-resuming on wake; the client shows a clear notice ("Mac screen is off, resumes automatically on wake" / "Locked, showing the lock screen") instead of a vague "can't connect"

### Fixed

- Landscape terminal keyboard toolbar misalignment: `weight + aspectRatio` produced over-tall keys and truncated text on wide screens (aspectRatio ignores height constraints); changed to a fixed 48dp key height
- Tab back history: switching between terminal tabs no longer pushes onto the stack (back goes straight to the home page); added the back chain for SFTP entered from the terminal page
- The SFTP host picker close button didn't follow the theme in a Dialog (LocalContentColor defaulted to platform black); `onSurface` is now set explicitly

### Engineering

- Adopted **ktlint** (mavenCentral CLI + JavaExec tasks, no plugin portal dependency): `make lint-kt` / `./gradlew ktlintCheck`; added to the CI checks stage; formatted 175 existing files (import ordering/wrapping style etc.), with Compose function names and protocol-layer constants exempted by rule, and 3 files renamed to match class names

## [1.5.1] - 2026-08-22

### Fixed

- Installed streaming service still prompted to install: SSH non-interactive sessions have restricted PATH, so `command -v ffmpeg` missed brew-installed ffmpeg → false missing report; search paths extended to explicitly probe `/opt/homebrew/bin`,
  `/usr/local/bin`, `~/bin` (stream-read detection consistent with the install script)
- The brew branch of the install script re-locates the real ffmpeg path after install (the relay no longer launches a nonexistent binary)
- Missing ffmpeg (FFMPEG_MISSING) and service-not-running are unified into the install guide card
- The install guide card no longer stacks a generic error state: the red error text + "Reconnect" button duplicated the "Install"
  action; the card shows text per the specific reason, and failed installs keep logs for retry

## [1.5.0] - 2026-08-22

### Added

- **PiP two-finger zoom**: The remote video PiP supports pinch/spread to resize the window (single-finger drag to move, bottom-right handle to resize, tap to fullscreen — all handled by the gesture layer)

### Fixed

- Pressing back after PiP fullscreen jumped straight to the home page: later-registered BackHandlers win, so the fullscreen collapse handler must be registered after onBack
- Fullscreen back button was covered by the video surface/error state, invisible and untappable: the button moved to the topmost layer, now frosted-glass "← Collapse/Back" with text in AppStrings bilingual
- PiP position/size reset after fullscreen round-trips: state hoisted to the session key block (in-component remember is destroyed on fullscreen switches)
- PiP could be dragged off screen: clamp range corrected per the initial top-right position (the old symmetric range overran right/up)
- PiP resize handle undraggable: events dispatch parent→child along the hit path, and the handle's independent gesture was stolen by the parent; unified gesture now judges by start point
- When the PiP hugged the screen's right edge, horizontal scaling was stolen by the system back gesture: the handle area excludes the system back gesture (cross-platform expect/actual)
- Two-finger pinch accidentally triggered fullscreen: the video surface's clickable received events before the gesture layer and sub-touch-slop movement counted as a tap; tap detection now done by the unified gesture

## [1.4.0] - 2026-08-22

### Added

- **Remote video (macOS screen streaming)**: Terminal + menu "Remote video" — watch the Mac screen live over the SSH channel
  - One-tap install of a persistent streaming service on the Mac (a LaunchAgent running in the GUI domain: avfoundation screen capture is TCC-restricted and SSH background sessions can't record, so service-ization works around it), with guided install when the service is missing (streaming logs)
  - Phone-side ExoPlayer + local HTTP stream (MPEG-TS) playback: hardware decoding, Fit scaling, frame-rate/resolution badges; replaced the hand-written MediaCodec pipeline (real OPPO/MTK devices ate input and produced no frames)
  - Terminal page PiP (drag/resize/close) + in-place fullscreen, disconnect reconnect, and same-host multi-session mutual kick
- **Terminal tab back history**: Back goes to the previous tab first (SFTP/screen → terminal), home only when the stack is empty

### Fixed

- Streaming relay disconnect/reconnect handling: BSD nc's FIN half-close is no longer misjudged as a disconnect (during warmup ffmpeg was killed by mistake); a new connection SIGKILLs the old ffmpeg directly, with 45s first-frame / 20s mid-stream no-data self-healing, fixing zombie processes piling up and hogging the capture device; install results judged by TERMISH_SCREEN_OK
- H264Stream Annex-B parser start-code length boundary judgment (3-byte SC + NAL header 0x01 no longer misjudged)

## [1.3.0] - 2026-08-22

### Added

- **Voice input (pluggable ASR)**: A horizontally centered persistent microphone button; tap and speak — realtime transcription on screen + waveform + timer, auto-send after ~2s of silence; the button is draggable, and long-press or the badge resets it to center
  - Recognition services made pluggable: the settings page can add/edit/delete multiple providers (Volcano Engine streaming recognition first), keys stored in platform secure storage, old configs auto-migrated
  - Unconfigured tap shows a prompt + empty-state guidance
- **Markdown preview**: md files in the SFTP file manager render in place (headings/code blocks/inline styles/lists/quotes, zero-dependency pure-Kotlin renderer), with one-tap preview ⇄ source toggle
- **Terminal tools menu**: The bottom-right + expands — upload file (current directory//tmp, remote path typed into the terminal after upload), file manager (located at the terminal working directory), favorites, Git
- **SFTP file manager enhancements**: Multi-select batch (download/delete/copy path), delete (recursive on directories) and rename (platform-level delete/rename, sshj+libssh2), pull-to-refresh, directory favorites (persisted per host), date grouping, empty state, 20 categories of colored file icons
- **Quick commands empty state can add**: The terminal {} panel can create new commands directly when empty (name+content dialog)
- **Global branded notices**: Dark rounded Snackbar + emerald action, unified across terminal/file manager/global; progress widgets unified (TransferProgressCard)

### Fixed

- Voice silence auto-end too sensitive (threshold 0.05→0.02, window 1.6s→2.0s, so quiet speech no longer cuts off)
- SFTP preview layer obscured download progress and completion notices (zIndex + deduplicated SnackbarHost)
- Voice/fragment dialog colors didn't follow the terminal theme
- Voice recognition protocol corrected per real testing (top-level result, frame flags marking the final packet, first packet has no sequence)


## [1.2.4] - 2026-08-22

### Fixed

- **Download notice obscured when the SFTP preview panel is open**: Binary files (apk etc.) enter preview, and the fullscreen
  overlay covered the download progress card and completion notice. Removed the covered duplicate SnackbarHost and raised the progress card's
  zIndex above the preview layer

## [1.2.3] - 2026-08-22

### Fixed

- **Voice button position**: Horizontally centered + vertically at the bottom (above the toolbar), no longer obscuring mid-terminal output
  (1.2.2 mistakenly placed it vertically centered)

## [1.2.2] - 2026-08-22

### Added

- **Voice button moved to screen center**: A persistent 64dp brand-green microphone button when idle, tap once to start recording
  (saving the two-step bottom-right menu); during recording/recognition it switches in place to a red button + transcription overlay, the whole group draggable

## [1.2.1] - 2026-08-22

### Fixed

- **Voice input silence auto-end too sensitive**: Quiet speech (RMS ~1000) was misjudged as silence by the old 0.05 threshold,
  cutting off after a 1.6s pause. Threshold lowered to 0.02 and the silence window extended to 2.4s, tolerating 1-2s pauses mid-sentence

## [1.2.0] - 2026-08-22

### Added

- **Voice input**: Terminal bottom-right tools menu (+ expands: Voice / Upload file / File manager / Git)
  - Tap Voice to start recording: a large 72dp centered button (draggable) + realtime transcription on screen + flowing waveform + recording timer
  - Auto-end and send after ~1.6s of silence; 60s cap; mistouches (<300ms) discarded
  - Volcano Engine "large-model streaming speech recognition" integrated (bigmodel_async WebSocket, response parsing corrected per real protocol testing)
- **Recognition services made pluggable**: The settings page can add/edit/delete multiple recognition services (name/API Key/resource ID, keys in platform secure storage), old configs auto-migrated; adding a service type only requires implementing the AsrEngine interface
- **Terminal file upload**: Pick the target directory from the menu (current / temporary directory, as card options) → system file picker multi-select → SFTP streaming upload + bottom-right progress card; the remote path is typed into the terminal after upload
- **File manager**: One tap in the terminal menu opens the SFTP file manager for the current host, located at the terminal's current working directory
- **SFTP file manager fully reworked**:
  - Multi-select mode (long-press to enter: batch download / delete / copy path)
  - Delete (recursive on directories) and rename (platform layer adds SftpSession.delete/rename, dual sshj + libssh2 implementations)
  - Single tap a file to preview directly; preview panel action menu (download / rename / delete / copy path)
  - Pull-to-refresh, empty state, directory favorites, date sorting grouped by "Today / This week / Earlier"
  - File type icons expanded to 20 categories (colored linear icons, including APK / certificate-key / Markdown / torrent / config)
- **Global unified notice style**: Branded Snackbar (dark rounded card + emerald action), covering terminal page / file manager / global notices

### Improved

- Recording-state overlay unified with Git/voice buttons into brand emerald; the tools menu FAB merged with the Git panel entry
- Upload/download progress widgets unified (TransferProgressCard: floating card in the bottom-right)
- Folder icons returned to theme color; file icons enlarged to 24dp and tinted for distinction

### Fixed

- Voice recognition protocol corrected per real testing: response result is a top-level object, the final packet is marked by frame flags, and the first packet has no sequence field
- Settings page strings split into nested AppStrings subclasses (avoiding the JVM 255-parameter limit)

## [1.1.14] - 2026-08-21

### Fixed

- **Late SSH bootstrap output polluting the screen after mosh takeover**: Cloud-host PAM MOTD scripts
  (landscape/ESM detection) can take 1-3s, and their output arrives after the mosh UDP confirmation;
  the late bytes are written into the UI buffer shared with mosh, landing under the herdr TUI with no later
  mosh frames to cover them (herdr is idle and doesn't redraw), appearing as "Ubuntu upgrade
  text left under herdr". On the mosh UDP first-packet confirmation the moshDisplayTakeover gate is set, discarding on both the enqueue and consume
  sides; the gate resets on reconnect so the degraded SSH path still displays output normally

## [1.1.13] - 2026-08-21

### Fixed

- **iOS Chinese all rendered as "kotlin.Unit"**: `codePointToString` was written as
  `StringBuilder().appendCodePoint(cp).toString()`, but Kotlin/Native's
  StringBuilder has no public `appendCodePoint` member, so resolution within this file found the private extension returning `Unit`
  → every wide character rendered as the literal `kotlin.Unit`; on JVM it happened to be
  shadowed by `java.lang.StringBuilder` members and worked, masking the issue. Replaced with a pure-expression implementation manually converting code points
  to surrogate pairs, unifying platform semantics
- **CJK long-line output froze the screen**: When a wide character's head landed on cols-2 and its tail on cols-1, exactly filling the line,
  `cursorCol` was pushed to cols (out of bounds), and the next character accessed `cells[cursorCol]`, throwing
  out of bounds — the output-consumption coroutine died, and the terminal froze silently with no diagnostics. Now clamped to the last cell
  with deferred wrapping (aligned with narrow-character end-of-line semantics)
- **Output parse exceptions no longer kill the session**: The consume loop's `emulator.write` is wrapped
  in try/catch; a parse exception in one batch only drops that batch (diagnostic logging via `TermLog`),
  and the session stays responsive — skipping a stretch of output worst-case garbles the screen, far better than losing response forever

## [1.1.12] - 2026-08-21

### Fixed

- **herdr/TUI mode scrolling had no fling**: `awaitEachGesture` re-enters immediately after the gesture block returns
  (block runs before waiting for all-up), and the fling-cancel statement sat before `awaitFirstDown`,
  so inertia started on release was killed in the same frame, feeling like "it moves only while you drag";
  moved after waiting for the pointer, and fling decays normally again

## [1.1.11] - 2026-08-21

### Added

- **SFTP text preview** (file menu → Preview): md/txt/log etc. open directly in a fullscreen monospace view,
  streaming only the first 512 KB (large files truncated with a notice), auto-detected as binary if the first 4 KB contain NUL,
  decoded as UTF-8; selectable in-line text + one-tap copy all
- **SFTP image preview**: png/jpg/jpeg/gif/webp/bmp open directly on a black background (Fit scaling),
  with the title bar toggling original pixel size (scrollable for detail); read cap 8MB, with a clear over-limit notice;
  platform decoding (Android BitmapFactory / iOS skia / desktop ImageIO)
- **SFTP upload multi-select**: The three platforms' pickers support selecting multiple files and uploading concurrently
  (Android OpenMultipleDocuments / iOS allowsMultipleSelection /
  JFileChooser multiSelectionEnabled, each streaming independently)
- **SFTP file type icons**: Image/video/audio/archive/code/text/PDF icons differ by extension
  (consistent in list and search results)

### Changed

- **Terminal tab titles prefer the host's custom name**; falling back to `user@host` when unnamed
- **Same-host multi-session tab numbering**: Numbered 1, 2, 3… by position in the current tab list (auto-renumbered on delete)
- **README screenshots all updated** (new UI) + mosh/herdr guided-install screenshots (EN/CN pairs),
  docs synced with install guidance (sudo password sent only this once, skippable with SSH fallback)

## [1.1.10] - 2026-08-21

## [1.1.9] - 2026-08-20

### Changed

- **Removed the "Support the author" donation entry from settings**: The payment QR codes are personal property and not published with the app/repo
  (Alipay/WeChat payment codes and docs/donate/ removed together)

## [1.1.8] - 2026-08-20

## [1.1.7] - 2026-08-20

### Changed

- **Keyboard toolbar expansion no longer flickers**: When ▾ expands the F-key/F-function row it now covers the bottom of the canvas
  instead of compressing it — no PTY resize, so TUIs like herdr no longer reflow and flash;
  keys changed to squares (adaptive to screen width, 48dp touch-target cap); the expand arrow now uses a
  Material icon, with symbol keys (↑↓←→ ⌨ / ⎇) aligned with icon font size at 14sp
- **"Connecting" indicator unified across pages**: SFTP first connection and reconnect share the terminal page's centered capsule
  (previously first connection was plain text); the terminal page indicator centers within the visible "canvas−toolbar" area,
  with colors following the terminal theme (no longer a bright capsule floating over the dark canvas under the app's light theme)

### Fixed

- **SFTP disconnected banner never disappeared**: On reconnect/switching to a new session, closing the old connection synchronously triggered
  the old connection's onClosed callback, and the race mislabeled the just-reconnected session back to "Disconnected".
  Introduced a connection generation ID; callbacks from older generations are ignored; intentional disconnect/removal is likewise no longer mislabeled

## [1.1.6] - 2026-08-20

### Changed

- **herdr downgraded from a connection mode to a connection option**: herdr is a remote app, not a transport protocol, and being a same-level single choice with
  SSH/Mosh was a layering mistake. Now a "Launch the herdr workbench after connecting" checkbox
  (orthogonal to the transport layer): under Mosh it bootstraps `mosh-server new -- herdr` to run the TUI directly;
  the SSH / fallback path injects the herdr command after connecting (exiting back to the shell instead of dropping the whole
  session); removed the entire dedicated exec+pty channel mechanism, cutting connection orchestration by 259 lines

### Fixed

- **New hosts repeatedly popping "Confirm server identity"**: Post-connection system probing overwrote the host snapshot wholesale, wiping the just-recorded TOFU fingerprint back to empty — every subsequent reconnect/new session/SFTP
  popped the dialog again. Now updated with partial fields based on the repository's latest values

## [1.1.5] - 2026-08-20

### Added

- **Terminal toolbar rearranged + new keys**: ESC top-left, CTRL/ALT bottom-left (matching a physical keyboard's bottom row);
  added ⇧⇥ (Shift+Tab, TUI menu reverse-select / Claude Code mode toggle) and ⌃\
  (long-press ⌃C/⌃D to trigger, killing stubborn processes that ignore even ⌃C); arrow keys auto-repeat when held
  (after 400ms, 60ms each); ESC long-press double-sends; ⌃E swapped for /, two 8+8 equal-width rows with ↑↓ aligned

### Fixed

- **Two regressions from the toolbar rearrangement**: Arrow keys lost the ESC byte (sending "[A" instead of
  "ESC[A", typing literal characters with no cursor movement); a trailing lambda mis-bound the long-press callback (normal keys failed on single tap,
  arrow keys unresponsive)
- **Overlay system-back passthrough**: On the host-edit fragment page / fragment management subpage / terminal fragment panel,
  a swipe back went straight to the home page, discarding the form being edited; now the topmost overlay closes first
- **Form pages obscured by the soft keyboard**: On host edit / fragment edit / tag management pages, imePadding lifted
  the covered bottom fields into view; tag input supports IME Done submission (previously only a hardware Enter worked)

## [1.1.4] - 2026-08-19

### Fixed

- **Xcode Cloud archiving double failure**: ① ad-hoc archiving under Xcode 26 (Xcode Cloud always injects `CODE_SIGN_IDENTITY=-`) is mutually exclusive with the Automatic signing style, changed to
  unsigned archive + re-sign at export; ② device builds linked to the simulator slice of
  libssh2 (sim before device in `LIBRARY_SEARCH_PATHS`), now split by SDK condition; ③ removed unreferenced entitlements (keychain default already implies the group,
  behavior unchanged); the personal Team ID stays out of the repo (local real-device first run needs re-selecting the team)

## [1.1.3] - 2026-08-19

### Changed

- **Repository info cleanup**: Removed personal infrastructure
  info from source comments and AI test cases (deploy machine public IP, personal domain, placeholdered internal IPs); NOTICE completed with runtime
  dependency declarations (Kotlin/Compose, kotlinx-*, multiplatform-settings,
  AndroidX, slf4j, MIT), added LICENSES/MIT.txt; reverted mistakenly committed personal
  Xcode signing configuration, keeping pbxproj clean (Team ID injected by Xcode Cloud)

## [1.1.2] - 2026-08-19

### Fixed

- **Xcode Cloud Archive signing configuration**: The repo keeps `CODE_SIGNING_ALLOWED=YES`
  and an empty `DEVELOPMENT_TEAM`, with the Team ID injected via Xcode Cloud environment variables,
  satisfying cloud archive signing while keeping the personal Team ID out of the repo

## [1.1.1] - 2026-08-19

### Fixed

- **Xcode Cloud build failure** (PhaseScriptExecution failed): The cloud build image lacks
  Java and iosApp/native/ is git-ignored (missing OpenSSL/libssh2) — added
  ci_post_clone.sh to auto-install Temurin 17 + cross-compile native dependencies, and the script phase
  gained a JAVA_HOME fallback resolution; local builds unaffected

## [1.1.0] - 2026-08-19

### Added

- **iOS App Store listing preparation**: Privacy manifest (PrivacyInfo.xcprivacy),
  bilingual InfoPlist.strings, encryption compliance declaration (standard-protocol exemption),
  iPhone-only target devices; submission draft in docs/appstore/submission.md

### Fixed

- **Corrupted iOS project file**: The bilingual InfoPlist.strings build entry's isa was mistakenly written as
  PBXVariantGroup, crashing Xcode when parsing the project (unrecognized selector),
  so CI/Xcode Cloud clean checkouts couldn't open the project
- **A batch of iOS compile fixes**: FilePicker compile errors (misplaced NSObject import +
  coordinated-read callback misuse); libssh2 length parameters changed from character count to byte count (SFTP downloads failed on UTF-8 multi-byte
  paths); SFTP upload completion added and hang-on-disconnect prevented; kbiHandler leak;
  herdr bootstrap duplicate probing deduplicated
- **CI build traceability**: main branch test build filenames include version + run number + short SHA,
  so downloads can be traced to the build that produced them

## [1.0.1] - 2026-08-19

### Fixed

- **HERDR mode couldn't enter the workbench**: When sshd non-interactive exec's PATH lacks
  `~/.local/bin`, probing hit the `$HOME`-prefixed candidate, but the downstream mosh bootstrap
  `-- '$HOME/...'` single quotes don't expand and mosh-server execvp's directly without a shell,
  reporting `execvp: $HOME/.local/bin/herdr: No such file or directory`.
  After a probe hit, the path is resolved to absolute (`echo $HOME`) and carried through bootstrap/fallback/reconnect end to end
- Terminal top status strip (connecting/reconnecting/lost/error) changed to square corners, fitting the full-width terminal look

## [1.0.0] - 2026-08-17

First official release (versionCode 3). All prior builds were internal testing, not publicly distributed;
the signing key was rebuilt before release (alias `mssh` → `termish`), with no historical upgrade-compatibility burden.

### Added

- **Pure-Kotlin mosh client** (`dev.termish.mosh`): In-house AES-128-OCB (L-table convention aligned with the protocol spec, including independent standard test vectors), SSP state synchronization (fragmentation/reassembly/
  out-of-order recovery/ACK/throwaway/prospective resend), zlib fragment compression, RTT estimation
  (Jacobson/Karels), direct UDP connection and a 15s connectivity timeout. The shadow terminal reuses the in-house pure-Kotlin
  terminal emulator, with render capped at 50fps, no dependency on the native mosh-client binary.
- Local echo prediction (prediction overlay): at high RTT, prediction renders on the unconfirmed fork per mosh's trigger threshold,
  absorbed by echo_ack, aligning typing immediacy with native mosh-client
- Mosh support on all platforms: SSH bootstraps `mosh-server`, fixed UDP port (NAS/port-forwarding scenarios),
  terminal theme injection (`moshThemeSync`) so TUIs like herdr get the phone's color scheme
- Session management: leaving the Connections tab doesn't disconnect, leaving policy (keep/10 minutes/disconnect), exponential-backoff
  auto-reconnect, Android foreground service + wakelock background keepalive, and recovery from network loss/switches
- Multi-session support: same-host multi-session tabs, terminal page tab bar, session status stat cards
- SFTP: connection overlay + file browsing tab, upload, streaming download, **recursive directory download**, breadcrumb
  navigation (two-level collapse + back = history back), wildcard/multi-keyword search, path/list state kept across tab switches;
  iOS binary byte-stream upload (cinterop bypassing String mapping), directory detection fallback for servers without longentry stat
- Encrypted private key support (PKCS#8 / legacy PEM / OpenSSH, passphrase asked at connection, never written to disk)
- Auto-detection of the remote system (Termius-style): exec probe of os-release/uname after connecting, no manual entry
- macOS-style terminal page header (traffic-light dots, red dot = back), theme-aware status bar icons
- Authentication/host key confirmation dialogs globalized (pops directly while the home page waits to connect)
- Settings and host edit pages with full-row tappable radio buttons (accessibility Role.RadioButton)
- Engineering: root Gradle workflow tasks (testIntegration/runDebug/reinstallDebug),
  thin Makefile shell, signing secrets in `.env` (CI zero-file injection), integration tests self-detecting

### Terminal emulator

- VT100/xterm sequences, UTF-8, CJK wide-character full chain at 2 cells, alt screen, scrollback
- Row-level COW with `(identity, version)` incremental sync, `shallowFork` forking (render performance critical)
- True color/256/ANSI-16, OSC 8/10/11/12/52, bracketed paste, DECSCUSR, mouse reporting
  (X10/SGR/urxvt), focus events, alternate scroll
- Row-level text layout cache (LRU 256 rows, full-screen scrolling shifts with all hits)
- CJK IME as a first-class citizen: composing text never reaches the wire, only committed text is diffed and sent, candidate bar works normally

### Fixed

- SSH output consumption serialized to the main thread: the reader only posts to a bounded queue (256 chunks, TCP backpressure when full),
  and the main thread feeds the emulator from a single point, batching redraws within an 8ms frame budget — all terminal buffer reads/writes
  are confined to the main thread, eliminating races with Canvas drawing and resize row-array rebuilds
- TerminalController coroutine governance: removing a session destroys the controller and reclaims delayed tasks; CancellationException is not swallowed;
  mosh abnormal-exit reconnect is managed under reconnectJob uniformly
- Authentication/host key dialogs 120s timeout: page destruction or no answer is treated as denial, so the connection thread never blocks forever
- Mosh semantic alignment: after connecting it never actively times out (roaming), redraw invalidation (root cause of typing lag/scroll jumps),
  shallowFork phantom-row accumulation, COW stutter, and status dot and lost banner from one source
- Keepalive and reconnect: renewal timing switched to absolute instants to avoid dead windows, network listening distinguishes disconnect/transport switch,
  immunity period/debounce split using a monotonic clock, mosh "connect-then-exit" resets after a stable period, and reconnect failure stops keepalive to prevent spinning
- Terminal resize crash (TerminalLine row/col out-of-sync overrun), click coordinate drift, OSC 8 clicks,
  wrapped-line copy
- iOS: SSH thread safety and resource leaks, connection races, Keychain log metadata
- iOS/desktop connection timeout: tcpConnect changed to non-blocking connect + poll, applying the 15s
  connectTimeoutMillis (previously iOS waited for the kernel's 60s+ TCP retries on black-hole addresses while the UI spun); the error notice changed from a vague errno 60 to "Connection timed out (no response in 15s)"
- Home card three-state display: connecting (CONNECTING/AUTH) no longer counted as "Connected" —
  stats and entry labels distinguish connected green / connecting orange / disconnected gray
- Card taps no longer reuse the old session after host config changes: credential signature (credentialKey) compared,
  and on mismatch a new session is created with the current config (the old session remains on the connections page to close manually)
- CI workflow fixes: job-level if can't use matrix context, which broke workflow parsing;
  Ubuntu sshd needs the /run/sshd directory; Windows WiX switched to official binary download (choco unstable)
- Release pipeline: GitHub Release auto-aggregates artifacts from three platforms with SHA-256 checksums

### Security

- Android Keystore (AES-GCM) / iOS Keychain secret storage; passwords/private keys never stored in plaintext on disk
- TOFU host key verification: confirm on first use, and a fingerprint-change popup compares old and new (with a reset entry);
  tapping trust records the fingerprint, and authentication failure no longer re-pops the trust dialog
- Signing key rebuild: the keystore alias cleaned up from the legacy `mssh` to `termish` (CN=Termish,
  4096-bit RSA, valid 10000 days); the old keystore is archived at
  `~/Documents/秘钥/termish-mssh-legacy-20260817.jks`
- No telemetry, no analytics, and no network requests beyond SSH connections

### Removed

- The native mosh-client path (so mode): the `USE_KMP_MOSH` fallback switch, Android JNI PTY bridge,
  iOS `mosh-client` binary, desktop pty4j, related build scripts and CI steps, and the GPL license notice.
  A complete snapshot of the native implementation is preserved at the `native-mosh` tag

### Documentation

- README made bilingual (English authoritative + full Chinese mirror); docs/ deep-water documents added:
  architecture / terminal-emulator / mosh / input-pipeline
- crypto/README: explicitly records the production-pipeline exception and threat model of the in-house mosh AES-128-OCB implementation
- README home download entries (Android/desktop/iOS three-channel buttons) + bilingual screenshot system:
  6 shots per language in 3×2 (5 Android dark pages + iOS), all real captures on an all-black theme;
  website Screens synced (6 English / 9 Chinese in 3×3)

## [0.2.0] - 2026-08-15

Internal test build, not publicly distributed.

### Added

- Pure-Kotlin terminal emulator + Compose Multiplatform UI (Android/iOS/Desktop)
- Transport layer: JVM sshj + BouncyCastle, iOS libssh2 + static OpenSSL
- Host management: search, tags, quick commands, password/private-key/keyboard-interactive authentication
- Two-row function-key toolbar (F1-F12, arrow keys, sticky CTRL/ALT)
- Design system: zinc neutrals + emerald accents, bundled JetBrains Mono

[Unreleased]: https://github.com/farlume/termish/compare/v1.8.14...HEAD
[1.8.14]: https://github.com/farlume/termish/compare/v1.8.13...v1.8.14
[1.8.13]: https://github.com/farlume/termish/compare/v1.8.12...v1.8.13
[1.8.12]: https://github.com/farlume/termish/compare/v1.8.11...v1.8.12
[1.8.11]: https://github.com/farlume/termish/compare/v1.8.10...v1.8.11
[1.8.10]: https://github.com/farlume/termish/compare/v1.8.9...v1.8.10
[1.8.9]: https://github.com/farlume/termish/compare/v1.8.8...v1.8.9
[1.8.8]: https://github.com/farlume/termish/compare/v1.8.7...v1.8.8
[1.8.7]: https://github.com/farlume/termish/compare/v1.8.6...v1.8.7
[1.8.6]: https://github.com/farlume/termish/compare/v1.8.5...v1.8.6
[1.8.5]: https://github.com/farlume/termish/compare/v1.8.4...v1.8.5
[1.8.4]: https://github.com/farlume/termish/compare/v1.8.3...v1.8.4
[1.8.3]: https://github.com/farlume/termish/compare/v1.8.2...v1.8.3
[1.8.2]: https://github.com/farlume/termish/compare/v1.8.1...v1.8.2
[1.8.1]: https://github.com/farlume/termish/compare/v1.8.0...v1.8.1
[1.8.0]: https://github.com/farlume/termish/compare/v1.7.3...v1.8.0
[1.7.3]: https://github.com/farlume/termish/compare/v1.7.2...v1.7.3
[1.7.2]: https://github.com/farlume/termish/compare/v1.7.1...v1.7.2
[1.7.1]: https://github.com/farlume/termish/compare/v1.7.0...v1.7.1
[1.7.0]: https://github.com/farlume/termish/compare/v1.6.4...v1.7.0
[1.6.4]: https://github.com/farlume/termish/compare/v1.6.3...v1.6.4
[1.6.3]: https://github.com/farlume/termish/compare/v1.6.2...v1.6.3
[1.6.2]: https://github.com/farlume/termish/compare/v1.6.1...v1.6.2
[1.6.1]: https://github.com/farlume/termish/compare/v1.6.0...v1.6.1
[1.6.0]: https://github.com/farlume/termish/compare/v1.5.1...v1.6.0
[1.5.1]: https://github.com/farlume/termish/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/farlume/termish/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/farlume/termish/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/farlume/termish/compare/v1.2.4...v1.3.0
[1.2.4]: https://github.com/farlume/termish/compare/v1.2.3...v1.2.4
[1.2.3]: https://github.com/farlume/termish/compare/v1.2.2...v1.2.3
[1.2.2]: https://github.com/farlume/termish/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/farlume/termish/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/farlume/termish/compare/v1.1.14...v1.2.0
[1.1.14]: https://github.com/farlume/termish/compare/v1.1.13...v1.1.14
[1.1.13]: https://github.com/farlume/termish/compare/v1.1.12...v1.1.13
[1.1.12]: https://github.com/farlume/termish/compare/v1.1.11...v1.1.12
[1.1.11]: https://github.com/farlume/termish/compare/v1.1.10...v1.1.11
[1.1.10]: https://github.com/farlume/termish/compare/v1.1.9...v1.1.10
[1.1.9]: https://github.com/farlume/termish/compare/v1.1.8...v1.1.9
[1.1.8]: https://github.com/farlume/termish/compare/v1.1.7...v1.1.8
[1.1.7]: https://github.com/farlume/termish/compare/v1.1.6...v1.1.7
[1.1.6]: https://github.com/farlume/termish/compare/v1.1.5...v1.1.6
[1.1.5]: https://github.com/farlume/termish/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/farlume/termish/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/farlume/termish/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/farlume/termish/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/farlume/termish/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/farlume/termish/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/farlume/termish/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/farlume/termish/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/farlume/termish/releases/tag/v0.2.0
