#!/usr/bin/env bash
# Compila as dependências nativas de libslic3r para Android.
# Resultado em native/deps/out-android-<abi> (prefixo CMake). Demorado na primeira vez.
# ABI vem de ANDROID_ABI (padrão arm64-v8a; x86_64 para o emulador).
#
# Uso: [ANDROID_ABI=x86_64] scripts/build-deps.sh [alvo ...]
#   sem argumentos: constrói tudo (alvo "deps")
#   com argumentos: só os alvos dados, ex.: scripts/build-deps.sh dep_Boost dep_TBB
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh"

BUILD_DIR="$ROOT/native/deps/build-$ANDROID_ABI"
mkdir -p "$BUILD_DIR"

"$CMAKE" -S "$ROOT/native/deps" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL="$ANDROID_STL" \
    -DANDROID_NDK="$ANDROID_NDK" \
    -DCMAKE_BUILD_TYPE=Release

# -j1 no nível de cima: cada dependência já paraleliza internamente.
"$CMAKE" --build "$BUILD_DIR" -j1 --target "${@:-deps}"
