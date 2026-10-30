# Superbuild das dependências de libslic3r para Android (arm64-v8a no app;
# x86_64 só para testes no emulador).
#
# Reaproveita as receitas de third_party/OrcaSlicer/deps/*/X.cmake, trocando a
# função orcaslicer_add_cmake_project por uma versão que repassa o toolchain do
# NDK (ANDROID_ABI, ANDROID_PLATFORM, ANDROID_STL) a cada ExternalProject.
#
# Uso: scripts/build-deps.sh (não invocar este CMake diretamente).
#
# Dependências omitidas de propósito (ver docs/arquitetura.md §2.3):
#   OCCT, OpenCV, CURL, GLEW, GLFW, OpenCSG, wxWidgets.
#   ZLIB vem do sysroot do NDK.

cmake_minimum_required(VERSION 3.20)
if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "4.0")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.5 CACHE STRING "" FORCE)
endif()
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()

project(android-slicer-deps)

if (NOT ANDROID)
    message(FATAL_ERROR "Este superbuild é só para Android. Use scripts/build-deps.sh.")
endif ()

include(ExternalProject)
include(ProcessorCount)
ProcessorCount(NPROC)
if (NPROC EQUAL 0)
    set(NPROC 1)
endif ()

set(ORCA_DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/OrcaSlicer/deps")
get_filename_component(ORCA_DEPS_DIR "${ORCA_DEPS_DIR}" ABSOLUTE)

set(DEP_DOWNLOAD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/DL_CACHE" CACHE PATH "Cache de tarballs baixados")
set(DESTDIR "${CMAKE_CURRENT_SOURCE_DIR}/out-android-${ANDROID_ABI}" CACHE PATH "Prefixo de instalação das deps")

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

find_package(Git REQUIRED)
set(PATCH_CMD ${GIT_EXECUTABLE} apply --verbose --ignore-space-change --whitespace=fix)

# Variáveis que as receitas do Orca esperam encontrar.
set(_is_multi FALSE)
set(DEP_DEBUG OFF)
set(IS_CROSS_COMPILE TRUE)
set(IN_GIT_REPO FALSE)
set(FLATPAK FALSE)
set(DEP_CMAKE_OPTS "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")
set(ZLIB_PKG "")
set(FREETYPE_PKG "dep_FREETYPE")

# Argumentos do toolchain Android repassados a todo ExternalProject CMake.
set(ANDROID_CMAKE_ARGS
    -DCMAKE_MAKE_PROGRAM:FILEPATH=${CMAKE_MAKE_PROGRAM}
    -DCMAKE_TOOLCHAIN_FILE:FILEPATH=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI:STRING=${ANDROID_ABI}
    -DANDROID_PLATFORM:STRING=${ANDROID_PLATFORM}
    -DANDROID_STL:STRING=${ANDROID_STL}
    -DANDROID_NDK:PATH=${ANDROID_NDK}
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE:STRING=BOTH
    -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY:STRING=BOTH
    -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE:STRING=BOTH
)

function(orcaslicer_add_cmake_project projectname)
    cmake_parse_arguments(P_ARGS "" "INSTALL_DIR;BUILD_COMMAND;INSTALL_COMMAND" "CMAKE_ARGS" ${ARGN})

    ExternalProject_Add(
        dep_${projectname}
        EXCLUDE_FROM_ALL    ON
        INSTALL_DIR         ${DESTDIR}
        DOWNLOAD_DIR        ${DEP_DOWNLOAD_DIR}/${projectname}
        CMAKE_ARGS
            -DCMAKE_POLICY_VERSION_MINIMUM=3.5
            -DCMAKE_BUILD_TYPE:STRING=${CMAKE_BUILD_TYPE}
            -DCMAKE_INSTALL_PREFIX:STRING=${DESTDIR}
            -DCMAKE_MODULE_PATH:STRING=${ORCA_DEPS_DIR}/../cmake/modules
            -DCMAKE_PREFIX_PATH:STRING=${DESTDIR}
            -DCMAKE_FIND_ROOT_PATH:STRING=${DESTDIR}
            -DCMAKE_DEBUG_POSTFIX:STRING=d
            -DBUILD_SHARED_LIBS:BOOL=OFF
            ${ANDROID_CMAKE_ARGS}
            ${DEP_CMAKE_OPTS}
            ${P_ARGS_CMAKE_ARGS}
        ${P_ARGS_UNPARSED_ARGUMENTS}
        BUILD_COMMAND ${CMAKE_COMMAND} --build . --config Release -- -j${NPROC}
        INSTALL_COMMAND ${CMAKE_COMMAND} --build . --target install --config Release
    )
endfunction()

include(${CMAKE_CURRENT_SOURCE_DIR}/GMP-android.cmake)

set(DEP_Boost_COMPONENTS system iostreams filesystem thread log locale regex date_time)
include(${ORCA_DEPS_DIR}/Boost/Boost.cmake)
include(${ORCA_DEPS_DIR}/Cereal/Cereal.cmake)
include(${ORCA_DEPS_DIR}/Qhull/Qhull.cmake)
include(${ORCA_DEPS_DIR}/TBB/TBB.cmake)
include(${ORCA_DEPS_DIR}/Blosc/Blosc.cmake)
include(${ORCA_DEPS_DIR}/OpenEXR/OpenEXR.cmake)
include(${ORCA_DEPS_DIR}/OpenVDB/OpenVDB.cmake)
include(${ORCA_DEPS_DIR}/Eigen/Eigen.cmake)
include(${ORCA_DEPS_DIR}/CGAL/CGAL.cmake)
include(${ORCA_DEPS_DIR}/NLopt/NLopt.cmake)
include(${ORCA_DEPS_DIR}/libnoise/libnoise.cmake)
include(${ORCA_DEPS_DIR}/EXPAT/EXPAT.cmake)
include(${ORCA_DEPS_DIR}/PNG/PNG.cmake)
include(${ORCA_DEPS_DIR}/JPEG/JPEG.cmake)
include(${ORCA_DEPS_DIR}/FREETYPE/FREETYPE.cmake)
include(${CMAKE_CURRENT_SOURCE_DIR}/OpenSSL-android.cmake)
# Modelos .drc (os de calibração do Orca são todos Draco).
include(${ORCA_DEPS_DIR}/Draco/Draco.cmake)

add_custom_target(deps ALL DEPENDS
    dep_Boost
    dep_Cereal
    dep_Qhull
    dep_TBB
    dep_Blosc
    dep_OpenEXR
    dep_OpenVDB
    dep_Eigen
    dep_GMP
    dep_MPFR
    dep_CGAL
    dep_NLopt
    dep_libnoise
    dep_EXPAT
    dep_PNG
    dep_JPEG
    dep_FREETYPE
    dep_OpenSSL
    dep_Draco
)
