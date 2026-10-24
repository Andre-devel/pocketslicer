# Variáveis de ambiente compartilhadas pelos scripts de build nativo.
# Pode ser sobrescrito exportando as variáveis antes de chamar os scripts.

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
export ANDROID_NDK="${ANDROID_NDK:-$ANDROID_SDK_ROOT/ndk/28.2.13676358}"
export ANDROID_ABI="${ANDROID_ABI:-arm64-v8a}"
export ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-29}"
export ANDROID_STL="${ANDROID_STL:-c++_static}"

CMAKE_DIR="${CMAKE_DIR:-$ANDROID_SDK_ROOT/cmake/3.31.6/bin}"
export CMAKE="${CMAKE:-$CMAKE_DIR/cmake}"
export NINJA="${NINJA:-$CMAKE_DIR/ninja}"

export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/corretto-17.0.20.1}"

for p in "$ANDROID_NDK" "$CMAKE" "$NINJA"; do
    if [ ! -e "$p" ]; then
        echo "env.sh: não encontrado: $p" >&2
        exit 1
    fi
done
