#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=out/ios
export IPHONEOS_DEPLOYMENT_TARGET=16.0
LIB=libtapetape_core.a
rm -rf "$OUT"
mkdir -p "$OUT/bindings" "$OUT/headers"

rustup target add aarch64-apple-ios aarch64-apple-ios-sim >/dev/null
cargo build -p tapetape-core --release
cargo build -p tapetape-core --release --target aarch64-apple-ios
cargo build -p tapetape-core --release --target aarch64-apple-ios-sim

cargo run -q -p uniffi-bindgen -- generate \
  --library target/release/libtapetape_core.dylib \
  --language swift --out-dir "$OUT/bindings"

cp "$OUT/bindings/tapetape_coreFFI.h" "$OUT/headers/"
cp "$OUT/bindings/tapetape_coreFFI.modulemap" "$OUT/headers/module.modulemap"

xcodebuild -create-xcframework \
  -library "target/aarch64-apple-ios/release/$LIB" -headers "$OUT/headers" \
  -library "target/aarch64-apple-ios-sim/release/$LIB" -headers "$OUT/headers" \
  -output "$OUT/TapeTapeCore.xcframework"

rm -rf "$OUT/headers"
echo "xcframework: $OUT/TapeTapeCore.xcframework"
echo "swift:       $OUT/bindings/tapetape_core.swift"
