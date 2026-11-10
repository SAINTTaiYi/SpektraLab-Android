# SpektraLab for Android — native engine build.
# GPLv3. Builds libspektrafilm_engine.so (engine + JNI bridge).
cmake_minimum_required(VERSION 3.22.1)
project(spektralab LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Release: optimize for the per-pixel spectral loops.
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -ffast-math -fno-finite-math-only")
if (NOT CMAKE_CXX_FLAGS_DEBUG MATCHES "-O")
    set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")
endif()

# Engine source root
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(ENGINE_ROOT "${PROJECT_ROOT}/../SpektraLab-android-adreno/engine")

# Include directories
include_directories(
    ${ENGINE_ROOT}/include
    ${ENGINE_ROOT}/src
    ${ENGINE_ROOT}/src/core
    ${ENGINE_ROOT}/src/gpu
    ${ENGINE_ROOT}/src/pipeline
    ${ENGINE_ROOT}/src/io
    ${ENGINE_ROOT}/third_party/volk
    ${ENGINE_ROOT}/third_party/stb
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Vulkan headers
find_path(VULKAN_INCLUDE_DIR vulkan/vulkan_core.h
    PATHS ${ENGINE_ROOT}/../build/deps/Vulkan-Headers/include
          ${ANDROID_NDK}/sources/third_party/vulkan/src/include
          $ENV{PREFIX}/include
)
if(NOT VULKAN_INCLUDE_DIR)
    message(FATAL_ERROR "Vulkan headers not found")
endif()
include_directories(${VULKAN_INCLUDE_DIR})

# glslangValidator for compiling .comp shaders to SPIR-V
find_program(GLSLANG_VALIDATOR glslangValidator
    PATHS ${ANDROID_NDK}/shader-tools/*/bin
          $ENV{PREFIX}/bin
    REQUIRED
)

# Compile Vulkan shaders to SPIR-V
set(VULKAN_SHADER_DIR ${ENGINE_ROOT}/src/shaders/vulkan)
set(SPIRV_OUTPUT_DIR ${CMAKE_CURRENT_BINARY_DIR}/spirv)
file(MAKE_DIRECTORY ${SPIRV_OUTPUT_DIR})

set(VULKAN_KERNELS
    spk_math_probe spk_split_probe spk_take_rgb spk_matmul3 spk_stride_sample
    spk_affine3 spk_tc_b spk_lut2d_cubic spk_sep_fir_acc spk_iir_df_acc
    spk_lincomb3 spk_log10_guarded spk_curves spk_couplers_correction
    spk_spectral_epilogue spk_print_exposure spk_cam16ucs_compress
    spk_cctf_encode_matrix spk_to_rgba16 spk_view_grade spk_view_grade_di
    spk_view_geometry spk_view_pack spk_view_cam16 spk_grain_layers
    spk_grain_layer_one spk_grain_simple spk_lognormal_field spk_rng_probe
    spk_mul spk_glare_add spk_reduce_max spk_boost spk_highlight_headroom
    spk_bw_correct spk_edr spk_cctf_decode spk_zoom_bilinear_mirror
    spk_geometry_resample_df spk_lut3d_trilinear spk_di_normalise spk_di_encode
    spk_scene_latitude spk_overscan_canvas spk_overscan_add_mask
    spk_overscan_film_present spk_overscan_light spk_view_pair
)

set(SPIRV_HEADERS "")
foreach(kernel ${VULKAN_KERNELS})
    set(input ${VULKAN_SHADER_DIR}/${kernel}.comp)
    set(output ${SPIRV_OUTPUT_DIR}/${kernel}.spv)
    add_custom_command(
        OUTPUT ${output}
        COMMAND ${GLSLANG_VALIDATOR} -V --target-env vulkan1.0 -o ${output} ${input}
        DEPENDS ${input}
        COMMENT "Compiling ${kernel}.comp to SPIR-V"
    )
    list(APPEND SPIRV_HEADERS ${output})
endforeach()

# Engine sources
set(SPEKTRALAB_CORE_SOURCES
    ${ENGINE_ROOT}/src/core/blob.cpp
    ${ENGINE_ROOT}/src/core/cam16.cpp
    ${ENGINE_ROOT}/src/core/colour.cpp
    ${ENGINE_ROOT}/src/core/curves.cpp
    ${ENGINE_ROOT}/src/core/digital_intermediate.cpp
    ${ENGINE_ROOT}/src/core/hanatos.cpp
    ${ENGINE_ROOT}/src/core/json.cpp
    ${ENGINE_ROOT}/src/core/latitude_fit.cpp
    ${ENGINE_ROOT}/src/core/numeric.cpp
    ${ENGINE_ROOT}/src/core/params.cpp
    ${ENGINE_ROOT}/src/core/print_lut.cpp
    ${ENGINE_ROOT}/src/core/printing.cpp
    ${ENGINE_ROOT}/src/core/profile.cpp
    ${ENGINE_ROOT}/src/core/setup_cache.cpp
    ${ENGINE_ROOT}/src/core/spectral.cpp
)

set(SPEKTRALAB_PIPELINE_SOURCES
    ${ENGINE_ROOT}/src/pipeline/blur.cpp
    ${ENGINE_ROOT}/src/pipeline/engine.cpp
    ${ENGINE_ROOT}/src/pipeline/pipeline.cpp
    ${ENGINE_ROOT}/src/pipeline/scene_latitude.cpp
    ${ENGINE_ROOT}/src/pipeline/contrast_mask.cpp
    ${ENGINE_ROOT}/src/pipeline/overscan.cpp
    ${ENGINE_ROOT}/src/pipeline/imprint_raster.cpp
    ${ENGINE_ROOT}/src/pipeline/view_process.cpp
    ${ENGINE_ROOT}/src/pipeline/windows_unported.cpp
)

set(SPEKTRALAB_GPU_SOURCES
    ${ENGINE_ROOT}/src/gpu/vulkan_gpu.cpp
    ${ENGINE_ROOT}/third_party/volk/volk.c
)

set(SPEKTRALAB_IO_SOURCES
    ${ENGINE_ROOT}/src/io/raw_decoder.cpp
    ${ENGINE_ROOT}/src/io/raw_headroom.cpp
    ${ENGINE_ROOT}/src/io/image_writer.cpp
)

set(JNI_SOURCES spektralab_jni.cpp)

# Build shared library
add_library(spektrafilm_engine SHARED
    ${SPEKTRALAB_CORE_SOURCES}
    ${SPEKTRALAB_PIPELINE_SOURCES}
    ${SPEKTRALAB_GPU_SOURCES}
    ${SPEKTRALAB_IO_SOURCES}
    ${JNI_SOURCES}
    ${SPIRV_HEADERS}
)

# Android system libs
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# Vulkan: use the system Vulkan loader on Android
find_library(vulkan-lib vulkan)

# LibRaw (from Termux)
find_library(raw-lib raw PATHS $ENV{PREFIX}/lib)
find_library(raw_r-lib raw_r PATHS $ENV{PREFIX}/lib)

# OpenMP (from Termux)
find_library(omp-lib omp PATHS $ENV{PREFIX}/lib)

# Link everything
target_link_libraries(spektrafilm_engine
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    ${vulkan-lib}
    ${raw-lib}
    ${omp-lib}
    Threads::Threads
    ${CMAKE_DL_LIBS}
)

# 16 KB page alignment for Android 15+
set_target_properties(spektrafilm_engine PROPERTIES
    LINK_FLAGS "-Wl,-z,max-page-size=16384"
)

# Compile definitions
target_compile_definitions(spektrafilm_engine PRIVATE
    SPK_ANDROID_SYSTEM_VULKAN=1
    SPK_VULKAN_BACKEND=1
    SPEKTRALAB_VULKAN_ON_APPLE=0
    SPEKTRALAB_BUILD_HOST=0
    SPEKTRALAB_BUILD_DESKTOP=0
    SPEKTRALAB_BUILD_NATIVE_RAW=1
)
