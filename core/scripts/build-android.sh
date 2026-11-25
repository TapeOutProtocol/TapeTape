#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=out/android
rm -rf "$OUT"
mkdir -p "$OUT/jniLibs" "$OUT/kotlin"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$HOME"/Library/Android/sdk/ndk/* | sort -V | tail -1)
  export ANDROID_NDK_HOME
fi
command -v cargo-ndk >/dev/null || cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android >/dev/null

cargo ndk -t armeabi-v7a -t arm64-v8a -t x86 -t x86_64 -o "$OUT/jniLibs" build -p tapetape-core --release
cargo build -p tapetape-core --release

cargo run -q -p uniffi-bindgen -- generate \
  --library target/release/libtapetape_core.dylib \
  --language kotlin --no-format --out-dir "$OUT/kotlin"

find "$OUT/jniLibs" -name '*.so'
echo "kotlin: $(find "$OUT/kotlin" -name '*.kt')"
