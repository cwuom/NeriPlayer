add_library(
    neri_dsp_core
    STATIC
    neri_dsp_primitives.cpp
    neri_dsp_filter_stages.cpp
    neri_dsp_space_stages.cpp
    neri_dsp_dynamics_stages.cpp
    neri_dsp_engine.cpp
)
neri_configure_owned_native_target(neri_dsp_core)
# 实时音频线程按速度优化，覆盖通用配置里的 -Oz
target_compile_options(neri_dsp_core PRIVATE "$<$<NOT:$<CONFIG:Debug>>:-O2>")
target_include_directories(neri_dsp_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/..")

if(ANDROID)
    add_library(neri_dsp_android OBJECT neri_dsp_jni.cpp)
    neri_configure_owned_native_target(neri_dsp_android)
    target_link_libraries(neri_dsp_android PRIVATE neri_dsp_core)
endif()
