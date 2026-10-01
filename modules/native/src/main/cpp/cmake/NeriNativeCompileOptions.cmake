function(neri_configure_owned_native_target target)
    target_compile_features(${target} PUBLIC cxx_std_17)
    set_target_properties(
        ${target}
        PROPERTIES
            CXX_EXTENSIONS OFF
            POSITION_INDEPENDENT_CODE ON
    )
    target_compile_options(${target} PRIVATE -Wall -Wextra -Werror)
    if(ANDROID)
        target_compile_options(
            ${target}
            PRIVATE
            -ffunction-sections
            -fdata-sections
            "$<$<NOT:$<CONFIG:Debug>>:-Oz>"
        )
    endif()
endfunction()
