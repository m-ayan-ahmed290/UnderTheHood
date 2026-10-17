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
  (cd "$SRC"; CGO_ENABLED=0 GOOS=android GOARCH="$ARCH" go build -trimpath -buildvcs=false -ldflags="-s -w -X github.com/rclone/rclone/fs.Version=${VERSION#v}" -o "$OUT/librclone.so" .)
done
cp "$SRC/COPYING" "$ROOT/docs/RCLONE-LICENSE.txt"
