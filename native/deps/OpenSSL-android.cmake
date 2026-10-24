# OpenSSL 1.1.1w para Android (mesma versão que o Orca usa).
# Usa o script Configure do próprio OpenSSL com o target android-<arch>; o
# Configure localiza o clang do NDK via ANDROID_NDK_ROOT + PATH.

set(_ossl_toolchain_bin "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
string(REGEX REPLACE "^android-" "" _ossl_api "${ANDROID_PLATFORM}")
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(_ossl_target android-arm64)
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(_ossl_target android-x86_64)
else ()
    message(FATAL_ERROR "ABI não suportada para OpenSSL: ${ANDROID_ABI}")
endif ()

ExternalProject_Add(dep_OpenSSL
    EXCLUDE_FROM_ALL ON
    URL "https://github.com/openssl/openssl/archive/OpenSSL_1_1_1w.tar.gz"
    URL_HASH SHA256=2130E8C2FB3B79D1086186F78E59E8BC8D1A6AEDF17AB3907F4CB9AE20918C41
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/OpenSSL
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env
            "ANDROID_NDK_ROOT=${ANDROID_NDK}"
            "PATH=${_ossl_toolchain_bin}:$ENV{PATH}"
        ./Configure ${_ossl_target} -D__ANDROID_API__=${_ossl_api}
            "--prefix=${DESTDIR}" "--openssldir=${DESTDIR}"
            no-shared no-asm no-ssl3-method no-dynamic-engine no-tests
    BUILD_COMMAND ${CMAKE_COMMAND} -E env
            "ANDROID_NDK_ROOT=${ANDROID_NDK}"
            "PATH=${_ossl_toolchain_bin}:$ENV{PATH}"
        make -j${NPROC} build_libs
    INSTALL_COMMAND ${CMAKE_COMMAND} -E env
            "ANDROID_NDK_ROOT=${ANDROID_NDK}"
            "PATH=${_ossl_toolchain_bin}:$ENV{PATH}"
        make install_dev
)
