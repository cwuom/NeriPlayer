package moe.ouom.neriplayer.data.ltw.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.listentogether.profile.LISTEN_TOGETHER_NICKNAME_MIN_LENGTH
import moe.ouom.neriplayer.listentogether.profile.LISTEN_TOGETHER_NICKNAME_MAX_LENGTH
import moe.ouom.neriplayer.listentogether.profile.isValidListenTogetherNickname

fun validateListenTogetherNickname(nickname: String): ListenTogetherValidationError? {
    val normalized = nickname.trim()
    return when {
        normalized.length !in LISTEN_TOGETHER_NICKNAME_MIN_LENGTH..LISTEN_TOGETHER_NICKNAME_MAX_LENGTH -> {
            ListenTogetherValidationError(
                messageResId = CoreCommonR.string.listen_together_error_nickname_length,
                args = listOf(
                    LISTEN_TOGETHER_NICKNAME_MIN_LENGTH,
                    LISTEN_TOGETHER_NICKNAME_MAX_LENGTH
                )
            )
        }

        !isValidListenTogetherNickname(normalized) -> {
            ListenTogetherValidationError(CoreCommonR.string.listen_together_error_nickname_chars)
        }

        else -> null
    }
}

fun requireValidListenTogetherNickname(nickname: String, formatValidationError: (ListenTogetherValidationError) -> String): String {
    val normalized = nickname.trim()
    validateListenTogetherNickname(normalized)?.let { error(formatValidationError(it)) }
    return normalized
}
