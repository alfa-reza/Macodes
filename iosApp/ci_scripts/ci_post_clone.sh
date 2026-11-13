#!/bin/bash
# Pre-build setup for Xcode Cloud (automatically executed after code checkout in Xcode Cloud; has no effect on local builds):
#
#   1. JDK 17—Xcode Cloud images do not include Java; during the “Build Kotlin Framework” phase
#      There is a failure with the `gradlew` command if the JDK is not present (PhaseScriptExecution failed).
#      Install to ~/Library/Java/JavaVirtualMachines (no sudo required; java_home can be detected)
#   2. Rust Remote Interface and Agent Service—Phone resources include precompiled executables for Mac and Linux
#   3. OpenSSL + libssh2 static libraries → iosApp/native/——This directory is git-ignored,
#      No clean checkout; if missing, there is an immediate failure in the Kotlin Framework’s cinterop/linking.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"

if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  echo "== JDK 17 已存在：$(/usr/libexec/java_home -v 17) =="
else
  echo "== 安装 JDK 17（Adoptium Temurin，免 sudo） =="
  JDK_HOME="$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk"
  mkdir -p "$JDK_HOME"
  # For the Adoptium API, use `aarch64/x64` as the arch (macOS `uname` returns `arm64/x86_64`; naive URL construction gets a 404).
  # Fallback polling for unknown architectures (Xcode Cloud only supports ARM and Intel machines); verify the tar file to ensure a valid package is obtained rather than an error page.
  RAW_ARCH="$(uname -m)"
  case "$RAW_ARCH" in
    arm64|aarch64) ARCHES=(aarch64) ;;
    x86_64|x64) ARCHES=(x64) ;;
    *) ARCHES=(aarch64 x64) ;;
  esac
  echo "== uname -m: $RAW_ARCH → 尝试 arch: ${ARCHES[*]} =="
  ok=""
  for arch in "${ARCHES[@]}"; do
    url="https://api.adoptium.net/v3/binary/latest/17/ga/mac/${arch}/jdk/hotspot/normal/eclipse"
    echo "== 下载 Temurin 17 (${arch}) =="
    if curl -fsSL --retry 3 "$url" -o /tmp/temurin17.tar.gz && tar tzf /tmp/temurin17.tar.gz >/dev/null 2>&1; then
      ok="$arch"; break
    fi
    echo "== ${arch} 下载/校验失败，换下一个 =="
  done
  if [ -z "$ok" ]; then
    echo "!! JDK 17 下载失败（uname=$RAW_ARCH，尝试过 ${ARCHES[*]}）"; exit 1
  fi
  echo "== 使用 ${ok} 版 JDK =="
  tar xzf /tmp/temurin17.tar.gz -C "$JDK_HOME" --strip-components=1
  echo "== JDK 17 安装完成：$(/usr/libexec/java_home -v 17) =="
fi

echo "== 准备 Rust 远端画面与 Agent 服务 =="
if [ ! -x "$HOME/.cargo/bin/rustup" ]; then
  curl -fsSL --retry 3 https://sh.rustup.rs -o /tmp/termish-rustup-init.sh
  sh /tmp/termish-rustup-init.sh -y --profile minimal --no-modify-path --default-toolchain stable
fi
"$HOME/.cargo/bin/rustup" target add aarch64-apple-darwin x86_64-apple-darwin \
  aarch64-unknown-linux-musl x86_64-unknown-linux-musl
cd "$ROOT"
sh scripts/service-build.sh build-screen --all
sh scripts/service-build.sh build-agent --all

echo "== 构建 iOS 原生依赖（OpenSSL + libssh2 → iosApp/native/） =="
cd "$ROOT"
bash scripts/build-ios-native.sh
echo "== ci_post_clone 完成 =="
