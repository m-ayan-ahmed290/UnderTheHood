#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../native/rclone"
: "${ANDROID_HOME:?Set ANDROID_HOME to your SDK}"
ndk="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
for abi in ${UTH_ABIS:-arm64-v8a x86_64}; do
 case "$abi" in
  arm64-v8a) arch=arm64; triple=aarch64-linux-android;;
  x86_64) arch=amd64; triple=x86_64-linux-android;;
  *) echo "Unsupported ABI: $abi" >&2; exit 1;;
 esac
 out="../../apps/android/src/main/jniLibs/$abi"
 mkdir -p "$out"
 CGO_ENABLED=1 GOOS=android GOARCH="$arch" CC="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${triple}29-clang" \
 CGO_LDFLAGS='-Wl,-z,max-page-size=16384' go build -mod=readonly -trimpath -buildmode=c-shared -ldflags='-s -w' -o "$out/libuth.so" .
 rm -f "$out/libuth.h"
done
