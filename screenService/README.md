# Termish Screen Service

> **Summary:** macOS uses a Python session relay with native ScreenCaptureKit/CoreGraphics capture, VideoToolbox encoding, and session-scoped power assertions. Linux keeps the Rust X11/Wayland service. Both stream authenticated H.264 video through loopback TCP over SSH.

- `macos/screen-relay.py`: the macOS system-Python service template, managing authenticated sessions, power assertions, the capture subprocess and standard-library CGEvent input.
- `macos/Capture.m`: ScreenCaptureKit first, CoreGraphics fallback, VideoToolbox H.264; includes a synthetic-pattern self-check that also runs without screen-recording permission.
- `macos/tests/`: Python service regression tests for authentication, video packets, feedback recovery, heartbeat and encoder cleanup.
- `rust/`: Linux TCP, FFmpeg, X11 XTEST, Wayland Portal; the macOS install and state-probing tooling is retained.
- `service.properties`: deployment version and default ports, read by the build tooling.
- `install.sh`, `native-install.sh`: verify the uploaded artifacts, configure and register the graphical session service, and restore the old macOS service on failure.

```bash
make screen-service
./gradlew screenServiceTest
sh scripts/service-build.sh build-screen --all
python3 -m unittest discover -s screenService/macos/tests -v
```

macOS runs the session service with `/usr/bin/python3`; after authentication it wakes the display and
keeps the system and display from sleeping while idle, then releases all power assertions on
disconnect or heartbeat expiry. It does not modify persistent power settings or unlock the system.
The Helper's native capture subprocess handles the picture; you must allow the Helper to record the
screen in macOS and sign it with a stable certificate to preserve the upgrade authorisation identity.
Whether the screen-recording permission is missing is decided only after a capture attempt, not by an
extra pre-check that would block capture; without the control permission, the picture can still be watched.
After a display configuration change the capture subprocess is recreated, keeping the authenticated
channel and the frame sequence. A static desktop repeats the most recent frame, so stream-stall
detection is not triggered by mistake.
When the native tool is unavailable the FFmpeg fallback is kept, and the screen is located from the
AVFoundation list instead of a fixed device index.
Closed-lid recovery depends on the actual display state and authorisation the system provides and
needs real-device acceptance; a synthetic-pattern test is not a substitute.
Linux continues to offer pause, resume, disconnect, restart, logs and quit through the tray and the
management window.
Configuration, token and logs stay outside the app bundle; a new version requires updating the
computer-side service once from the app.
See the [remote screen service documentation](../docs/screen-service.md) for build and diagnostics.
