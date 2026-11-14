# Rust payload arrives over authenticated SFTP, never inside a shell argument.
APP_DIR="$HOME/Library/Application Support/macodes"
NATIVE="$APP_DIR/screen-service"
MACODES_BACKEND=rust
[ -n "${MACODES_NATIVE_STAGE:-}" ] || { echo "==> Native payload required" >&2; exit 1; }
if [ -n "${MACODES_NATIVE_STAGE:-}" ]; then
  case "$MACODES_NATIVE_STAGE" in
    "$APP_DIR"/screen-service.upload-*) ;;
    *) echo '==> Invalid service staging path' >&2; exit 1 ;;
  esac
  case "${MACODES_NATIVE_SHA256:-}" in
    ''|*[!0-9a-f]*) echo '==> Invalid service checksum' >&2; exit 1 ;;
  esac
  [ "${#MACODES_NATIVE_SHA256}" = 64 ] || exit 1
  if command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA=$(shasum -a 256 "$MACODES_NATIVE_STAGE" | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA=$(sha256sum "$MACODES_NATIVE_STAGE" | awk '{print $1}')
  else
    echo '==> SHA-256 verifier unavailable' >&2; exit 1
  fi
  if [ "$ACTUAL_SHA" != "$MACODES_NATIVE_SHA256" ]; then
    echo '==> Service payload checksum mismatch' >&2; exit 1
  fi
  chmod 700 "$MACODES_NATIVE_STAGE"
  MACODES_NATIVE_EXEC="$MACODES_NATIVE_STAGE"
  if [ "$OS" = "Darwin" ]; then
    APP_BUNDLE="$APP_DIR/Macodes Helper.app"
    MACODES_APP_STAGE="$MACODES_NATIVE_STAGE.app"
    trap 'rm -rf "$MACODES_APP_STAGE"; [ -z "${RELAY_STAGE:-}" ] || rm -f "$RELAY_STAGE"' 0
    mkdir -m 700 "$MACODES_APP_STAGE"
    tar -xzf "$MACODES_NATIVE_STAGE" -C "$MACODES_APP_STAGE"
    MACODES_NATIVE_EXEC="$MACODES_APP_STAGE/Contents/MacOS/Macodes Helper"
    /usr/bin/codesign --verify --strict "$MACODES_APP_STAGE"
    NATIVE="$APP_BUNDLE/Contents/MacOS/Macodes Helper"
  fi
  if [ "$("$MACODES_NATIVE_EXEC" --version)" != "@RELAY_VERSION@" ]; then
    echo '==> Service payload version or architecture mismatch' >&2; exit 1
  fi
  MACODES_BACKEND=rust
  echo '==> Rust service payload verified'
fi
