package moe.ouom.neriplayer.listentogether.validation

import moe.ouom.neriplayer.R

const val LISTEN_TOGETHER_JOIN_SECRET_MAX_LENGTH = 256

fun validateListenTogetherJoinSecret(value: String?): ListenTogetherValidationError? {
    val normalized = value?.trim().orEmpty()
    return when {
        normalized.isBlank() -> {
            ListenTogetherValidationError(R.string.listen_together_error_join_secret_required)
        }

        normalized.length > LISTEN_TOGETHER_JOIN_SECRET_MAX_LENGTH -> {
            ListenTogetherValidationError(
                messageResId = R.string.listen_together_error_join_secret_length,
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

fun requireValidListenTogetherJoinSecret(value: String?): String {
    val normalized = value?.trim().orEmpty()
    validateListenTogetherJoinSecret(normalized)?.let { error(it.formatForApp()) }
    return normalized
}
