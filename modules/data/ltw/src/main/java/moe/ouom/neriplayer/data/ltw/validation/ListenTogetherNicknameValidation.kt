package moe.ouom.neriplayer.data.ltw.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.ltw.profile.isValidListenTogetherNickname

const val LISTEN_TOGETHER_NICKNAME_MIN_LENGTH =
    moe.ouom.neriplayer.data.ltw.profile.LISTEN_TOGETHER_NICKNAME_MIN_LENGTH
const val LISTEN_TOGETHER_NICKNAME_MAX_LENGTH =
    moe.ouom.neriplayer.data.ltw.profile.LISTEN_TOGETHER_NICKNAME_MAX_LENGTH

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

fun sanitizeListenTogetherNicknameOrNull(nickname: String?): String? =
    moe.ouom.neriplayer.data.ltw.profile.sanitizeListenTogetherNicknameOrNull(nickname)

fun requireValidListenTogetherNickname(nickname: String, formatValidationError: (ListenTogetherValidationError) -> String): String {
    val normalized = nickname.trim()
    validateListenTogetherNickname(normalized)?.let { error(formatValidationError(it)) }
    return normalized
}
