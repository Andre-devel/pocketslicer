#!/usr/bin/env bash
# Compila libslic3r + slice_cli para Android (native/build-<abi>). Requer build-deps.sh antes.
# Uso: [ANDROID_ABI=x86_64] scripts/build-native.sh [opções do ninja ...]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

BUILD_DIR="$ROOT/native/build-$ANDROID_ABI"

"$CMAKE" -S "$ROOT/native" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL="$ANDROID_STL" \
    -DANDROID_NDK="$ANDROID_NDK" \
    -DCMAKE_BUILD_TYPE=Release

"$CMAKE" --build "$BUILD_DIR" -- "$@"
