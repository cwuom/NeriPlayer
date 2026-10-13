package moe.ouom.neriplayer.platform.subsonic.api

import kotlinx.coroutines.TimeoutCancellationException
import moe.ouom.neriplayer.common.R

/** Resolve safe error types at the display boundary, using the caller's current locale. */
fun subsonicErrorMessageRes(error: Throwable, accountInput: Boolean = false): Int = when {
    error is TimeoutCancellationException -> R.string.server_timeout
    error is SubsonicException -> when (error.kind) {
        SubsonicFailureKind.AUTHENTICATION -> R.string.server_error_auth
        SubsonicFailureKind.FORBIDDEN -> R.string.server_error_forbidden
        SubsonicFailureKind.NOT_FOUND -> R.string.server_error_missing
        SubsonicFailureKind.RATE_LIMITED -> R.string.server_error_rate_limit
        SubsonicFailureKind.SERVER -> R.string.server_error_service
        SubsonicFailureKind.NETWORK -> R.string.server_error_network
        SubsonicFailureKind.TIMEOUT -> R.string.server_timeout
        SubsonicFailureKind.TLS -> R.string.server_error_tls
        SubsonicFailureKind.UNSUPPORTED -> R.string.server_error_protocol
        SubsonicFailureKind.ACCOUNT_UNAVAILABLE -> R.string.server_unavailable
        SubsonicFailureKind.CONFIG_CHANGED -> R.string.server_error_config_changed
        SubsonicFailureKind.INVALID_RESPONSE -> R.string.server_error_response
    }
    error is IllegalArgumentException && accountInput -> R.string.server_error_input
    else -> R.string.server_request_failed
}
