#!/bin/bash
# Build the Eternal Terminal client (`et`) for Android arm64-v8a.
# Prerequisites: Android NDK, autotools, cmake, pkg-config, host protoc 3.21.x
#
# Usage: ./build-et.sh [NDK_PATH]
#
# Cross-compiles the ET client + its static deps (protobuf, OpenSSL,
# libsodium) and places the stripped binary at:
#   androidApp/src/main/jniLibs/arm64-v8a/libet.so
#
# It rides the app's Cloudflare tunnel the same way mosh-client is packaged
# (executed via ProcessBuilder from jniLibs), but unlike mosh it resumes over
# TCP — so it survives a Starlink egress-IP change without a full tmux redraw.
#
# Dep notes:
#   * protobuf is PINNED to 3.21.12 (matches the host protoc and, crucially,
#     is pre-Abseil: ET only pulls the Abseil/utf8_range mess in for
#     protobuf >= 4, so this pin sidesteps cross-compiling Abseil entirely).
#   * VCPKG + Sentry + tests are disabled; ET's CMake is already Android-aware
#     (it drops utempter/SELinux/stacktrace on ANDROID), so only the `et`
#     target is built — the server binaries are never linked.

set -e

NDK="${1:-$ANDROID_NDK_HOME}"
if [ -z "$NDK" ]; then
    echo "Usage: $0 <NDK_PATH>"
    echo "Or set ANDROID_NDK_HOME environment variable"
    exit 1
fi

API=28  # match build-mosh (getrandom() etc.)
TARGET=aarch64-linux-android
ABI=arm64-v8a
ET_TAG=et-v7.0.0
PROTOBUF_TAG=v3.21.12
OPENSSL_TAG=openssl-3.2.0
SODIUM_TAG=1.0.20-RELEASE

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"
export ANDROID_NDK_ROOT="$NDK"
export CC="${TARGET}${API}-clang"
export CXX="${TARGET}${API}-clang++"
export AR="llvm-ar"
export RANLIB="llvm-ranlib"
export STRIP="llvm-strip"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKDIR="$(pwd)/build-et-tmp"
PREFIX="$WORKDIR/install"
mkdir -p "$WORKDIR" "$PREFIX"

PROTOC="$(command -v protoc)"
if [ -z "$PROTOC" ]; then
    echo "host protoc not found (need 3.21.x to match the pinned libprotobuf)"; exit 1
fi
echo "Using host protoc: $PROTOC ($($PROTOC --version))"

echo "=== [1/4] protobuf $PROTOBUF_TAG (static, target) ==="
cd "$WORKDIR"
if [ ! -d protobuf ]; then
    git clone --depth 1 --branch "$PROTOBUF_TAG" https://github.com/protocolbuffers/protobuf.git
fi
cd protobuf
if [ ! -f "$PREFIX/lib/libprotobuf.a" ]; then
    git submodule update --init --recursive
    ./autogen.sh
    ./configure --host=$TARGET --prefix="$PREFIX" \
        --disable-shared --enable-static \
        --with-protoc="$PROTOC" \
        CFLAGS="-fPIC" CXXFLAGS="-fPIC"
    make -j"$(nproc)" install
fi

echo "=== [2/4] OpenSSL $OPENSSL_TAG (static, target) ==="
cd "$WORKDIR"
if [ ! -d openssl ]; then
    git clone --depth 1 --branch "$OPENSSL_TAG" https://github.com/openssl/openssl.git
fi
cd openssl
if [ ! -f "$PREFIX/lib/libcrypto.a" ]; then
    ./Configure android-arm64 --prefix="$PREFIX" no-shared no-tests no-apps
    # Single-threaded: OpenSSL parallel make races on .d.tmp files
    make -j1 install_sw
fi

echo "=== [3/4] libsodium $SODIUM_TAG (static, target) ==="
cd "$WORKDIR"
if [ ! -d libsodium ]; then
    git clone --depth 1 --branch "$SODIUM_TAG" https://github.com/jedisct1/libsodium.git
fi
cd libsodium
if [ ! -f "$PREFIX/lib/libsodium.a" ]; then
    ./autogen.sh -s
    ./configure --host=$TARGET --prefix="$PREFIX" \
        --disable-shared --enable-static \
        CFLAGS="-fPIC"
    make -j"$(nproc)" install
fi

echo "=== [4/4] Eternal Terminal client ($ET_TAG) ==="
cd "$WORKDIR"
if [ ! -d EternalTerminal ]; then
    git clone --depth 1 --branch "$ET_TAG" https://github.com/MisterTea/EternalTerminal.git
fi
cd EternalTerminal
# Only the submodules the `et` client actually needs — NOT vcpkg (huge) or
# sentry-native (disabled). sanitizers-cmake is find_package(REQUIRED).
git submodule update --init --depth 1 \
    external/easyloggingpp \
    external/cxxopts \
    external/cpp-httplib \
    external/json \
    external/simpleini \
    external/PlatformFolders \
    external/msgpack-c \
    external/ThreadPool \
    external/base64 \
    external/sole \
    external/sanitizers-cmake \
    external/UniversalStacktrace

# --- ET client patch: --idpasskey (skip ssh bootstrap) + --pty (self-PTY) ---
# 1. --idpasskey: the app runs `etterminal` over its own in-process SSH-over-
#    Cloudflare channel and parses IDPASSKEY, then passes it so the client
#    connects the data channel directly — Android has no ssh binary for ET.
# 2. --pty: the client forkpty's itself so it runs with a real controlling TTY
#    even when launched from the app's plain pipe-based Process (ET needs a PTY
#    for input forwarding + a valid window size; plain pipes break the shell).
# Idempotent: --check fails once already applied, so the apply is skipped.
if git apply --check "$SCRIPT_DIR/patches/et-client.patch" 2>/dev/null; then
    git apply "$SCRIPT_DIR/patches/et-client.patch"
fi

# --- Android portability shims (bionic lacks a few glibc bits ET assumes) ---
# bionic defines no _PATH_TMP (Android has no /tmp), but ET's GetTempDirectory()
# needs a default. The app passes --tmpdir (its own cache dir) at runtime, so
# this compile-time value is only a fallback. Idempotent (marker-guarded).
if ! grep -q 'CR_ANDROID_PATCH' src/base/Headers.hpp; then
    sed -i 's|^inline string GetTempDirectory() {|// CR_ANDROID_PATCH\n#ifndef _PATH_TMP\n#define _PATH_TMP "/data/local/tmp/"\n#endif\ninline string GetTempDirectory() {|' src/base/Headers.hpp
fi
# NDK 27 dropped the libutil.so stub (openpty/forkpty live in bionic libc), so
# ET's `util` link fails; and protobuf's Android log handler needs -llog
# (__android_log_write). Swap `util` -> `log` in the ANDROID link list.
# Inherently idempotent: after the first pass the `util)` form is gone.
sed -i 's|set(CORE_LIBRARIES OpenSSL::SSL ZLIB::ZLIB util)|set(CORE_LIBRARIES OpenSSL::SSL ZLIB::ZLIB log)|' CMakeLists.txt

rm -rf build && mkdir build && cd build
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release \
    -DDISABLE_VCPKG=ON \
    -DDISABLE_SENTRY=ON \
    -DDISABLE_TELEMETRY=ON \
    -DBUILD_TESTING=OFF \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    -DOPENSSL_ROOT_DIR="$PREFIX" \
    -DOPENSSL_USE_STATIC_LIBS=TRUE \
    -DProtobuf_INCLUDE_DIR="$PREFIX/include" \
    -DProtobuf_LIBRARY="$PREFIX/lib/libprotobuf.a" \
    -DProtobuf_PROTOC_EXECUTABLE="$PROTOC" \
    -Dsodium_USE_STATIC_LIBS=ON \
    -Dsodium_INCLUDE_DIR="$PREFIX/include" \
    -Dsodium_LIBRARY_RELEASE="$PREFIX/lib/libsodium.a" \
    -Dsodium_LIBRARY_DEBUG="$PREFIX/lib/libsodium.a"

cmake --build . --target et -j"$(nproc)"

echo "=== Installing ==="
OUTPUT="$SCRIPT_DIR/androidApp/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUTPUT"
$STRIP et -o "$OUTPUT/libet.so"

ls -la "$OUTPUT/libet.so"
file "$OUTPUT/libet.so"
echo "=== Done! et client built at $OUTPUT/libet.so ==="
