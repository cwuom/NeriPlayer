package moe.ouom.neriplayer.ui.feedback

import moe.ouom.neriplayer.common.R as CoreR

internal fun lanErrorText(code: String): Int = when (code) {
    "MEMORY_LIMIT" -> CoreR.string.lan_error_memory
    "AUDIO_FORMAT" -> CoreR.string.lan_error_audio_format
    "AUDIO_SUSPENDED", "AUDIO_ENGINE", "CLOCK" -> CoreR.string.lan_error_audio_engine
    "LOAD_TIMEOUT" -> CoreR.string.lan_error_load_timeout
    "FULL_AUDIO_REQUIRED" -> CoreR.string.lan_error_full_audio
    "AUDIO_DURATION", "AUDIO_CONTENT" -> CoreR.string.lan_error_duration
    "SOURCE" -> CoreR.string.lan_error_source
    "DENIED" -> CoreR.string.lan_error_denied
    "LOADING" -> CoreR.string.lan_error_loading
    "OUTPUT" -> CoreR.string.lan_error_output
    "CLOUD_ACTIVE" -> CoreR.string.lan_error_cloud
    "NAME" -> CoreR.string.lan_error_name
    "ADDRESS" -> CoreR.string.lan_error_address
    "DISCOVERY" -> CoreR.string.lan_error_discovery
    "SERVICE_STOPPED" -> CoreR.string.lan_error_service
    "VERSION", "INVALID", "STALE", "UNSUPPORTED" -> CoreR.string.lan_error_invalid
    else -> CoreR.string.lan_error_network
}
