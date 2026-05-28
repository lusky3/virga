#!/usr/bin/env bash
# Cross-compile rclone as a standalone Android executable named librclone.so
# for each target ABI. Android only extracts files matching lib*.so from
# lib/<abi>/, so we exploit that to ship a real ELF executable we exec directly.
#
# Prerequisites: Go (pinned), Android NDK. See rclone-build/README.md.
set -euo pipefail

RCLONE_VERSION="${RCLONE_VERSION:-v1.69.1}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
MIN_SDK="${MIN_SDK:-26}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
NDK="$ANDROID_SDK/ndk/$NDK_VERSION"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
BUILD_DIR="${BUILD_DIR:-$REPO_ROOT/.rclone-build}"
# jniLibs of the rclone core module — packaged into the APK per-ABI.
OUT_BASE="$REPO_ROOT/core/rclone/src/main/jniLibs"

if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "ERROR: NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

# Map Android ABI -> Go arch + NDK clang target triple.
declare -A GOARCH=( [arm64-v8a]=arm64 [armeabi-v7a]=arm [x86_64]=amd64 )
declare -A GOARM=( [armeabi-v7a]=7 )
declare -A CC_PREFIX=(
  [arm64-v8a]=aarch64-linux-android
  [armeabi-v7a]=armv7a-linux-androideabi
  [x86_64]=x86_64-linux-android
)

ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

mkdir -p "$BUILD_DIR"
if [[ ! -d "$BUILD_DIR/rclone" ]]; then
  echo ">> Cloning rclone $RCLONE_VERSION"
  git clone --depth 1 --branch "$RCLONE_VERSION" https://github.com/rclone/rclone.git "$BUILD_DIR/rclone"
fi

cd "$BUILD_DIR/rclone"
VERSION_TAG="$(git describe --tags 2>/dev/null || echo "$RCLONE_VERSION")"

for abi in $ABIS; do
  arch="${GOARCH[$abi]}"
  cc="$TOOLCHAIN/${CC_PREFIX[$abi]}${MIN_SDK}-clang"
  outdir="$OUT_BASE/$abi"
  mkdir -p "$outdir"
  echo ">> Building rclone for $abi (GOARCH=$arch, CC=$(basename "$cc"))"
  env \
    GOOS=android GOARCH="$arch" ${GOARM[$abi]:+GOARM=${GOARM[$abi]}} \
    CGO_ENABLED=1 CC="$cc" \
    go build \
      -tags "noselfupdate" \
      -trimpath \
      -ldflags "-s -w -X github.com/rclone/rclone/fs.Version=$VERSION_TAG-virga" \
      -o "$outdir/librclone.so" \
      .
  echo "   -> $outdir/librclone.so ($(du -h "$outdir/librclone.so" | cut -f1))"
done

echo ">> Done. Built ABIs: $ABIS at rclone $VERSION_TAG"
