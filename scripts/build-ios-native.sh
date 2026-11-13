#!/bin/bash
# Build native iOS SSH dependencies: OpenSSL (static) + libssh2 (static), and output to iosApp/native.
#
# Why build it yourself: The precompiled library included with NTermish is incompatible with the new Xcode linker (arm64 emulator missing,
# Archive members do not have alignment); the modern libssh2 pod also has a dependency on GitHub git cloning (network restricted). This script starts from
# Download the source code from the official repositories (openssl.org / libssh2.org), cross-compile it, and statically link the result into the Kotlin framework.
#
# Output:
#   iosApp/native/include/openssl/*.h
#   iosApp/native/include/libssh2.h
#   iosApp/native/lib/libcrypto.a  libssl.a  libssh2.a   (fat: arm64-device + arm64-sim + x86_64-sim)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/iosApp/native"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

OPENSSL_VER="3.0.16"
LIBSSH2_VER="1.11.1"
MIN_IOS="13.0"

echo "== 下载源码 =="
curl -sL --retry 3 "https://www.openssl.org/source/openssl-$OPENSSL_VER.tar.gz" | tar xz -C "$WORK"
curl -sL --retry 3 "https://www.libssh2.org/download/libssh2-$LIBSSH2_VER.tar.gz" | tar xz -C "$WORK"
OPENSSL_DIR="$WORK/openssl-$OPENSSL_VER"
LIBSSH2_DIR="$WORK/libssh2-$LIBSSH2_VER"

DEVSYSROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
SIMSDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"

mkdir -p "$WORK/out/lib"

# ---------- OpenSSL ----------
build_openssl() {
  local name="$1" target="$2" extra="${3:-}"
  echo "== OpenSSL: $name =="
  local dir="$WORK/build-openssl-$name"
  cp -R "$OPENSSL_DIR" "$dir"
  (
    cd "$dir"
    ./Configure "$target" no-shared no-tests no-asm $extra >/dev/null
    make -j"$(sysctl -n hw.ncpu)" build_generated >/dev/null
    make -j"$(sysctl -n hw.ncpu)" libcrypto.a libssl.a >/dev/null
    cp libcrypto.a "$WORK/out/lib/libcrypto-$name.a"
    cp libssl.a "$WORK/out/lib/libssl-$name.a"
  )
}
build_openssl "device"    "ios64-xcrun"          ""
build_openssl "sim-arm64" "iossimulator-xcrun"   ""

echo "== OpenSSL 头文件 =="
mkdir -p "$WORK/out/include/openssl"
# Use the header files in the build directory (including generated files such as opensslv.h, etc.)
cp "$WORK/build-openssl-device"/include/openssl/*.h "$WORK/out/include/openssl/"
cp "$LIBSSH2_DIR"/include/*.h "$WORK/out/include/"

# ---------- libssh2 ----------
build_libssh2_single() {
  local name="$1" arch="$2" sdk="$3" minflag="$4"
  echo "== libssh2: $name =="
  local dir="$WORK/build-libssh2-$name"
  cp -R "$LIBSSH2_DIR" "$dir"
  # Prepare the OpenSSL static library prefix for a single architecture
  local oprefix="$WORK/openssl-$name"
  mkdir -p "$oprefix/include" "$oprefix/lib"
  cp -R "$WORK/out/include/." "$oprefix/include/"
  cp "$WORK/out/lib/libcrypto-$name.a" "$oprefix/lib/libcrypto.a" 2>/dev/null || true
  cp "$WORK/out/lib/libssl-$name.a" "$oprefix/lib/libssl.a" 2>/dev/null || true
  # If the single-architecture OpenSSL version does not exist, use "fat" (this script has already generated all single-architecture versions).
  if [ ! -f "$oprefix/lib/libcrypto.a" ]; then
    cp "$WORK/out/lib/libcrypto.a" "$oprefix/lib/libcrypto.a"
    cp "$WORK/out/lib/libssl.a" "$oprefix/lib/libssl.a"
  fi
  (
    cd "$dir"
    export CC="$(xcrun -f clang) -arch $arch -isysroot $sdk $minflag"
    export CFLAGS="-I$oprefix/include"
    export LDFLAGS="-L$oprefix/lib"
    ./configure --host="$arch-apple-darwin" --disable-shared --enable-static \
      --disable-examples-build --with-crypto=openssl --with-libssl-prefix="$oprefix" --with-libz >/dev/null
    make -j"$(sysctl -n hw.ncpu)" >/dev/null
    cp src/.libs/libssh2.a "$WORK/out/lib/libssh2-$name.a"
  )
}
build_libssh2_single "device" "arm64" "$DEVSYSROOT" "-mios-version-min=$MIN_IOS"
build_libssh2_single "sim-arm64" "arm64" "$SIMSDK" "-mios-simulator-version-min=$MIN_IOS"

echo "== libssh2 构建完成（device / sim 分开）=="

echo "== 整理产物（device / sim 分开，因同为 arm64 无法 lipo 合并）=="
rm -rf "$VENDOR"
mkdir -p "$VENDOR/include" "$VENDOR/lib/device" "$VENDOR/lib/sim"
cp -R "$WORK/out/include/." "$VENDOR/include/"
cp "$WORK/out/lib/libcrypto-device.a" "$WORK/out/lib/libssl-device.a" "$WORK/out/lib/libssh2-device.a" "$VENDOR/lib/device/"
cp "$WORK/out/lib/libcrypto-sim-arm64.a" "$WORK/out/lib/libssl-sim-arm64.a" "$WORK/out/lib/libssh2-sim-arm64.a" "$VENDOR/lib/sim/"
# Naming Specification
mv "$VENDOR/lib/device/libcrypto-device.a" "$VENDOR/lib/device/libcrypto.a"
mv "$VENDOR/lib/device/libssl-device.a" "$VENDOR/lib/device/libssl.a"
mv "$VENDOR/lib/device/libssh2-device.a" "$VENDOR/lib/device/libssh2.a"
mv "$VENDOR/lib/sim/libcrypto-sim-arm64.a" "$VENDOR/lib/sim/libcrypto.a"
mv "$VENDOR/lib/sim/libssl-sim-arm64.a" "$VENDOR/lib/sim/libssl.a"
mv "$VENDOR/lib/sim/libssh2-sim-arm64.a" "$VENDOR/lib/sim/libssh2.a"

echo "== 完成 =="
ls -la "$VENDOR/lib/device" "$VENDOR/lib/sim"
file "$VENDOR/lib/device/libssh2.a" "$VENDOR/lib/sim/libssh2.a"
