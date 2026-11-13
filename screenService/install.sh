set -e
PORT=@SCREEN_PORT@
OS=$(uname)
PLIST="$HOME/Library/LaunchAgents/dev.termish.screen.plist"
@NATIVE_SOURCE@
cleanup_legacy_screen_files() {
  # Called only after the native service passes startup verification.
  rm -f "$APP_DIR/screen-relay.py" "$APP_DIR/screen_service_config.py"
  [ ! -L "$APP_DIR/__pycache__" ] || return 0
  for CACHE in "$APP_DIR"/__pycache__/screen-relay.*.pyc "$APP_DIR"/__pycache__/screen_service_config.*.pyc; do
    rm -f "$CACHE"
  done
  rmdir "$APP_DIR/__pycache__" 2>/dev/null || true
}
# ---- ffmpeg: Install if missing (find the path that matches the stream-reading script to avoid non-interactive errors after installation)
# (PATH false positive missing). macOS uses brew/static packages; Linux requires apt (sudo) ——
# Provide a clear tooltip (visible in the log) when the command cannot be detected and there are no apt permissions. ----
FF=""
for cand in $(command -v ffmpeg 2>/dev/null) "$HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
  if [ -n "$cand" ] && [ -x "$cand" ]; then FF="$cand"; break; fi
done
# Linux systems depend on a shared authorization entry point. It cannot be defined only when ffmpeg is missing; otherwise,
# The final screen is visible but is incorrectly flagged as “Remote control not supported.”
if [ "$OS" = "Linux" ]; then
  ADMIN_AVAILABLE=1
  if [ "$(id -u)" = "0" ]; then
    run_admin() { "$@"; }
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    run_admin() { sudo -n "$@"; }
  elif [ "${TERMISH_SUDO_STDIN:-}" = "1" ] && command -v sudo >/dev/null 2>&1; then
    run_admin() { sudo -S -p '' "$@"; }
  else
    ADMIN_AVAILABLE=0
    run_admin() { return 1; }
  fi
fi
if [ -z "$FF" ]; then
  if [ "$OS" = "Darwin" ]; then
  if command -v brew >/dev/null 2>&1; then
    echo "==> Installing ffmpeg via Homebrew (takes a few minutes)"
    brew install ffmpeg
    # After installing via brew, re-set the path (command -v may still cause failure in sessions with restricted PATH; use the "brew" prefix as a fallback).
    FF="$(command -v ffmpeg 2>/dev/null)"
    [ -z "$FF" ] && FF="/opt/homebrew/bin/ffmpeg"
    [ -x "$FF" ] || FF="/usr/local/bin/ffmpeg"
  else
    echo "==> Downloading static ffmpeg to ~/bin"
    mkdir -p "$HOME/bin"
    curl -fsSL -o /tmp/termish-ffmpeg.zip https://evermeet.cx/ffmpeg/getrelease/zip
    rm -rf /tmp/termish-ffmpeg && mkdir -p /tmp/termish-ffmpeg
    unzip -q /tmp/termish-ffmpeg.zip -d /tmp/termish-ffmpeg
    cp /tmp/termish-ffmpeg/ffmpeg "$HOME/bin/ffmpeg"
    chmod +x "$HOME/bin/ffmpeg"
    FF="$HOME/bin/ffmpeg"
  fi
  else
    # Linux: Installs automatically when running as root or with password-less sudo; if the app has provided a password, use `sudo -S`;
    # If no valid authorization method is available, output the manual command that matches the distribution.
    if [ "$ADMIN_AVAILABLE" != "1" ]; then
      if command -v apt-get >/dev/null 2>&1; then
        echo "==> Linux requires administrator privileges: run sudo apt-get update && sudo apt-get install -y ffmpeg in a terminal first, then retry" >&2
      elif command -v dnf >/dev/null 2>&1; then
        echo "==> Linux requires administrator privileges: run sudo dnf install -y ffmpeg in a terminal first, then retry" >&2
      elif command -v pacman >/dev/null 2>&1; then
        echo "==> Linux requires administrator privileges: run sudo pacman -S --needed ffmpeg in a terminal first, then retry" >&2
      else
        echo "==> Linux needs ffmpeg: install it with your system package manager, then retry" >&2
      fi
      exit 1
    fi
    if command -v apt-get >/dev/null 2>&1; then
      echo "==> Installing ffmpeg via apt"
      # Combine `update` and `install` into a single `sudo` command: even if the remote server has disabled the `sudo` time cache,
      # It also reads the password only once and will not wait for additional input on the second command.
      run_admin sh -c 'apt-get update -qq && apt-get install -y -qq ffmpeg'
    elif command -v dnf >/dev/null 2>&1; then
      echo "==> Installing ffmpeg via dnf"
      run_admin dnf install -y -q ffmpeg
    elif command -v pacman >/dev/null 2>&1; then
      echo "==> Installing ffmpeg via pacman"
      run_admin pacman -S --needed --noconfirm ffmpeg
    else
      echo "==> Unrecognized Linux package manager; install ffmpeg manually, then retry" >&2
      exit 1
    fi
    FF="$(command -v ffmpeg 2>/dev/null)"
    [ -n "$FF" ] || FF="/usr/bin/ffmpeg"
  fi
fi
# Keep package-manager symlinks so upgrades do not pin an obsolete binary.
# The service runs outside this SSH shell, so relative PATH entries need an absolute path.
FF_PATH="$FF"
case "$FF_PATH" in
  /*) ;;
  *) FF_PATH="$(pwd)/$FF_PATH" ;;
esac
echo "==> ffmpeg: $FF_PATH"
# A unique 256-bit bearer token for each remote account: even a loopback TCP connection on the same machine
# For users accessing from other operating systems, “a listener that listens only on 127.0.0.1” should not be treated as an authentication boundary.
TOKEN_FILE="$HOME/.termish-screen.token"
TOKEN=""
if [ -f "$TOKEN_FILE" ]; then
  TOKEN="$(tr -d '\r\n' < "$TOKEN_FILE" 2>/dev/null || true)"
fi
if ! printf '%s' "$TOKEN" | grep -Eq '^[0-9a-fA-F]{64}$'; then
  umask 077
  TOKEN_TMP="$TOKEN_FILE.tmp.$$"
  od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$TOKEN_TMP"
  mv "$TOKEN_TMP" "$TOKEN_FILE"
fi
chmod 600 "$TOKEN_FILE"
# X11 text paste uses xclip; Wayland capture uses the consent-scoped GStreamer PipeWire source.
if [ "$OS" = "Linux" ]; then
  # GNOME without a StatusNotifier host uses the application-list management window.
  if ! command -v zenity >/dev/null 2>&1; then
    [ "$ADMIN_AVAILABLE" = "1" ] || { echo '==> Desktop management requires zenity and administrator permission' >&2; exit 1; }
    if command -v apt-get >/dev/null 2>&1; then
      run_admin sh -c 'apt-get update -qq && apt-get install -y -qq zenity'
    elif command -v dnf >/dev/null 2>&1; then
      run_admin dnf install -y -q zenity
    elif command -v pacman >/dev/null 2>&1; then
      run_admin pacman -S --needed --noconfirm zenity
    else
      echo '==> Install zenity, then retry' >&2; exit 1
    fi
  fi
  if [ "${TERMISH_SESSION_TYPE:-}" = "wayland" ]; then
    if ! command -v gst-launch-1.0 >/dev/null 2>&1 || ! gst-inspect-1.0 pipewiresrc >/dev/null 2>&1 || ! gst-inspect-1.0 y4menc >/dev/null 2>&1; then
      [ "$ADMIN_AVAILABLE" = "1" ] || { echo '==> Wayland capture dependencies require administrator permission' >&2; exit 1; }
      if command -v apt-get >/dev/null 2>&1; then
        run_admin sh -c 'apt-get update -qq && apt-get install -y -qq gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-base'
      elif command -v dnf >/dev/null 2>&1; then
        run_admin dnf install -y -q gstreamer1-plugins-base pipewire-gstreamer gstreamer1-tools
      elif command -v pacman >/dev/null 2>&1; then
        run_admin pacman -S --needed --noconfirm gst-plugins-base gst-plugin-pipewire gstreamer
      else
        echo '==> Install GStreamer tools, PipeWire and base plugins, then retry' >&2; exit 1
      fi
    fi
  elif ! command -v xclip >/dev/null 2>&1; then
    [ "$ADMIN_AVAILABLE" = "1" ] || { echo '==> X11 text paste requires xclip and administrator permission' >&2; exit 1; }
    if command -v apt-get >/dev/null 2>&1; then
      run_admin sh -c 'apt-get update -qq && apt-get install -y -qq xclip'
    elif command -v dnf >/dev/null 2>&1; then
      run_admin dnf install -y -q xclip
    elif command -v pacman >/dev/null 2>&1; then
      run_admin pacman -S --needed --noconfirm xclip
    else
      echo '==> Install xclip, then retry' >&2; exit 1
    fi
  fi
fi
mkdir -p "$APP_DIR"
"$TERMISH_NATIVE_EXEC" --write-config "$APP_DIR/screen-service.json" "$PORT" "$FF_PATH"
"$TERMISH_NATIVE_EXEC" --check-config --config "$APP_DIR/screen-service.json"
@MACOS_SOURCE@
restore_macos_service() {
  launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
  rm -rf "$APP_BUNDLE"
  if [ -d "$APP_BACKUP" ]; then mv "$APP_BACKUP" "$APP_BUNDLE"; fi
  if [ -f "$RELAY_BACKUP" ]; then
    mv "$RELAY_BACKUP" "$RELAY"
  elif [ "$RELAY_REPLACED" = "1" ]; then
    rm -f "$RELAY"
  fi
  if [ -f "$BACKEND_BACKUP" ]; then
    mv "$BACKEND_BACKUP" "$APP_DIR/screen-service.backend"
  else
    rm -f "$APP_DIR/screen-service.backend"
  fi
  if [ -f "$PLIST_BACKUP" ]; then
    mv "$PLIST_BACKUP" "$PLIST"
    launchctl bootstrap gui/$(id -u) "$PLIST" 2>/dev/null || true
  else
    rm -f "$PLIST"
  fi
}
if [ "$OS" = "Darwin" ]; then
  /usr/bin/python3 --version >/dev/null 2>&1 || { echo '==> macOS system Python is required' >&2; exit 1; }
  RELAY="$APP_DIR/screen-relay.py"
  RELAY_STAGE="$TERMISH_NATIVE_STAGE.py"
  RELAY_BACKUP="$APP_DIR/.screen-relay.previous"
  RELAY_REPLACED=0
  BACKEND_BACKUP="$APP_DIR/.screen-service.backend.previous"
  write_macos_relay > "$RELAY_STAGE"
  chmod 600 "$RELAY_STAGE"
  /usr/bin/python3 "$RELAY_STAGE" --check-config --config "$APP_DIR/screen-service.json"
  /usr/bin/python3 - "$RELAY_STAGE" <<'TERMISH_PY_CHECK'
import ast, sys
with open(sys.argv[1]) as source:
    ast.parse(source.read())
TERMISH_PY_CHECK
  # Only replace this service after checksum, bundle signature and config pass.
  PLIST_BACKUP="$PLIST.termish-previous"
  rm -f "$PLIST_BACKUP"
  if [ -f "$PLIST" ]; then cp "$PLIST" "$PLIST_BACKUP"; fi
  rm -f "$BACKEND_BACKUP"
  if [ -f "$APP_DIR/screen-service.backend" ]; then cp "$APP_DIR/screen-service.backend" "$BACKEND_BACKUP"; fi
  launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
  APP_BACKUP="$APP_DIR/.Termish Helper.previous"
  rm -rf "$APP_BACKUP"
  if [ -d "$APP_BUNDLE" ]; then mv "$APP_BUNDLE" "$APP_BACKUP"; fi
  rm -f "$RELAY_BACKUP"
  if [ -f "$RELAY" ]; then cp "$RELAY" "$RELAY_BACKUP"; fi
  if ! mv "$TERMISH_APP_STAGE" "$APP_BUNDLE"; then
    restore_macos_service
    exit 1
  fi
  if ! mv "$RELAY_STAGE" "$RELAY"; then
    restore_macos_service
    exit 1
  fi
  RELAY_REPLACED=1
  rm -f "$TERMISH_NATIVE_STAGE"
else
  mv "$TERMISH_NATIVE_STAGE" "$NATIVE"
fi
"$NATIVE" --licenses > "$APP_DIR/screen-service.NOTICE"
if [ "$OS" = "Darwin" ]; then
  printf 'python-macos\n' > "$APP_DIR/screen-service.backend"
else
  printf 'rust\n' > "$APP_DIR/screen-service.backend"
fi
# ---- Service launch: macOS uses LaunchAgent (GUI screen recording permission);
# Linux uses systemd user services (with desktop autostart as a fallback); runs after a system reboot upon graphical login
# Automatic resume. Older versions only used `nohup`; processes would survive after the SSH session was disconnected but would be lost after a reboot. ----
if [ "$OS" = "Darwin" ]; then
mkdir -p "$HOME/Library/LaunchAgents"
if ! /usr/bin/python3 - "$PLIST" "$RELAY" "$APP_DIR/screen-service.json" <<'TERMISH_PLIST'
import os, plistlib, sys
os.umask(0o077)
with open(sys.argv[1], 'wb') as output:
    plistlib.dump({
        'Label': 'dev.termish.screen',
        'ProgramArguments': ['/usr/bin/python3', sys.argv[2], '--config', sys.argv[3]],
        'RunAtLoad': True,
        'KeepAlive': {'SuccessfulExit': False},
    }, output)
TERMISH_PLIST
then
  restore_macos_service
  exit 1
fi
launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
sleep 1
# When relay launches, it cleans up orphaned ffmpeg processes from the previous round based on its own PID file; this cannot
# pkill all ffmpeg processes; otherwise, it may mistakenly terminate the user’s own transcoding or recording tasks.
if ! launchctl bootstrap gui/$(id -u) "$PLIST"; then
  restore_macos_service
  exit 1
fi
sleep 1
# Verify using launchctl (do not touch the connection: detecting a connection—disconnecting will interrupt the relay’s current service cycle;
# (Also, do not use pgrep: the script’s own zsh command line containing the script text may cause false matches.)
if launchctl print gui/$(id -u)/dev.termish.screen 2>/dev/null | grep -q "state = running" \
  && lsof -nP -iTCP:$((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1; then
  echo @RELAY_VERSION@ > "$HOME/.termish-screen.version"
  rm -rf "$APP_BACKUP"
  rm -f "$PLIST_BACKUP"
  rm -f "$RELAY_BACKUP"
  rm -f "$BACKEND_BACKUP"
  rm -f "$APP_DIR/screen-service"
  echo "==> TERMISH_SCREEN_OK"
else
  restore_macos_service
  echo "==> Service did not start (check ~/Library/Logs/termish-screen.err)" >&2
  exit 1
fi
else
# Linux: Restart the relay and register the user-level persistent service.
# ⚠️ Do not use `pkill -f`: The install script’s own (sh -c) command line contains
# Script text: The -f option for exact matching will crash the program (same issue on the macOS branch; user feedback:
# (Ubuntu boot installation failure) — Use PID files for precise cleanup
RELAY_PID="$HOME/.termish-screen.pid"
stop_termish_relay_pid() {
  OLD_PID="$1"
  # The install script enables `set -e`; PID file corruption is a normal part of the upgrade process, and the function must
  # Explicitly return success; otherwise, the process will exit early after writing the new relay but before launching it.
  case "$OLD_PID" in ''|*[!0-9]*) return 0 ;; esac
  [ -r "/proc/$OLD_PID/cmdline" ] || return 0
  OLD_CMD="$(tr '\0' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)"
  case "$OLD_CMD" in
    *"$NATIVE"*|*"$HOME/.termish-screen-launch.sh"*) kill "$OLD_PID" 2>/dev/null || true ;;
  esac
  return 0
}
if [ -f "$RELAY_PID" ]; then
  stop_termish_relay_pid "$(cat "$RELAY_PID" 2>/dev/null)"
  rm -f "$RELAY_PID"
fi
# Versions v31 and earlier may not have reliable PID files, or a process failure may have overwritten the PID.
# After locating the old process from the listener port, still verify item by item that the command line must include the absolute path to the current relay,
LISTENER_PID=""
if command -v lsof >/dev/null 2>&1; then
  LISTENER_PID="$(lsof -t -iTCP:$PORT -sTCP:LISTEN 2>/dev/null | head -1)"
elif command -v fuser >/dev/null 2>&1; then
  LISTENER_PID="$(fuser -n tcp "$PORT" 2>/dev/null | awk '{print $1}')"
fi
stop_termish_relay_pid "$LISTENER_PID"
sleep 0.5
# The launch wrapper re-detects DISPLAY/XAUTHORITY on every run. Xwayland's display
# The serial number and authorization files will change after a restart; the values from the time of installation must not be hard-coded into the service unit.
RUNNER="$HOME/.termish-screen-launch.sh"
LOG="$HOME/.termish-screen.log"
cat > "$RUNNER" <<'TERMISH_EOF'
#!/bin/sh
NATIVE="$HOME/Library/Application Support/termish/screen-service"
LOG="$HOME/.termish-screen.log"
# relay logs record exceptions, bitrate adjustments, and encoder self-healing; log size is still limited to avoid long-term
# Once stored, it grows indefinitely. Retain the last 1 MiB to allow for troubleshooting the most recent session.
if [ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 5242880 ]; then
  tail -c 1048576 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
fi
while :; do
  USER_RUNTIME="/run/user/$(id -u)"
  XDISP=":0"
  XPID=""
  GRAPHICAL=0
  LOCAL_SESSION_TYPE=""
  # logind is the authoritative source for session types. Wayland also launches Xwayland; if
  # Checking the process may mistakenly trigger x11grab and output a black root window.
  if command -v loginctl >/dev/null 2>&1; then
    for sid in $(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="$(id -u)" '$2 == uid { print $1 }'); do
      SESSION_TYPE="$(loginctl show-session "$sid" -p Type --value 2>/dev/null)"
      SESSION_REMOTE="$(loginctl show-session "$sid" -p Remote --value 2>/dev/null)"
      case "$SESSION_TYPE:$SESSION_REMOTE" in
        wayland:no|x11:no)
          GRAPHICAL=1
          LOCAL_SESSION_TYPE="$SESSION_TYPE"
          SESSION_DISPLAY="$(loginctl show-session "$sid" -p Display --value 2>/dev/null)"
          [ -n "$SESSION_DISPLAY" ] && XDISP="$SESSION_DISPLAY"
          break
          ;;
      esac
    done
  fi
  if command -v pgrep >/dev/null 2>&1; then
    # Select only the current user’s graphics server; the GDM greeter belongs to another user.
    XPID="$(pgrep -u "$(id -u)" -x Xwayland 2>/dev/null | head -1)"
    if [ -z "$XPID" ]; then
      XPID="$(pgrep -u "$(id -u)" -x Xorg 2>/dev/null | head -1)"
    fi
  fi
  if [ -n "$XPID" ] && [ -r "/proc/$XPID/cmdline" ]; then
    DETECTED_DISPLAY="$(tr '\0' '\n' < "/proc/$XPID/cmdline" | grep -E '^:[0-9]+$' | head -1)"
    [ -n "$DETECTED_DISPLAY" ] && XDISP="$DETECTED_DISPLAY"
    if [ "$GRAPHICAL" != "1" ] && [ "$(basename "$(readlink "/proc/$XPID/exe" 2>/dev/null)")" = "Xorg" ]; then
      GRAPHICAL=1
      LOCAL_SESSION_TYPE="x11"
    fi
  fi
  XNUM="${XDISP#:}"
  if [ "$GRAPHICAL" = "1" ]; then
    export XDG_RUNTIME_DIR="$USER_RUNTIME"
    export DBUS_SESSION_BUS_ADDRESS="unix:path=$USER_RUNTIME/bus"
    if [ "$LOCAL_SESSION_TYPE" = "wayland" ]; then
      WAYLAND_SOCKET="$(find "$USER_RUNTIME" -maxdepth 1 -type s -name 'wayland-*' -printf '%f\n' 2>/dev/null | sort | head -1)"
      if [ -z "$WAYLAND_SOCKET" ]; then
        sleep 2
        continue
      fi
      export XDG_SESSION_TYPE=wayland
      export WAYLAND_DISPLAY="$WAYLAND_SOCKET"
      COMPOSITOR_PID="$(pgrep -u "$(id -u)" -x kwin_wayland 2>/dev/null | head -1)"
      if [ -n "$COMPOSITOR_PID" ] && [ -r "/proc/$COMPOSITOR_PID/environ" ]; then
        CURRENT_DESKTOP="$(tr '\0' '\n' < "/proc/$COMPOSITOR_PID/environ" | sed -n 's/^XDG_CURRENT_DESKTOP=//p' | head -1)"
        [ -n "$CURRENT_DESKTOP" ] && export XDG_CURRENT_DESKTOP="$CURRENT_DESKTOP"
      fi
    else
      [ -S "/tmp/.X11-unix/X$XNUM" ] || { sleep 2; continue; }
      export XDG_SESSION_TYPE=x11
      unset WAYLAND_DISPLAY
    fi
    if [ -S "/tmp/.X11-unix/X$XNUM" ]; then
      export DISPLAY="$XDISP"
    fi
    XAUTH=""
    if [ -n "$XPID" ] && [ -r "/proc/$XPID/cmdline" ]; then
      XAUTH="$(tr '\0' '\n' < "/proc/$XPID/cmdline" | awk 'take { print; exit } $0 == "-auth" { take=1 }')"
    fi
    if [ -z "$XAUTH" ] && [ -f "/run/user/$(id -u)/gdm/Xauthority" ]; then
      XAUTH="/run/user/$(id -u)/gdm/Xauthority"
    fi
    if [ -z "$XAUTH" ] && [ -f "$HOME/.Xauthority" ]; then
      XAUTH="$HOME/.Xauthority"
    fi
    if [ -n "$XAUTH" ] && [ -r "$XAUTH" ]; then
      export XAUTHORITY="$XAUTH"
    fi
    exec "$NATIVE" >> "$LOG" 2>&1
  fi
  sleep 2
done
TERMISH_EOF
chmod 700 "$RUNNER"

SERVICE_STARTED=0
USER_RUNTIME="/run/user/$(id -u)"
UNIT_DIR="$HOME/.config/systemd/user"
UNIT="$UNIT_DIR/dev.termish.screen.service"
if command -v systemctl >/dev/null 2>&1 && [ -S "$USER_RUNTIME/bus" ]; then
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT" <<TERMISH_EOF
[Unit]
Description=Termish screen relay
After=graphical-session.target

[Service]
Type=simple
ExecStart=%h/.termish-screen-launch.sh
Environment=TERMISH_SYSTEMD_SERVICE=1
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
TERMISH_EOF
  export XDG_RUNTIME_DIR="$USER_RUNTIME"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=$USER_RUNTIME/bus"
  # `enable --now` will not restart existing running units; you must explicitly restart when upgrading Relay,
  # The presence of a session bus does not imply that the systemd user manager is available; in case of failure, proceed with desktop autostart.
  if systemctl --user daemon-reload \
    && systemctl --user enable dev.termish.screen.service \
    && systemctl --user restart dev.termish.screen.service; then
    SERVICE_STARTED=1
    rm -f "$HOME/.config/autostart/dev.termish.screen.desktop"
    echo "==> systemd user service: enabled (restored automatically after reboot)"
  fi
fi
if [ "$SERVICE_STARTED" != "1" ]; then
  # When the systemd user bus is not available (on some minimal desktops), pass XDG autostart
  # Ensure that graphical login starts automatically next time; for the current installation, use `nohup` to launch it immediately.
  mkdir -p "$HOME/.config/autostart"
  cat > "$HOME/.config/autostart/dev.termish.screen.desktop" <<'TERMISH_EOF'
[Desktop Entry]
Type=Application
Name=Termish Screen Relay
Exec=/bin/sh -c "$HOME/.termish-screen-launch.sh"
X-GNOME-Autostart-enabled=true
NoDisplay=true
TERMISH_EOF
  nohup "$RUNNER" >/dev/null 2>&1 &
  echo $! > "$RELAY_PID"
  echo "==> Desktop autostart service: enabled (restored automatically after reboot)"
fi
sleep 1.5
# Verify that both the control plane and the loopback video port are listeners; do not establish a probe connection (which would trigger ffmpeg).
if (lsof -nP -iTCP:$PORT -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":$PORT ") \
  && (lsof -nP -iTCP:$((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":$((PORT + 2)) "); then
  echo @RELAY_VERSION@ > "$HOME/.termish-screen.version"
  "$NATIVE" --install-desktop
  cleanup_legacy_screen_files
  echo "==> TERMISH_SCREEN_OK"
else
  echo "==> Service did not start (check $LOG)" >&2
  exit 1
fi
fi
