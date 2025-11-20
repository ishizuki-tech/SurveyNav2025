cmake_minimum_required(VERSION 3.10)
project(whisper_jni LANGUAGES C CXX)

# ============================================================
# ✅ whisper.cpp — Android JNI Build Script (Final Clean)
# ------------------------------------------------------------
# • Works with ggml-org/whisper.cpp master (2025-10-31)
# • Fixes undefined symbols automatically (traits.cpp includes ggml_cpu_extra_work_size)
# • Includes all ggml-cpu/*.cpp files explicitly
# • Excludes all GPU/BLAS/SYCL/x86 backends (Android safe)
# • NDK 28 (Clang 19), arm64-v8a + armeabi-v7a supported
# ============================================================

# ------------------------------------------------------------
# Compiler setup
# ------------------------------------------------------------
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ------------------------------------------------------------
# Root path auto-detection
# ------------------------------------------------------------
set(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../..")
set(CANDIDATE_PATHS
        "${ROOT_DIR}/whisper.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../whisper.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper_core"
)
set(WHISPER_LIB_DIR "")
foreach(path IN LISTS CANDIDATE_PATHS)
    if (EXISTS "${path}/src/whisper.cpp")
        set(WHISPER_LIB_DIR "${path}")
        break()
    endif()
endforeach()

# Auto-clone if missing
if (NOT WHISPER_LIB_DIR)
    message(WARNING "⚠️ whisper.cpp not found, attempting clone...")
    execute_process(
            COMMAND git clone --depth=1 https://github.com/ggml-org/whisper.cpp.git
            WORKING_DIRECTORY ${ROOT_DIR}
    )
    if (EXISTS "${ROOT_DIR}/whisper.cpp/src/whisper.cpp")
        set(WHISPER_LIB_DIR "${ROOT_DIR}/whisper.cpp")
        message(STATUS "✅ whisper.cpp cloned successfully.")
    else()
        message(FATAL_ERROR "❌ whisper.cpp source not found after clone.")
    endif()
endif()

message(STATUS "📂 Using whisper.cpp directory: ${WHISPER_LIB_DIR}")

# ------------------------------------------------------------
# Collect sources
# ------------------------------------------------------------
set(PROJECT_SOURCES
        "${WHISPER_LIB_DIR}/src/whisper.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/whisperLib.c"
)

# ---- GGML sources (CPU-only)
file(GLOB_RECURSE GGML_SOURCES
        "${WHISPER_LIB_DIR}/ggml/src/ggml*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml*.cpp"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/*.c"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu/*.cpp"
)

# Exclude non-Android backends
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cann/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cuda/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-vulkan/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-metal/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-opencl/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-sycl/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-zdnn/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-webgpu/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-blas/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-rpc/.*")

# 🚫 Exclude unsupported CPU architectures
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/spacemit/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/kleidiai/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/arch/x86/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/arch/powerpc/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/arch/s390/.*")
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/arch/loongarch/.*")  # ✅ add this
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/arch/riscv/.*")      # ✅ add this
list(FILTER GGML_SOURCES EXCLUDE REGEX ".*/ggml-cpu/arch/wasm/.*")       # ✅ add this

set(SOURCE_FILES ${PROJECT_SOURCES} ${GGML_SOURCES})
message(STATUS "📦 GGML source count: ${GGML_SOURCES}")

# ------------------------------------------------------------
# Include paths
# ------------------------------------------------------------
add_library(ggml_interface INTERFACE)
target_include_directories(ggml_interface INTERFACE
        "${WHISPER_LIB_DIR}"
        "${WHISPER_LIB_DIR}/src"
        "${WHISPER_LIB_DIR}/include"
        "${WHISPER_LIB_DIR}/ggml/include"
        "${WHISPER_LIB_DIR}/ggml/src"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-cpu"
        "${WHISPER_LIB_DIR}/ggml/src/ggml-quants"
)

# ------------------------------------------------------------
# Version macros
# ------------------------------------------------------------
execute_process(
        COMMAND git -C ${WHISPER_LIB_DIR} rev-parse --short HEAD
        OUTPUT_VARIABLE LOCAL_GIT_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
)
if (NOT LOCAL_GIT_COMMIT)
    set(LOCAL_GIT_COMMIT "local-build")
endif()

add_compile_definitions(
        GGML_VERSION="ggml-android"
        GGML_COMMIT="${LOCAL_GIT_COMMIT}"
        WHISPER_VERSION="whisper-android"
)

# ------------------------------------------------------------
# Android system libs
# ------------------------------------------------------------
find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)

# ------------------------------------------------------------
# Build function
# ------------------------------------------------------------
function(build_library target_name)
    message(STATUS "⚙️ Building ${target_name} ...")
    add_library(${target_name} SHARED ${SOURCE_FILES})

    target_compile_definitions(${target_name} PUBLIC
            GGML_USE_CPU=1
            GGML_USE_K_QUANTS=1
            GGML_USE_QKK=1
            GGML_COMPUTE_QK_F32=1
    )

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4 -mfloat-abi=softfp)
    endif()

    if (CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O0 -g -DDEBUG -Wall -Wextra)
        message(STATUS "🪵 Debug flags: -O0 -g -Wall -Wextra")
    else()
        target_compile_options(${target_name} PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections -flto -Wl,--exclude-libs,ALL)
    endif()

    target_link_libraries(${target_name} PRIVATE ${LOG_LIB} ${ANDROID_LIB} ggml_interface)
endfunction()

# ------------------------------------------------------------
# ABI selection
# ------------------------------------------------------------
if (DEFINED ANDROID_ABI)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        build_library("whisper_v8fp16_va")
    elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
        build_library("whisper_vfpv4")
    else()
        message(WARNING "⚠️ Unknown ABI: ${ANDROID_ABI}, building generic.")
        build_library("whisper_generic")
    endif()
else()
    build_library("whisper_generic")
endif()

# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------
message(STATUS "✅ whisper.cpp JNI configuration complete.")
message(STATUS "🧱 Build type   : ${CMAKE_BUILD_TYPE}")
message(STATUS "🧩 ABI target   : ${ANDROID_ABI}")
message(STATUS "📦 whisper.cpp  : ${WHISPER_LIB_DIR}")
message(STATUS "🔖 Commit       : ${LOCAL_GIT_COMMIT}")
