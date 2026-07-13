package moe.ouom.neriplayer.listentogether.validation

import moe.ouom.neriplayer.R

const val LISTEN_TOGETHER_NICKNAME_MIN_LENGTH = 1
const val LISTEN_TOGETHER_NICKNAME_MAX_LENGTH = 24

fun validateListenTogetherNickname(nickname: String): ListenTogetherValidationError? {
    val normalized = nickname.trim()
    return when {
        normalized.length !in LISTEN_TOGETHER_NICKNAME_MIN_LENGTH..LISTEN_TOGETHER_NICKNAME_MAX_LENGTH -> {
            ListenTogetherValidationError(
                messageResId = R.string.listen_together_error_nickname_length,
                args = listOf(
                    LISTEN_TOGETHER_NICKNAME_MIN_LENGTH,
                    LISTEN_TOGETHER_NICKNAME_MAX_LENGTH
                )
            )
        }

        !isValidListenTogetherNickname(normalized) -> {
            ListenTogetherValidationError(R.string.listen_together_error_nickname_chars)
        }

        else -> null
    }
}

fun sanitizeListenTogetherNicknameOrNull(nickname: String?): String? {
    val normalized = nickname?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return normalized.takeIf { validateListenTogetherNickname(it) == null }
}

fun requireValidListenTogetherNickname(nickname: String): String {
    val normalized = nickname.trim()
    validateListenTogetherNickname(normalized)?.let { error(it.formatForApp()) }
    return normalized
}

private fun isValidListenTogetherNickname(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (!isAllowedNicknameCodePoint(codePoint)) {
            return false
        }
        index += Character.charCount(codePoint)
    }
    return true
}

private fun isAllowedNicknameCodePoint(codePoint: Int): Boolean {
    return when {
        codePoint in '0'.code..'9'.code -> true
        codePoint in 'A'.code..'Z'.code -> true
        codePoint in 'a'.code..'z'.code -> true
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN -> true
        else -> false
    }
}
