package moe.ouom.neriplayer.listentogether

import java.util.Locale

private val ROOM_ID_REGEX = Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$")
private val USER_UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

const val LISTEN_TOGETHER_ROOM_ID_LENGTH = 6
const val LISTEN_TOGETHER_NICKNAME_MIN_LENGTH = 1
const val LISTEN_TOGETHER_NICKNAME_MAX_LENGTH = 24

fun normalizeListenTogetherRoomId(value: String): String {
    return value.trim().uppercase()
}

fun validateListenTogetherRoomId(roomId: String): String? {
    val normalized = normalizeListenTogetherRoomId(roomId)
    return when {
        normalized.length != LISTEN_TOGETHER_ROOM_ID_LENGTH -> {
            if (isChineseLocale()) {
                "房间 ID 需要为 $LISTEN_TOGETHER_ROOM_ID_LENGTH 位。"
            } else {
                "Room ID must be $LISTEN_TOGETHER_ROOM_ID_LENGTH characters."
            }
        }

        !ROOM_ID_REGEX.matches(normalized) -> {
            if (isChineseLocale()) {
                "房间 ID 仅支持大写字母和数字。"
            } else {
                "Room ID only supports uppercase letters and digits."
            }
        }

        else -> null
    }
}

fun validateListenTogetherUserUuid(userUuid: String): String? {
    val normalized = userUuid.trim()
    return when {
        normalized.isBlank() -> {
            if (isChineseLocale()) {
                "用户 UUID 不能为空。"
            } else {
                "User UUID is required."
            }
        }

        !USER_UUID_REGEX.matches(normalized) -> {
            if (isChineseLocale()) {
                "用户 UUID 格式无效。"
            } else {
                "User UUID format is invalid."
            }
        }

        else -> null
    }
}

fun validateListenTogetherNickname(nickname: String): String? {
    val normalized = nickname.trim()
    return when {
        normalized.length !in LISTEN_TOGETHER_NICKNAME_MIN_LENGTH..LISTEN_TOGETHER_NICKNAME_MAX_LENGTH -> {
            if (isChineseLocale()) {
                "当前昵称长度需要为 $LISTEN_TOGETHER_NICKNAME_MIN_LENGTH-$LISTEN_TOGETHER_NICKNAME_MAX_LENGTH 位。"
            } else {
                "Nickname length must be $LISTEN_TOGETHER_NICKNAME_MIN_LENGTH-$LISTEN_TOGETHER_NICKNAME_MAX_LENGTH characters."
            }
        }

        !isValidListenTogetherNickname(normalized) -> {
            if (isChineseLocale()) {
                "当前昵称仅支持汉字、英文字母和数字。"
            } else {
                "Nickname only supports Chinese characters, letters, and digits."
            }
        }

        else -> null
    }
}

fun sanitizeListenTogetherNicknameOrNull(nickname: String?): String? {
    val normalized = nickname?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return normalized.takeIf { validateListenTogetherNickname(it) == null }
}

fun requireValidListenTogetherRoomId(roomId: String): String {
    val normalized = normalizeListenTogetherRoomId(roomId)
    validateListenTogetherRoomId(normalized)?.let(::error)
    return normalized
}

fun requireValidListenTogetherUserUuid(userUuid: String): String {
    val normalized = userUuid.trim()
    validateListenTogetherUserUuid(normalized)?.let(::error)
    return normalized.lowercase()
}

fun requireValidListenTogetherNickname(nickname: String): String {
    val normalized = nickname.trim()
    validateListenTogetherNickname(normalized)?.let(::error)
    return normalized
}

private fun isChineseLocale(): Boolean {
    return Locale.getDefault().language.startsWith("zh", ignoreCase = true)
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
