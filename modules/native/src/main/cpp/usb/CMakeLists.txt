add_library(
    neri_usb_core
    STATIC
    control/usb_feature_unit.cpp
    control/usb_sample_rate_readback.cpp
    exclusive/usb_recovery_action_latch.cpp
    exclusive/usb_runtime_report_v2.cpp
    feedback/usb_explicit_feedback_engine.cpp
    feedback/usb_explicit_feedback_engine_state.cpp
    feedback/usb_explicit_feedback_runtime.cpp
    feedback/usb_feedback_clock.cpp
    feedback/usb_feedback_decoder.cpp
    feedback/usb_feedback_estimator.cpp
    feedback/usb_feedback_in_transfer_set.cpp
    feedback/usb_feedback_packet_scheduler.cpp
    feedback/usb_feedback_rate_math.cpp
    feedback/usb_feedback_stream_gate.cpp
    feedback/usb_feedback_transfer_lifecycle.cpp
    feedback/usb_feedback_types.cpp
    pcm/usb_pcm_codec.cpp
    pcm/usb_pcm_pipeline.cpp
    pcm/usb_pcm_resampler.cpp
    uac1/usb_uac1_format.cpp
    uac2/usb_uac2_candidate_model.cpp
    uac2/usb_uac2_clock_graph.cpp
    uac2/usb_uac2_descriptor_snapshot.cpp
    uac2/usb_uac2_feedback_model.cpp
    uac2/usb_uac2_feedback_profile.cpp
    uac2/usb_uac2_format.cpp
)
neri_configure_owned_native_target(neri_usb_core)
target_include_directories(neri_usb_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/..")
target_link_libraries(neri_usb_core PUBLIC neri_libusb_headers)

if(ANDROID)
    add_subdirectory(exclusive)
endif()
