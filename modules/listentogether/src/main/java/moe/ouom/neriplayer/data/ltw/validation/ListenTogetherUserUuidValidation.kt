package moe.ouom.neriplayer.data.ltw.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import moe.ouom.neriplayer.common.R as CoreCommonR

private val USER_UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

fun validateListenTogetherUserUuid(userUuid: String): ListenTogetherValidationError? {
    val normalized = userUuid.trim()
    return when {
        normalized.isBlank() -> {
            ListenTogetherValidationError(CoreCommonR.string.listen_together_error_user_uuid_required)
        }

        !USER_UUID_REGEX.matches(normalized) -> {
            ListenTogetherValidationError(CoreCommonR.string.listen_together_error_user_uuid_invalid)
        }

        else -> null
    }
}

fun requireValidListenTogetherUserUuid(userUuid: String, formatValidationError: (ListenTogetherValidationError) -> String): String {
    val normalized = userUuid.trim()
    validateListenTogetherUserUuid(normalized)?.let { error(formatValidationError(it)) }
    return normalized.lowercase()
}
