#!/bin/bash
# Launch a local, non-root test instance of sshd (port 22222) for transport layer integration test.
# Generate host/client ed25519 keys and use the client’s public key for pubkey authentication.
set -e

DIR="${Termish_TEST_DIR:-/tmp/termish_test}"
PORT="${Termish_TEST_PORT:-22222}"
mkdir -p "$DIR"
cd "$DIR"

[ -f hostkey ] || ssh-keygen -q -t ed25519 -f hostkey -N "" -C "termish-test-host"
[ -f client ]  || ssh-keygen -q -t ed25519 -f client  -N "" -C "termish-test-client"
# In CI, sshd is launched as root, while the test process runs as a regular user: the private key must be readable (for local testing purposes only).
chmod 644 client
cat client.pub > authorized_keys
# Second public key for the encryption integration test of the private key (retain if present)
[ -f enc_client.pub ] && cat enc_client.pub >> authorized_keys

cat > sshd_config <<EOF
Port $PORT
ListenAddress 0.0.0.0
HostKey $DIR/hostkey
AuthorizedKeysFile $DIR/authorized_keys
PasswordAuthentication yes
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM yes
StrictModes no
ChallengeResponseAuthentication no
PidFile $DIR/sshd.pid
LogLevel DEBUG
EOF

# SFTP subsystem (required for SFTP integration test; path conventions differ between macOS and Linux)
if [ -x /usr/libexec/sftp-server ]; then
  SFTP_SERVER=/usr/libexec/sftp-server
elif [ -x /usr/lib/openssh/sftp-server ]; then
  SFTP_SERVER=/usr/lib/openssh/sftp-server
fi
echo "Subsystem sftp $SFTP_SERVER" >> "$DIR/sshd_config"

# Ubuntu’s sshd requires the privileged directory /run/sshd (automatically created when root has write access;
# (Skip when running locally on macOS without root privileges; this has no impact.)
if [ ! -d /run/sshd ] && [ -w / ]; then
  mkdir -p /run/sshd
fi

echo "启动 sshd 于 127.0.0.1:$PORT（日志: $DIR/sshd.log）"
# Clean up old instances left over from the last run (ports may be occupied when running repeatedly on the same runner).
if [ -f "$DIR/sshd.pid" ] && kill -0 "$(cat "$DIR/sshd.pid")" 2>/dev/null; then
  echo "停止残留旧 sshd pid=$(cat "$DIR/sshd.pid")"
  kill "$(cat "$DIR/sshd.pid")" && sleep 0.5
fi
/usr/sbin/sshd -f "$DIR/sshd_config" -E "$DIR/sshd.log"
sleep 0.5
# After the sshd daemon forks, the parent process returns 0 immediately (set -e cannot catch bind failures),
# Also, nc scans may trigger false positives on other services (e.g., GitLab’s 2222) —
# The only criterion for success must be the confirmation row in the sshd log indicating that the listener is running.
if grep -q "Server listening on" "$DIR/sshd.log" 2>/dev/null; then
  echo "OK: 端口 $PORT 已监听"
else
  echo "FAIL: sshd 未监听 $PORT，日志如下："
  tail -20 "$DIR/sshd.log" 2>/dev/null || true
  exit 1
fi
