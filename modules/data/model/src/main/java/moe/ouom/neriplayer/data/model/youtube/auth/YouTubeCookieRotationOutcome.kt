package moe.ouom.neriplayer.data.model.youtube.auth

sealed interface YouTubeCookieRotationOutcome {
    data class Rotated(val cookies: Map<String, String>) : YouTubeCookieRotationOutcome

    data object Unchanged : YouTubeCookieRotationOutcome

    data object Throttled : YouTubeCookieRotationOutcome

    data class Rejected(val code: Int) : YouTubeCookieRotationOutcome

    data object NetworkError : YouTubeCookieRotationOutcome

    data object Skipped : YouTubeCookieRotationOutcome
}
