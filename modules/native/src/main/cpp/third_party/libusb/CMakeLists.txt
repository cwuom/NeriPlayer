add_library(neri_libusb_headers INTERFACE)
target_include_directories(
    neri_libusb_headers
    INTERFACE
    "${CMAKE_CURRENT_SOURCE_DIR}/.."
)

if(NOT ANDROID)
    return()
endif()

add_library(
    neri_libusb
    STATIC
    core.c
    descriptor.c
    hotplug.c
    io.c
    strerror.c
    sync.c
    os/linux_usbfs.c
    os/linux_netlink.c
    os/events_posix.c
    os/threads_posix.c
)
set_target_properties(neri_libusb PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(
    neri_libusb
    PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/os"
)
target_link_libraries(neri_libusb PUBLIC neri_libusb_headers)
target_compile_definitions(neri_libusb PRIVATE HAVE_CONFIG_H)
target_compile_options(
    neri_libusb
    PRIVATE
    -w
    -ffunction-sections
    -fdata-sections
    "$<$<NOT:$<CONFIG:Debug>>:-Oz>"
)

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR
   CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    target_compile_options(neri_libusb PRIVATE -fomit-frame-pointer)
endif()
