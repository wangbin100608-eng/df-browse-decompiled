#!/bin/bash
# 交叉编译 Rust 核心为 Android ARM64 so，输出到 jniLibs。
# 需要：Rust + rustup target aarch64-linux-android + Android NDK 26。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_HOME:-/opt/android-sdk}/ndk/26.3.11579264"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=aarch64-linux-android34-clang
export CC_aarch64_linux_android=aarch64-linux-android34-clang
cd "$ROOT/rust-core"
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libdi_core.so \
   "$ROOT/app/src/main/jniLibs/arm64-v8a/"
echo "libdi_core.so 已更新"
