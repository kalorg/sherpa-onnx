#!/usr/bin/env bash
set -ex

# Build script for Samsung Galaxy S25 Ultra (Snapdragon 8 Elite / SM8750, arm64-v8a, Hexagon V79)
dir=$PWD/build-android-arm64-v8a
mkdir -p "$dir"
cd "$dir"

if [ -z "${ANDROID_NDK:-}" ]; then
  ANDROID_NDK=/opt/android-sdk/ndk/27.0.12077973
fi

if [ ! -d "$ANDROID_NDK" ]; then
  echo "Please set ANDROID_NDK before running this script"
  exit 1
fi

if [ -z "${QNN_SDK_ROOT:-}" ]; then
  echo "Please set QNN_SDK_ROOT to your QAIRT / QNN SDK directory"
  exit 1
fi

onnxruntime_version=${SHERPA_ONNX_ONNXRUNTIME_VERSION:-1.28.2}

if [ -z "${SHERPA_ONNXRUNTIME_LIB_DIR:-}" ] || [ -z "${SHERPA_ONNXRUNTIME_INCLUDE_DIR:-}" ]; then
  if [ ! -f "$onnxruntime_version/jni/arm64-v8a/libonnxruntime.so" ]; then
    mkdir -p "$onnxruntime_version"
    pushd "$onnxruntime_version"
    wget -c -q "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${onnxruntime_version}/onnxruntime-android-${onnxruntime_version}.zip"
    unzip "onnxruntime-android-${onnxruntime_version}.zip"
    rm "onnxruntime-android-${onnxruntime_version}.zip"
    popd
  fi
  export SHERPA_ONNXRUNTIME_LIB_DIR="$dir/$onnxruntime_version/jni/arm64-v8a/"
  export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$dir/$onnxruntime_version/headers/"
fi

cmake -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DSHERPA_ONNX_ENABLE_TTS=ON \
    -DSHERPA_ONNX_ENABLE_QNN=ON \
    -DSHERPA_ONNX_ENABLE_JNI=ON \
    -DSHERPA_ONNX_ENABLE_BINARY=OFF \
    -DSHERPA_ONNX_ENABLE_C_API=OFF \
    -DSHERPA_ONNX_ENABLE_PYTHON=OFF \
    -DSHERPA_ONNX_ENABLE_TESTS=OFF \
    -DSHERPA_ONNX_ENABLE_CHECK=OFF \
    -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF \
    -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF \
    -DCMAKE_INSTALL_PREFIX=./install \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM=android-24 ..

make -j"$(nproc)" sherpa-onnx-jni
"$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  -o ../android/SherpaOnnxTts/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so \
  lib/libsherpa-onnx-jni.so
cp -fv ../android/SherpaOnnxTts/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so \
  ../android/SherpaOnnxTtsEngine/app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
