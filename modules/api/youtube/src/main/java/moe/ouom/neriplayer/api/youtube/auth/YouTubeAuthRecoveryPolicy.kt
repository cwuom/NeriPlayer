package moe.ouom.neriplayer.api.youtube.auth

fun extractYouTubeRequestFailureCode(error: Throwable): Int? {
    val message = error.message.orEmpty()
    return Regex("""request failed:\s*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(message)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

fun isYouTubeAuthRecoverableFailure(error: Throwable): Boolean {
    return extractYouTubeRequestFailureCode(error) in setOf(401, 403, 429)
}

fun shouldStartYouTubeWebAuthRecovery(error: Throwable): Boolean {
    return extractYouTubeRequestFailureCode(error) == 401
}
