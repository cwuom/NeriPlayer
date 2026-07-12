cmake_minimum_required(VERSION 3.22.1)

project(neri_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(LIBUSB_DIR ${CMAKE_SOURCE_DIR}/libusb)

add_library(
    usb
    STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
)

target_include_directories(
    usb
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
)

target_compile_definitions(
    usb
    PRIVATE
    HAVE_CONFIG_H
)

target_compile_options(
    usb
    PRIVATE
    -O2
    -w
)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR
   CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    target_compile_options(usb PRIVATE -fomit-frame-pointer)
endif()

add_library(
    neri_native
    SHARED
    native_crash_handler.cpp
    usb_exclusive_bridge.cpp
    usb_pcm_codec.cpp
    usb_pcm_pipeline.cpp
    usb_uac1_format.cpp
)

set_target_properties(
    neri_native
    PROPERTIES
    OUTPUT_NAME "_neri"
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(
    neri_native
    PRIVATE
    usb
    android
    ${log-lib}
    ${dl-lib}
)

target_include_directories(
    neri_native
    PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/libusb
    ${CMAKE_SOURCE_DIR}/libusb/os
)

target_compile_definitions(
    neri_native
    PRIVATE
    HAVE_CONFIG_H
)

target_compile_options(
    neri_native
    PRIVATE
    -Wall
    -Wextra
    -Werror
)
