cmake_minimum_required(VERSION 3.22.1)

project(neri_dsp_host_tests LANGUAGES CXX)

include(CTest)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(
    DSP_TEST_SANITIZER
    "none"
    CACHE STRING
    "Host sanitizer profile: none or asan-ubsan"
)
set_property(CACHE DSP_TEST_SANITIZER PROPERTY STRINGS none asan-ubsan)

get_filename_component(NERI_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." REALPATH)
include("${NERI_CPP_ROOT}/cmake/NeriNativeCompileOptions.cmake")
add_subdirectory("${NERI_CPP_ROOT}/dsp" dsp)

set(NERI_DSP_TEST_COMPILE_OPTIONS -UNDEBUG)
set(NERI_DSP_TEST_LINK_OPTIONS)
if(DSP_TEST_SANITIZER STREQUAL "asan-ubsan")
    set(NERI_DSP_SANITIZER_FLAGS -fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all)
    list(APPEND NERI_DSP_TEST_COMPILE_OPTIONS ${NERI_DSP_SANITIZER_FLAGS})
    list(APPEND NERI_DSP_TEST_LINK_OPTIONS ${NERI_DSP_SANITIZER_FLAGS})
    target_compile_options(neri_dsp_core PRIVATE ${NERI_DSP_SANITIZER_FLAGS})
elseif(NOT DSP_TEST_SANITIZER STREQUAL "none")
    message(FATAL_ERROR "Unknown DSP_TEST_SANITIZER=${DSP_TEST_SANITIZER}")
endif()

add_executable(neri_dsp_engine_test neri_dsp_engine_test.cpp)
neri_configure_owned_native_target(neri_dsp_engine_test)
target_compile_options(neri_dsp_engine_test PRIVATE ${NERI_DSP_TEST_COMPILE_OPTIONS})
target_link_options(neri_dsp_engine_test PRIVATE ${NERI_DSP_TEST_LINK_OPTIONS})
target_link_libraries(neri_dsp_engine_test PRIVATE neri_dsp_core)
add_test(NAME neri_dsp_engine_test COMMAND neri_dsp_engine_test)
set_tests_properties(neri_dsp_engine_test PROPERTIES TIMEOUT 120)
