cmake_minimum_required(VERSION 3.22.1)
project(bitchord_analysis LANGUAGES C CXX)

get_filename_component(BITCHORD_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native" ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(bitchord_analysis SHARED
    ${BITCHORD_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/resampler.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    ${BITCHORD_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(bitchord_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${BITCHORD_NATIVE_DIR}
)
target_compile_options(bitchord_analysis PRIVATE -O3 -fvisibility=hidden)
target_link_options(bitchord_analysis PRIVATE "-Wl,-z,max-page-size=16384")
find_library(log-lib log)
target_link_libraries(bitchord_analysis ${log-lib})
