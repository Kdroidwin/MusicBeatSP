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

# The three BitChord bit-perfect transports are reused here: Android 14's
# USB mixer, Oboe/AAudio exclusive output for built-in DACs, and the direct
# USB Audio Class driver. Keep both native dependencies pinned to upstream's
# verified releases so a normal build remains reproducible.
include(FetchContent)
set(OBOE_DISABLE_CONVERSION ON CACHE BOOL "" FORCE)
FetchContent_Declare(oboe
    URL https://github.com/google/oboe/archive/refs/tags/1.11.0.tar.gz
    URL_HASH SHA256=0ccf2110640a2b4489f1c93a404f2978656b434d8c5dc9cb6258f9cc95c79b15)
FetchContent_MakeAvailable(oboe)

add_library(bitchord_exclusive SHARED bitperfect/exclusive_output.cpp)
target_compile_options(bitchord_exclusive PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(bitchord_exclusive PRIVATE oboe ${log-lib})
target_link_options(bitchord_exclusive PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
set_property(TARGET bitchord_exclusive APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")

FetchContent_Declare(libusb
    URL https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2
    URL_HASH SHA256=ffaa41d741a8a3bee244ac8e54a72ea05bf2879663c098c82fc5757853441575)
FetchContent_MakeAvailable(libusb)
add_library(bitchord_libusb STATIC
    ${libusb_SOURCE_DIR}/libusb/core.c
    ${libusb_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_SOURCE_DIR}/libusb/io.c
    ${libusb_SOURCE_DIR}/libusb/sync.c
    ${libusb_SOURCE_DIR}/libusb/strerror.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_usbfs.c
    ${libusb_SOURCE_DIR}/libusb/os/events_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/threads_posix.c
    ${libusb_SOURCE_DIR}/libusb/os/linux_netlink.c)
target_include_directories(bitchord_libusb
    PRIVATE ${libusb_SOURCE_DIR}/android ${libusb_SOURCE_DIR}/libusb/os
    PUBLIC ${libusb_SOURCE_DIR}/libusb)
target_compile_options(bitchord_libusb PRIVATE -O2 -fvisibility=hidden -pthread -w)
set_target_properties(bitchord_libusb PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(bitchord_usb SHARED bitperfect/usb_transport.cpp)
target_compile_options(bitchord_usb PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(bitchord_usb PRIVATE bitchord_libusb ${log-lib})
target_link_options(bitchord_usb PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
    "-Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
set_property(TARGET bitchord_usb APPEND PROPERTY LINK_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/bitperfect/exports.map")
