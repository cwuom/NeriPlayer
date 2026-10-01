cmake_minimum_required(VERSION 3.22.1)

project(neri_native LANGUAGES C CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "Use tests/usb as the source directory for native host tests")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(cmake/NeriNativeCompileOptions.cmake)
add_subdirectory(third_party/libusb)
add_subdirectory(usb)
add_subdirectory(crash)

# jni methods have no native callers, so include every bridge object explicitly
add_library(
    neri_native
    SHARED
    $<TARGET_OBJECTS:neri_crash>
    $<TARGET_OBJECTS:neri_usb_android>
)
set_target_properties(neri_native PROPERTIES OUTPUT_NAME "_neri")

find_library(NERI_LOG_LIBRARY log REQUIRED)
find_library(NERI_DL_LIBRARY dl REQUIRED)

target_link_libraries(
    neri_native
    PRIVATE
    neri_usb_core
    neri_libusb
    android
    ${NERI_LOG_LIBRARY}
    ${NERI_DL_LIBRARY}
)
target_link_options(
    neri_native
    PRIVATE
    -Wl,-z,max-page-size=16384
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>"
)
