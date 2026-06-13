#!/usr/bin/env bash
# Cross-compile rclone as a standalone Android executable named librclone.so
# for each target ABI. Android only extracts files matching lib*.so from
# lib/<abi>/, so we exploit that to ship a real ELF executable we exec directly.
#
# Prerequisites: Go (pinned), Android NDK. See rclone-build/README.md.
set -euo pipefail

# Capture any caller-supplied overrides before sourcing the defaults file
# (sourcing assigns the file's values, which would otherwise clobber the env).
_ENV_RCLONE_VERSION="${RCLONE_VERSION:-}"
_ENV_RCLONE_COMMIT="${RCLONE_COMMIT:-}"
_ENV_NDK_VERSION="${NDK_VERSION:-}"
# Single source of truth for versions (shared with both CI workflows and the
# Gradle build). The file provides defaults; an explicit env override wins.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/rclone-versions.env
. "$SCRIPT_DIR/rclone-versions.env"

RCLONE_VERSION="${_ENV_RCLONE_VERSION:-$RCLONE_VERSION}"
# Expected commit the tag must resolve to — a tag is mutable (can be re-pointed),
# so we verify the checked-out HEAD against this pinned commit before building.
# Update alongside RCLONE_VERSION (git ls-remote …/rclone refs/tags/<ver>^{}).
RCLONE_COMMIT="${_ENV_RCLONE_COMMIT:-$RCLONE_COMMIT}"
NDK_VERSION="${_ENV_NDK_VERSION:-$NDK_VERSION}"
MIN_SDK="${MIN_SDK:-26}"

# The Go stdlib is statically linked into the shipped librclone.so, so its
# vulnerabilities (crypto/tls, crypto/x509, net, net/http resource-exhaustion /
# double-free / infinite-loop CVEs) are fixed ONLY by the compiler version, not by
# the rclone tag. Refuse to build with a toolchain older than the first patched
# release so a stale Go can't bake known-vulnerable stdlib into the binary.
MIN_GO_VERSION="${MIN_GO_VERSION:-1.25.10}"
GO_VERSION="$(go env GOVERSION 2>/dev/null | sed 's/^go//')"
if [[ -z "$GO_VERSION" ]]; then
  echo "ERROR: Go not found on PATH (need >= $MIN_GO_VERSION)." >&2
  exit 1
fi
if [[ "$(printf '%s\n%s\n' "$MIN_GO_VERSION" "$GO_VERSION" | sort -V | head -n1)" != "$MIN_GO_VERSION" ]]; then
  echo "ERROR: Go $GO_VERSION is older than the required $MIN_GO_VERSION." >&2
  echo "       Older toolchains link known-vulnerable Go stdlib into librclone.so. Upgrade Go." >&2
  exit 1
fi
echo ">> Go $GO_VERSION (>= $MIN_GO_VERSION) OK"

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

# Records the rclone version baked into the current jniLibs outputs. The CI
# cache restores both .rclone-build and jniLibs, so without this gate the
# ~5-min cross-compile re-runs every job and overwrites the restored binaries.
STAMP_FILE="$OUT_BASE/.rclone-version"

# Up-to-date skip: bail before cloning when every requested ABI's librclone.so
# already exists AND the stamp records the current RCLONE_VERSION. Set
# FORCE_REBUILD=1 to bypass (e.g. after changing build flags without a bump).
if [[ "${FORCE_REBUILD:-}" != "1" ]]; then
  all_present=1
  for abi in $ABIS; do
    [[ -f "$OUT_BASE/$abi/librclone.so" ]] || { all_present=0; break; }
  done
  if [[ "$all_present" == "1" && -f "$STAMP_FILE" \
        && "$(cat "$STAMP_FILE")" == "$RCLONE_VERSION" ]]; then
    echo ">> librclone.so up to date for $RCLONE_VERSION (all ABIs present), skipping"
    exit 0
  fi
fi

mkdir -p "$BUILD_DIR"
if [[ ! -d "$BUILD_DIR/rclone" ]]; then
  echo ">> Cloning rclone $RCLONE_VERSION"
  git clone --depth 1 --branch "$RCLONE_VERSION" https://github.com/rclone/rclone.git "$BUILD_DIR/rclone"
fi

cd "$BUILD_DIR/rclone"
# Integrity gate: the build compiles the WORKING TREE, not a commit object, so
# verifying HEAD alone is not enough — a cached clone could be checked out at the
# pinned commit yet have a dirty/tampered tree. We therefore require all three:
#   1. HEAD == the pinned commit (catches a moved tag / wrong checkout),
#   2. no tracked-file modifications  (git diff --quiet HEAD),
#   3. no untracked/extra files       (empty git status --porcelain),
# so what we compile is byte-for-byte the pinned commit's tree.
ACTUAL_COMMIT="$(git rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$RCLONE_COMMIT" ]]; then
  echo "ERROR: rclone HEAD $ACTUAL_COMMIT != pinned $RCLONE_COMMIT for $RCLONE_VERSION" >&2
  echo "       The tag may have moved, or the cached clone is stale/tampered." >&2
  exit 1
fi
if ! git diff --quiet HEAD || [[ -n "$(git status --porcelain)" ]]; then
  echo "ERROR: cached rclone clone at $BUILD_DIR/rclone is dirty (modified or" >&2
  echo "       untracked files). Refusing to build a tampered tree." >&2
  echo "       Delete $BUILD_DIR/rclone and re-run for a clean re-clone." >&2
  exit 1
fi
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
      -ldflags "-s -w -X github.com/rclone/rclone/fs.Version=$VERSION_TAG-virga -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
      -o "$outdir/librclone.so" \
      .
  echo "   -> $outdir/librclone.so ($(du -h "$outdir/librclone.so" | cut -f1))"
done

# Stamp the version the outputs were built from, so the next run (incl. a
# cache-restored CI job) can skip the cross-compile when nothing changed.
printf '%s\n' "$RCLONE_VERSION" > "$STAMP_FILE"

echo ">> Done. Built ABIs: $ABIS at rclone $VERSION_TAG"
