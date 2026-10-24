# libslic3r (OrcaSlicer) para Android + bridge JNI + executável de teste.
#
# Substitui o CMakeLists.txt raiz do OrcaSlicer, que arrasta GUI (wxWidgets),
# Python e desktop integration. Aqui só entra o que libslic3r precisa.
#
# Pré-requisito: scripts/build-deps.sh (gera native/deps/out-android-<abi>).
# Configuração: scripts/build-native.sh, ou via Gradle externalNativeBuild.

cmake_minimum_required(VERSION 3.20)
if(${CMAKE_VERSION} VERSION_GREATER_EQUAL "4.0")
    set(CMAKE_POLICY_VERSION_MINIMUM 3.13 CACHE STRING "" FORCE)
endif()
project(android-slicer-native CXX C)

if (NOT ANDROID)
    message(FATAL_ERROR "Este projeto é só para Android (use o toolchain do NDK).")
endif ()

set(ORCA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/OrcaSlicer")
get_filename_component(ORCA_DIR "${ORCA_DIR}" ABSOLUTE)
set(DEPS_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/deps/out-android-${ANDROID_ABI}" CACHE PATH "Prefixo das deps compiladas")

list(APPEND CMAKE_PREFIX_PATH "${DEPS_PREFIX}")
list(APPEND CMAKE_FIND_ROOT_PATH "${DEPS_PREFIX}")
list(APPEND CMAKE_MODULE_PATH "${ORCA_DIR}/cmake/modules")
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif ()

# --- Equivalente ao que o CMakeLists.txt raiz do Orca define -------------------
include("${ORCA_DIR}/version.inc")
set(SLIC3R_RESOURCES_DIR "${ORCA_DIR}/resources")
set(LIBDIR "${ORCA_DIR}/src")

set(SLIC3R_STATIC ON)
set(SLIC3R_GUI OFF)
set(SLIC3R_PCH OFF)
set(SLIC3R_PROFILE OFF)
set(SLIC3R_NO_OCCT ON)                 # patch em src/libslic3r/CMakeLists.txt
set(IS_CROSS_COMPILE TRUE)             # desliga encoding-check (dev-utils)
set(SLIC3R_ENC_CHECK OFF CACHE BOOL "" FORCE)
set(SLIC3R_NO_OCCT_STUBS "${CMAKE_CURRENT_SOURCE_DIR}/src/android_stubs.cpp")

add_compile_definitions("$<IF:$<CONFIG:Debug>,DEBUG,NDEBUG>")
add_compile_definitions(BBL_RELEASE_TO_PUBLIC=1)
add_compile_options(-fsigned-char -Wall -Wno-unused-variable -Wno-unused-but-set-variable
                    -Wno-sign-compare -Wno-reorder -Wno-unused-function -Wno-deprecated-declarations)

add_compile_options(-Wno-delete-non-abstract-non-virtual-dtor -Wno-deprecated-non-prototype
                    -Wno-#pragma-messages -Wno-overloaded-virtual -Wno-unused-private-field
                    -Wno-missing-braces -Wno-unused-lambda-capture -Wno-unused-local-typedef
                    -Wno-pessimizing-move
                    # O toolchain do NDK põe -Werror=format-security; imgui não passa.
                    -Wno-error=format-security)

# O CMakeLists.txt raiz do Orca faz include_directories(${LIBDIR}); deps_src e
# libslic3r incluem "libslic3r/..." relativo a src/.
include_directories("${LIBDIR}")

function(slic3r_remap_configs targets from_Cfg to_Cfg)
endfunction()

# --- Dependências pré-compiladas ------------------------------------------------
set(Boost_USE_STATIC_LIBS ON)
set(Boost_NO_SYSTEM_PATHS TRUE)
if(POLICY CMP0167)
    cmake_policy(SET CMP0167 NEW)
endif()
find_package(Boost 1.83.0 REQUIRED COMPONENTS system filesystem thread log log_setup locale regex chrono atomic date_time iostreams program_options nowide)
find_package(Eigen3 5.0.1 REQUIRED)

add_library(boost_libs INTERFACE)
add_library(boost_headeronly INTERFACE)
target_include_directories(boost_headeronly SYSTEM INTERFACE ${Boost_INCLUDE_DIRS})
target_link_libraries(boost_libs INTERFACE boost_headeronly ${Boost_LIBRARIES})

set(TBB_STATIC 1)
set(TBB_DEBUG 1)
find_package(TBB REQUIRED)

find_package(OpenSSL REQUIRED)
find_package(Freetype REQUIRED)
find_package(ZLIB REQUIRED)        # sysroot do NDK
find_package(PNG REQUIRED)
find_package(JPEG REQUIRED)
find_package(EXPAT REQUIRED)
find_package(cereal REQUIRED)
if (NOT TARGET cereal::cereal)
    set_target_properties(cereal PROPERTIES IMPORTED_GLOBAL TRUE)
    add_library(cereal::cereal ALIAS cereal)
else ()
    set_target_properties(cereal::cereal PROPERTIES IMPORTED_GLOBAL TRUE)
endif ()
find_package(NLopt 1.4 REQUIRED)
find_package(libnoise REQUIRED)

# FindOpenVDB tenta executar vdb_print (binário Android) para ler a ABI.
add_compile_definitions(OPENVDB_ABI_VERSION_NUMBER=8)

set(OPENVDB_USE_STATIC_LIBS ON)
set(USE_BLOSC TRUE)
find_package(OpenVDB 5.0 REQUIRED COMPONENTS openvdb)

# --- Bibliotecas vendorizadas (subconjunto de deps_src) ------------------------
set(_deps_src "${ORCA_DIR}/deps_src")
set(_deps_bin "${CMAKE_CURRENT_BINARY_DIR}/deps_src")
foreach (_lib agg ankerl earcut fast_float nanosvg nlohmann stb_dxt
              Shiny admesh clipper clipper2 glu-libtess imgui libigl libnest2d
              mcut miniz qhull qoi semver)
    add_subdirectory("${_deps_src}/${_lib}" "${_deps_bin}/${_lib}")
endforeach ()

# dev-utils define encoding_check(); com SLIC3R_ENC_CHECK=OFF vira no-op.
add_subdirectory("${ORCA_DIR}/src/dev-utils" "${CMAKE_CURRENT_BINARY_DIR}/dev-utils")
add_subdirectory("${ORCA_DIR}/src/libslic3r" "${CMAKE_CURRENT_BINARY_DIR}/libslic3r")

target_include_directories(libslic3r PUBLIC "${ORCA_DIR}/src")
target_link_libraries(libslic3r PUBLIC log)

# --- Executável de teste (Fase 0): fatia um modelo via adb shell --------------
add_executable(slice_cli tests/slice_cli.cpp)
target_link_libraries(slice_cli PRIVATE libslic3r boost_libs)
