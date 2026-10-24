# GMP 6.2.1 + MPFR 4.2.2 para Android (mesmas versões do Orca). CGAL precisa
# deles para o kernel exato com raiz quadrada usado por MeshBoolean.cpp.
# Autotools: usa o wrapper <triple><api>-clang do NDK, que já embute --target.
# DOWNLOAD_EXTRACT_TIMESTAMP ON: mantém as datas do tarball; sem isso o make
# acha configure/Makefile.in/*.info desatualizados e chama autoconf/makeinfo.

string(REGEX REPLACE "^android-" "" _gmp_api "${ANDROID_PLATFORM}")
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(_gmp_host aarch64-linux-android)
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(_gmp_host x86_64-linux-android)
else ()
    message(FATAL_ERROR "ABI não suportada para GMP: ${ANDROID_ABI}")
endif ()
set(_gmp_bin "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
set(_gmp_env
    "CC=${_gmp_bin}/${_gmp_host}${_gmp_api}-clang"
    "CXX=${_gmp_bin}/${_gmp_host}${_gmp_api}-clang++"
    "AR=${_gmp_bin}/llvm-ar"
    "RANLIB=${_gmp_bin}/llvm-ranlib"
    "STRIP=${_gmp_bin}/llvm-strip"
    "CFLAGS=-O2 -DNDEBUG -fPIC"
    "CXXFLAGS=-O2 -DNDEBUG -fPIC")

# O patch 0001-GMP_GCC15 do Orca corrige um teste do configure só no GCC 15;
# não se aplica ao clang do NDK.
ExternalProject_Add(dep_GMP
    EXCLUDE_FROM_ALL ON
    URL https://github.com/SoftFever/OrcaSlicer_deps/releases/download/gmp-6.2.1/gmp-6.2.1.tar.bz2
    URL_HASH SHA256=eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/GMP
    DOWNLOAD_EXTRACT_TIMESTAMP ON
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${_gmp_env}
        ./configure --host=${_gmp_host} --enable-shared=no --enable-static=yes --enable-cxx=yes "--prefix=${DESTDIR}"
    BUILD_COMMAND make -j${NPROC}
    INSTALL_COMMAND make install
)

ExternalProject_Add(dep_MPFR
    EXCLUDE_FROM_ALL ON
    URL https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.2.tar.bz2
        https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2
    URL_HASH SHA256=9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b
    DOWNLOAD_DIR ${DEP_DOWNLOAD_DIR}/MPFR
    DOWNLOAD_EXTRACT_TIMESTAMP ON
    BUILD_IN_SOURCE ON
    CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ${_gmp_env}
        ./configure --host=${_gmp_host} --enable-shared=no --enable-static=yes "--prefix=${DESTDIR}" "--with-gmp=${DESTDIR}"
    BUILD_COMMAND make -j${NPROC}
    INSTALL_COMMAND make install
    DEPENDS dep_GMP
)
