#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=v1.75.2
# Go module checksums authenticate upstream source against sum.golang.org.
export GOTOOLCHAIN=auto
CACHE="${UTH_BUILD_CACHE:-$ROOT/.gradle/rclone-source}"
mkdir -p "$CACHE"
cd "$CACHE"
if [ ! -f go.mod ]; then go mod init dev.underthehood/rclone-build; fi
go mod download -json "github.com/rclone/rclone@$VERSION" > module.json
SRC=$(python3 -c 'import json; print(json.load(open("module.json"))["Dir"])')
for abi in "${@:-arm64-v8a}"; do
  case "$abi" in arm64-v8a) ARCH=arm64;; x86_64) ARCH=amd64;; *) exit 2;; esac
  OUT="$ROOT/apps/android/app/src/main/jniLibs/$abi"
  mkdir -p "$OUT"
  CGO=0
  if [ "$abi" = x86_64 ]; then
    CGO=1
    COMPILER=$(find "${ANDROID_HOME:?Android SDK required}/ndk" -path '*/bin/x86_64-linux-android29-clang' | sort | tail -1)
    test -n "$COMPILER" || { echo 'Android NDK compiler not found'; exit 1; }
    export CC="$COMPILER"
    export CGO_LDFLAGS='-Wl,-z,max-page-size=16384'
  fi
  (cd "$SRC"; CGO_ENABLED="$CGO" GOOS=android GOARCH="$ARCH" go build -trimpath -buildvcs=false -ldflags="-s -w -X github.com/rclone/rclone/fs.Version=${VERSION#v}" -o "$OUT/librclone.so" .)
done
mkdir -p "$ROOT/docs"
cp "$SRC/COPYING" "$ROOT/docs/RCLONE-LICENSE.txt"
