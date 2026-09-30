package moe.ouom.neriplayer.data.ltw.validation

import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

import moe.ouom.neriplayer.core.common.R as CoreCommonR

private val ROOM_ID_REGEX = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$")

const val LISTEN_TOGETHER_ROOM_ID_LENGTH = 6

fun normalizeListenTogetherRoomId(value: String): String {
    return value.trim().uppercase()
}

fun validateListenTogetherRoomId(roomId: String): ListenTogetherValidationError? {
    val normalized = normalizeListenTogetherRoomId(roomId)
    return when {
        normalized.length != LISTEN_TOGETHER_ROOM_ID_LENGTH -> {
            ListenTogetherValidationError(
                messageResId = CoreCommonR.string.listen_together_error_room_id_length,
                args = listOf(LISTEN_TOGETHER_ROOM_ID_LENGTH)
            )
        }

        !ROOM_ID_REGEX.matches(normalized) -> {
            ListenTogetherValidationError(CoreCommonR.string.listen_together_error_room_id_chars)
        }

        else -> null
    }
}

fun requireValidListenTogetherRoomId(roomId: String, formatValidationError: (ListenTogetherValidationError) -> String): String {
    val normalized = normalizeListenTogetherRoomId(roomId)
    validateListenTogetherRoomId(normalized)?.let { error(formatValidationError(it)) }
    return normalized
}
