cmake_minimum_required(VERSION 3.22.1)

project(neri_usb_host_tests LANGUAGES CXX)

include(CTest)
find_package(Threads REQUIRED)
find_program(BASH_EXECUTABLE bash REQUIRED)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(
    USB_TEST_FORCE_ASSERTS
    "Keep assert-based USB tests active in every build type"
    ON
)
set(
    USB_TEST_SANITIZER
    "none"
    CACHE STRING
    "Host sanitizer profile: none, asan-ubsan, or tsan"
)
set_property(
    CACHE USB_TEST_SANITIZER
    PROPERTY STRINGS none asan-ubsan tsan
)

set(NERI_CPP_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(NERI_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../..")
set(NERI_USB_LAB_RUNNER "${NERI_REPO_ROOT}/tools_pub/usb-async-lab")
set(NERI_USB_TEST_COMPILE_OPTIONS -Wall -Wextra -Werror)
set(NERI_USB_TEST_LINK_OPTIONS)

if(USB_TEST_FORCE_ASSERTS)
    list(APPEND NERI_USB_TEST_COMPILE_OPTIONS -UNDEBUG)
endif()

if(USB_TEST_SANITIZER STREQUAL "asan-ubsan")
    list(
        APPEND NERI_USB_TEST_COMPILE_OPTIONS
        -fsanitize=address,undefined
        -fno-omit-frame-pointer
        -fno-sanitize-recover=all
    )
    list(
        APPEND NERI_USB_TEST_LINK_OPTIONS
        -fsanitize=address,undefined
        -fno-omit-frame-pointer
        -fno-sanitize-recover=all
    )
elseif(USB_TEST_SANITIZER STREQUAL "tsan")
    list(
        APPEND NERI_USB_TEST_COMPILE_OPTIONS
        -fsanitize=thread
        -fno-omit-frame-pointer
    )
    list(
        APPEND NERI_USB_TEST_LINK_OPTIONS
        -fsanitize=thread
        -fno-omit-frame-pointer
    )
elseif(NOT USB_TEST_SANITIZER STREQUAL "none")
    message(FATAL_ERROR "Unknown USB_TEST_SANITIZER=${USB_TEST_SANITIZER}")
endif()

function(add_neri_usb_host_test target source)
    add_executable(${target} ${source} ${ARGN})
    target_include_directories(${target} PRIVATE "${NERI_CPP_ROOT}")
    target_compile_options(${target} PRIVATE ${NERI_USB_TEST_COMPILE_OPTIONS})
    target_link_options(${target} PRIVATE ${NERI_USB_TEST_LINK_OPTIONS})
    target_link_libraries(${target} PRIVATE Threads::Threads)
    add_test(NAME ${target} COMMAND ${target})
    set_tests_properties(
        ${target}
        PROPERTIES
            ENVIRONMENT "NERI_USB_TEST_SEED=324508639"
            TIMEOUT 60
    )
endfunction()

add_neri_usb_host_test(
    usb_assertions_enabled_test
    usb_assertions_enabled_test.cpp
)
add_neri_usb_host_test(
    usb_player_replay_buffer_test
    exclusive/usb_player_replay_buffer_test.cpp
)
add_neri_usb_host_test(
    usb_player_startup_preroll_test
    exclusive/usb_player_startup_preroll_test.cpp
)
add_neri_usb_host_test(
    usb_runtime_report_v2_test
    exclusive/usb_runtime_report_v2_test.cpp
    "${NERI_CPP_ROOT}/usb/exclusive/usb_runtime_report_v2.cpp"
)
add_neri_usb_host_test(
    usb_recovery_action_latch_test
    exclusive/usb_recovery_action_latch_test.cpp
    "${NERI_CPP_ROOT}/usb/exclusive/usb_recovery_action_latch.cpp"
)
add_neri_usb_host_test(
    usb_iso_transfer_health_test
    iso/usb_iso_transfer_health_test.cpp
)
add_neri_usb_host_test(
    usb_iso_packet_scheduler_test
    iso/usb_iso_packet_scheduler_test.cpp
)
add_neri_usb_host_test(
    usb_pcm_pipeline_test
    pcm/usb_pcm_pipeline_test.cpp
    "${NERI_CPP_ROOT}/usb/pcm/usb_pcm_codec.cpp"
    "${NERI_CPP_ROOT}/usb/pcm/usb_pcm_pipeline.cpp"
)
add_neri_usb_host_test(
    usb_uac1_format_test
    uac1/usb_uac1_format_test.cpp
    "${NERI_CPP_ROOT}/usb/uac1/usb_uac1_format.cpp"
)
add_neri_usb_host_test(
    usb_uac2_format_test
    uac2/usb_uac2_format_test.cpp
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_format.cpp"
)
add_neri_usb_host_test(
    usb_uac2_clock_graph_test
    uac2/usb_uac2_clock_graph_test.cpp
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_clock_graph.cpp"
)
add_neri_usb_host_test(
    usb_uac2_feedback_model_test
    uac2/usb_uac2_feedback_model_test.cpp
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_model.cpp"
)
add_neri_usb_host_test(
    usb_uac2_feedback_profile_test
    uac2/usb_uac2_feedback_profile_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_decoder.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_model.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_profile.cpp"
)
add_neri_usb_host_test(
    usb_uac2_descriptor_snapshot_test
    uac2/usb_uac2_descriptor_snapshot_test.cpp
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_model.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_profile.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_descriptor_snapshot.cpp"
)
add_neri_usb_host_test(
    usb_uac2_candidate_model_test
    uac2/usb_uac2_candidate_model_test.cpp
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_format.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_clock_graph.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_model.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_feedback_profile.cpp"
    "${NERI_CPP_ROOT}/usb/uac2/usb_uac2_candidate_model.cpp"
)
add_neri_usb_host_test(
    usb_feedback_rate_math_test
    feedback/usb_feedback_rate_math_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
)
add_neri_usb_host_test(
    usb_feedback_decoder_test
    feedback/usb_feedback_decoder_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_types.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_decoder.cpp"
)
add_neri_usb_host_test(
    usb_feedback_estimator_test
    feedback/usb_feedback_estimator_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_estimator.cpp"
)
add_neri_usb_host_test(
    usb_feedback_in_transfer_set_test
    feedback/usb_feedback_in_transfer_set_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_in_transfer_set.cpp"
)
add_neri_usb_host_test(
    usb_feedback_clock_test
    feedback/usb_feedback_clock_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_types.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_estimator.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_clock.cpp"
)
add_neri_usb_host_test(
    usb_feedback_packet_scheduler_test
    feedback/usb_feedback_packet_scheduler_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_packet_scheduler.cpp"
)
add_neri_usb_host_test(
    usb_feedback_ahead_window_test
    feedback/usb_feedback_ahead_window_test.cpp
)
add_neri_usb_host_test(
    usb_feedback_trace_replay_test
    feedback/usb_feedback_trace_replay_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_decoder.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_estimator.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_clock.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_packet_scheduler.cpp"
)
add_neri_usb_host_test(
    usb_feedback_transfer_lifecycle_test
    feedback/usb_feedback_transfer_lifecycle_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_transfer_lifecycle.cpp"
)
add_neri_usb_host_test(
    usb_feedback_stream_gate_test
    feedback/usb_feedback_stream_gate_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_stream_gate.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_clock.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_estimator.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_packet_scheduler.cpp"
)
add_neri_usb_host_test(
    usb_explicit_feedback_engine_test
    feedback/usb_explicit_feedback_engine_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_types.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_decoder.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_estimator.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_clock.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_packet_scheduler.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_stream_gate.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_transfer_lifecycle.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_explicit_feedback_engine.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_explicit_feedback_engine_state.cpp"
)
add_neri_usb_host_test(
    usb_explicit_feedback_runtime_test
    feedback/usb_explicit_feedback_runtime_test.cpp
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_types.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_rate_math.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_decoder.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_estimator.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_clock.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_packet_scheduler.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_feedback_stream_gate.cpp"
    "${NERI_CPP_ROOT}/usb/feedback/usb_explicit_feedback_runtime.cpp"
)

add_test(
    NAME usb_async_lab_runner_self_check
    COMMAND
        "${BASH_EXECUTABLE}"
        "${NERI_USB_LAB_RUNNER}"
        self-check
        --manifest
        "${CMAKE_CURRENT_LIST_DIR}/config/run-manifest.example.yaml"
)
set_tests_properties(usb_async_lab_runner_self_check PROPERTIES TIMEOUT 60)

foreach(failure_kind warning fingerprint port daemon)
    string(TOUPPER "${failure_kind}" failure_kind_upper)
    add_test(
        NAME usb_async_lab_runner_${failure_kind}_fail_closed_test
        COMMAND
            "${CMAKE_COMMAND}"
            -E
            env
            "NERI_USB_SELF_CHECK_FORCE_${failure_kind_upper}_FAILURE=1"
            "${BASH_EXECUTABLE}"
            "${NERI_USB_LAB_RUNNER}"
            self-check
            --manifest
            "${CMAKE_CURRENT_LIST_DIR}/config/run-manifest.example.yaml"
    )
    set_tests_properties(
        usb_async_lab_runner_${failure_kind}_fail_closed_test
        PROPERTIES
            TIMEOUT 60
            WILL_FAIL TRUE
    )
endforeach()
