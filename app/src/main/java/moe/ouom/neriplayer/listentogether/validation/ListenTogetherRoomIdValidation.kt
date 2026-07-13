package moe.ouom.neriplayer.listentogether.validation

import moe.ouom.neriplayer.R

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
                messageResId = R.string.listen_together_error_room_id_length,
                args = listOf(LISTEN_TOGETHER_ROOM_ID_LENGTH)
            )
        }

        !ROOM_ID_REGEX.matches(normalized) -> {
            ListenTogetherValidationError(R.string.listen_together_error_room_id_chars)
        }

        else -> null
    }
}

fun requireValidListenTogetherRoomId(roomId: String): String {
    val normalized = normalizeListenTogetherRoomId(roomId)
    validateListenTogetherRoomId(normalized)?.let { error(it.formatForApp()) }
    return normalized
}
