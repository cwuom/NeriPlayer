cmake_minimum_required(VERSION 3.22.1)

project(neri_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(
    neri_native
    SHARED
    native_crash_handler.cpp
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
    ${log-lib}
    ${dl-lib}
)
