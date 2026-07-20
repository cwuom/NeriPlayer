cmake_minimum_required(VERSION 3.22.1)

project(neri_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(LIBUSB_DIR ${CMAKE_SOURCE_DIR}/libusb)
set(NERI_CRASH_DIR ${CMAKE_SOURCE_DIR}/crash)
set(NERI_USB_DIR ${CMAKE_SOURCE_DIR}/usb)
set(NERI_USB_EXCLUSIVE_DIR ${NERI_USB_DIR}/exclusive)
set(NERI_USB_FEEDBACK_DIR ${NERI_USB_DIR}/feedback)
set(NERI_USB_ISO_DIR ${NERI_USB_DIR}/iso)
set(NERI_USB_PCM_DIR ${NERI_USB_DIR}/pcm)
set(NERI_USB_UAC1_DIR ${NERI_USB_DIR}/uac1)
set(NERI_USB_UAC2_DIR ${NERI_USB_DIR}/uac2)

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
    -w
    -ffunction-sections
    -fdata-sections
    "$<$<NOT:$<CONFIG:Debug>>:-Oz>"
)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR
   CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    target_compile_options(usb PRIVATE -fomit-frame-pointer)
endif()

add_library(
    neri_native
    SHARED
    ${NERI_CRASH_DIR}/native_crash_handler.cpp
    ${NERI_USB_EXCLUSIVE_DIR}/usb_exclusive_bridge.cpp
    ${NERI_USB_EXCLUSIVE_DIR}/usb_recovery_action_latch.cpp
    ${NERI_USB_EXCLUSIVE_DIR}/usb_runtime_report_v2.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_explicit_feedback_engine.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_explicit_feedback_engine_state.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_explicit_feedback_runtime.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_clock.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_decoder.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_estimator.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_in_transfer_set.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_packet_scheduler.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_rate_math.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_stream_gate.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_transfer_lifecycle.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_feedback_types.cpp
    ${NERI_USB_FEEDBACK_DIR}/usb_libusb_feedback_backend.cpp
    ${NERI_USB_PCM_DIR}/usb_pcm_codec.cpp
    ${NERI_USB_PCM_DIR}/usb_pcm_pipeline.cpp
    ${NERI_USB_UAC1_DIR}/usb_uac1_format.cpp
    ${NERI_USB_UAC2_DIR}/usb_uac2_candidate_model.cpp
    ${NERI_USB_UAC2_DIR}/usb_uac2_clock_graph.cpp
    ${NERI_USB_UAC2_DIR}/usb_uac2_descriptor_snapshot.cpp
    ${NERI_USB_UAC2_DIR}/usb_uac2_feedback_model.cpp
    ${NERI_USB_UAC2_DIR}/usb_uac2_feedback_profile.cpp
    ${NERI_USB_UAC2_DIR}/usb_uac2_format.cpp
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
    ${NERI_USB_EXCLUSIVE_DIR}
    ${NERI_USB_FEEDBACK_DIR}
    ${NERI_USB_ISO_DIR}
    ${NERI_USB_PCM_DIR}
    ${NERI_USB_UAC1_DIR}
    ${NERI_USB_UAC2_DIR}
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
    -ffunction-sections
    -fdata-sections
    "$<$<NOT:$<CONFIG:Debug>>:-Oz>"
)

target_link_options(
    neri_native
    PRIVATE
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>"
)
