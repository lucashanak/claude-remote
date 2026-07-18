#!/bin/bash
# Build the patched Eternal Terminal client for the HOST desktop platform
# (Linux x86_64 or macOS arm64/x86_64), STATICALLY linked against protobuf /
# OpenSSL / libsodium so the bundled binary is portable across distros and Mac
# versions (a system-deps dynamic build would break on a machine whose
# libprotobuf.so differs from the build box).
#
# Usage: ./build-et-desktop.sh <output-binary-path> [arch]
#   arch (macOS only): arm64 | x86_64 — cross-arch build via -arch. Default = host.
#
# Same recipe as build-et.sh minus the Android NDK: protobuf PINNED to 3.21.12
# (pre-Abseil), VCPKG/Sentry/tests off, patches/et-client.patch applied, only
# the `et` target built.

set -e
OUT="${1:?usage: build-et-desktop.sh <output-binary-path> [arch]}"
ARCH="${2:-}"
ET_TAG=et-v7.0.0
PROTOBUF_TAG=v3.21.12
OPENSSL_TAG=openssl-3.2.0
SODIUM_TAG=1.0.20-RELEASE

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(pwd)/build-et-desktop-tmp${ARCH:+-$ARCH}"
PREFIX="$WORK/install"
mkdir -p "$WORK" "$PREFIX"
NPROC=$( (command -v nproc >/dev/null && nproc) || sysctl -n hw.ncpu )
UNAME=$(uname -s)

# Host protoc must match the pinned libprotobuf (3.21.12). System packages vary
# (brew ships protobuf 4+), so download the exact prebuilt protoc 21.12 for the
# BUILD host's arch (protoc runs on the build machine, not the target).
HOSTM=$(uname -m)
case "$UNAME/$HOSTM" in
    Linux/x86_64)   PROTOC_ZIP=protoc-21.12-linux-x86_64.zip ;;
    Linux/aarch64)  PROTOC_ZIP=protoc-21.12-linux-aarch_64.zip ;;
    Darwin/arm64)   PROTOC_ZIP=protoc-21.12-osx-aarch_64.zip ;;
    Darwin/x86_64)  PROTOC_ZIP=protoc-21.12-osx-x86_64.zip ;;
    *) echo "unsupported build host: $UNAME/$HOSTM"; exit 1 ;;
esac
if [ ! -x "$WORK/protoc/bin/protoc" ]; then
    curl -fsSL -o "$WORK/protoc.zip" \
        "https://github.com/protocolbuffers/protobuf/releases/download/v21.12/$PROTOC_ZIP"
    mkdir -p "$WORK/protoc" && unzip -oq "$WORK/protoc.zip" -d "$WORK/protoc"
fi
PROTOC="$WORK/protoc/bin/protoc"
echo "Host: $UNAME  target-arch=${ARCH:-native}  protoc=$($PROTOC --version)"

# macOS cross/arch flags
MACFLAGS=""
if [ "$UNAME" = "Darwin" ] && [ -n "$ARCH" ]; then
    MACFLAGS="-arch $ARCH"
fi
export CFLAGS="$MACFLAGS ${CFLAGS:-}"
export CXXFLAGS="$MACFLAGS ${CXXFLAGS:-}"

echo "=== [1/4] protobuf $PROTOBUF_TAG (static) ==="
cd "$WORK"
[ -d protobuf ] || git clone --depth 1 --branch "$PROTOBUF_TAG" https://github.com/protocolbuffers/protobuf.git
cd protobuf
if [ ! -f "$PREFIX/lib/libprotobuf.a" ]; then
    git submodule update --init --recursive
    ./autogen.sh
    ./configure --prefix="$PREFIX" --disable-shared --enable-static \
        --with-protoc="$PROTOC" CFLAGS="-fPIC $MACFLAGS" CXXFLAGS="-fPIC $MACFLAGS"
    make -j"$NPROC" install
fi

echo "=== [2/4] OpenSSL $OPENSSL_TAG (static) ==="
cd "$WORK"
[ -d openssl ] || git clone --depth 1 --branch "$OPENSSL_TAG" https://github.com/openssl/openssl.git
cd openssl
if [ ! -f "$PREFIX/lib/libcrypto.a" ]; then
    if [ "$UNAME" = "Darwin" ]; then
        OSSL_TARGET=$([ "$ARCH" = "x86_64" ] && echo darwin64-x86_64 || echo darwin64-arm64)
        ./Configure "$OSSL_TARGET" --prefix="$PREFIX" no-shared no-tests no-apps
    else
        ./config --prefix="$PREFIX" no-shared no-tests no-apps
    fi
    make -j1 install_sw
fi

echo "=== [3/4] libsodium $SODIUM_TAG (static) ==="
cd "$WORK"
[ -d libsodium ] || git clone --depth 1 --branch "$SODIUM_TAG" https://github.com/jedisct1/libsodium.git
cd libsodium
if [ ! -f "$PREFIX/lib/libsodium.a" ]; then
    ./autogen.sh -s
    ./configure --prefix="$PREFIX" --disable-shared --enable-static \
        CFLAGS="-fPIC $MACFLAGS" CXXFLAGS="-fPIC $MACFLAGS"
    make -j"$NPROC" install
fi

echo "=== [4/4] Eternal Terminal client ($ET_TAG) ==="
cd "$WORK"
[ -d EternalTerminal ] || git clone --depth 1 --branch "$ET_TAG" https://github.com/MisterTea/EternalTerminal.git
cd EternalTerminal
git submodule update --init --depth 1 \
    external/easyloggingpp external/cxxopts external/cpp-httplib external/json \
    external/simpleini external/PlatformFolders external/msgpack-c \
    external/ThreadPool external/base64 external/sole external/sanitizers-cmake \
    external/UniversalStacktrace
git apply --check "$SCRIPT_DIR/patches/et-client.patch" 2>/dev/null && \
    git apply "$SCRIPT_DIR/patches/et-client.patch" || true

rm -rf build && mkdir build && cd build
# Statically link libstdc++/libgcc on Linux so the binary doesn't depend on the
# build box's GCC runtime; macOS links the system libc++ (always present).
EXE_LINK=""
[ "$UNAME" = "Linux" ] && EXE_LINK="-static-libgcc -static-libstdc++"
cmake .. \
    -DCMAKE_BUILD_TYPE=Release \
    -DDISABLE_VCPKG=ON -DDISABLE_SENTRY=ON -DDISABLE_TELEMETRY=ON -DBUILD_TESTING=OFF \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    -DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_USE_STATIC_LIBS=TRUE \
    -DProtobuf_INCLUDE_DIR="$PREFIX/include" \
    -DProtobuf_LIBRARY="$PREFIX/lib/libprotobuf.a" \
    -DProtobuf_PROTOC_EXECUTABLE="$PROTOC" \
    -Dsodium_USE_STATIC_LIBS=ON \
    -Dsodium_INCLUDE_DIR="$PREFIX/include" \
    -Dsodium_LIBRARY_RELEASE="$PREFIX/lib/libsodium.a" \
    -Dsodium_LIBRARY_DEBUG="$PREFIX/lib/libsodium.a" \
    ${ARCH:+-DCMAKE_OSX_ARCHITECTURES=$ARCH} \
    -DCMAKE_EXE_LINKER_FLAGS="$EXE_LINK"

cmake --build . --target et -j"$NPROC"

mkdir -p "$(dirname "$OUT")"
cp et "$OUT"
strip "$OUT" 2>/dev/null || true
echo "=== Done: $OUT ==="
file "$OUT"
