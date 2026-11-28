#!/bin/bash
# 获取 OpenCV Android SDK 构建依赖（仅 ARM64），放到 CMake 期望的路径。
# 在应用根目录（含 app/ 的那个目录）执行本脚本。
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP=$(mktemp -d)
echo "下载 OpenCV 4.9.0 Android SDK..."
curl -sSL --retry 3 -o "$TMP/opencv.zip" \
  "https://github.com/opencv/opencv/releases/download/4.9.0/opencv-4.9.0-android-sdk.zip"
unzip -q "$TMP/opencv.zip" -d "$TMP/sdk"
SRC="$TMP/sdk/OpenCV-android-sdk/sdk/native"

# CMake find_package(OpenCV) 配置与头文件
mkdir -p "$ROOT/app/opencv_sdk"
cp -r "$SRC/jni" "$ROOT/app/opencv_sdk/"
find "$ROOT/app/opencv_sdk/jni" -maxdepth 1 \( -name 'OpenCV-x86*' -o -name 'OpenCV-armeabi*' \) -delete
rm -rf "$ROOT/app/opencv_sdk/jni/abi-x86" "$ROOT/app/opencv_sdk/jni/abi-x86_64" "$ROOT/app/opencv_sdk/jni/abi-armeabi-v7a"

# OpenCVConfig.cmake 写死的 ${OpenCV_INSTALL_PATH}/sdk/native/{staticlibs,3rdparty,libs}
mkdir -p "$ROOT/app/sdk/native"
cp -r "$SRC/staticlibs" "$ROOT/app/sdk/native/"
cp -r "$SRC/3rdparty" "$ROOT/app/sdk/native/"
cp -r "$SRC/libs" "$ROOT/app/sdk/native/"
find "$ROOT/app/sdk/native" -type d \( -name x86 -o -name x86_64 -o -name armeabi-v7a \) -exec rm -rf {} + 2>/dev/null || true

# OpenCVConfig.cmake 从自身位置推导 install path 到 ${ROOT}/sdk/native，
# 而上面把库放在 ${ROOT}/app/sdk/native；建软链统一两处，避免 CMake 找不到静态库。
ln -sfn app/sdk "$ROOT/sdk"

rm -rf "$TMP"
echo "OpenCV 构建依赖就绪。现在可：cd $ROOT && gradle assembleRelease"
