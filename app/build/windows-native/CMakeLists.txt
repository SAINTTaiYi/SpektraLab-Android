cmake_minimum_required(VERSION 3.22)
project(SpektraLabAndroid LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Use the Android NDK toolchain, arm64-v8a, and API 24.")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_TESTING OFF)
set(SPEKTRALAB_BUILD_NATIVE_RAW ON)
set(SPEKTRALAB_LIBRAW_OPENMP OFF)
set(SPEKTRALAB_REQUIRE_VULKAN ON)
set(SPEKTRALAB_VULKAN_HEADERS "${CMAKE_SYSROOT}/usr/include" CACHE PATH "")
add_compile_definitions(SPK_ANDROID_SYSTEM_VULKAN=1)
add_compile_options(-O2 -fno-fast-math -ffp-contract=off)
add_subdirectory("${ENGINE_ROOT}" engine)

# Unlike the Termux build, build the codecs for the APK's API level and link
# them into the JNI library. This avoids shipping Termux's versioned SONAMEs.
add_library(spk_jpeg STATIC IMPORTED)
set_target_properties(spk_jpeg PROPERTIES
    IMPORTED_LOCATION "${JPEG_BUILD}/libjpeg.a"
    INTERFACE_INCLUDE_DIRECTORIES "${JPEG_SOURCE}/src;${JPEG_BUILD}")
file(GLOB lcms_sources CONFIGURE_DEPENDS "${LCMS_SOURCE}/src/*.c")
add_library(spk_lcms STATIC ${lcms_sources})
target_include_directories(spk_lcms PUBLIC "${LCMS_SOURCE}/include")
target_compile_definitions(spk_libraw PRIVATE USE_ZLIB USE_JPEG USE_JPEG8 USE_LCMS2)
target_link_libraries(spk_libraw PRIVATE spk_jpeg spk_lcms z)

set(jni "${CMAKE_CURRENT_SOURCE_DIR}/../../src/main/cpp")
add_library(spektrafilm_engine SHARED
    "${jni}/spektralab_jni.cpp"
    "${jni}/spektralab_raw_jni.cpp")
target_compile_features(spektrafilm_engine PRIVATE cxx_std_20)
target_include_directories(spektrafilm_engine PRIVATE "${ENGINE_ROOT}/include")
target_link_libraries(spektrafilm_engine PRIVATE
    spektralab_engine spk_image_io spk_libraw spk_jpeg log android jnigraphics vulkan)
target_link_options(spektrafilm_engine PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
