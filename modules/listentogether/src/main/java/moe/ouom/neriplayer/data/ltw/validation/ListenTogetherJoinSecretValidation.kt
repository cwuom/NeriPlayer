package moe.ouom.neriplayer.data.ltw.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import moe.ouom.neriplayer.common.R as CoreCommonR

const val LISTEN_TOGETHER_JOIN_SECRET_MAX_LENGTH = 256

fun validateListenTogetherJoinSecret(value: String?): ListenTogetherValidationError? {
    val normalized = value?.trim().orEmpty()
    return when {
        normalized.isBlank() -> {
            ListenTogetherValidationError(CoreCommonR.string.listen_together_error_join_secret_required)
        }

        normalized.length > LISTEN_TOGETHER_JOIN_SECRET_MAX_LENGTH -> {
            ListenTogetherValidationError(
                messageResId = CoreCommonR.string.listen_together_error_join_secret_length,
                args = listOf(LISTEN_TOGETHER_JOIN_SECRET_MAX_LENGTH)
            )
        }

        else -> null
    }
}

fun sanitizeListenTogetherJoinSecretOrNull(value: String?): String? {
    val normalized = value?.trim().orEmpty()
    return normalized.takeIf { validateListenTogetherJoinSecret(it) == null }
}

fun requireValidListenTogetherJoinSecret(value: String?, formatValidationError: (ListenTogetherValidationError) -> String): String {
    val normalized = value?.trim().orEmpty()
    validateListenTogetherJoinSecret(normalized)?.let { error(formatValidationError(it)) }
    return normalized
}
