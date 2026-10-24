# OpenSSL 3.5 (LTS) para Android. O Orca usa 1.1.1, cuja licença (OpenSSL/SSLeay) é
# tida como incompatível com a AGPL; a 3.x é Apache-2.0. libslic3r só usa MD5.
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
    URL "https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz"
    URL_HASH SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/OpenSSL
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env
            "ANDROID_NDK_ROOT=${ANDROID_NDK}"
            "PATH=${_ossl_toolchain_bin}:$ENV{PATH}"
        ./Configure ${_ossl_target} -D__ANDROID_API__=${_ossl_api}
            "--prefix=${DESTDIR}" "--openssldir=${DESTDIR}" --libdir=lib
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
