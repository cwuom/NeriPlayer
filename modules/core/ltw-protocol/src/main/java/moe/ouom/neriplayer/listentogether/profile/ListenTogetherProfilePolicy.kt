package moe.ouom.neriplayer.listentogether.profile

import java.util.UUID

const val LISTEN_TOGETHER_NICKNAME_MIN_LENGTH = 1
const val LISTEN_TOGETHER_NICKNAME_MAX_LENGTH = 24

fun buildListenTogetherUserUuid(): String = UUID.randomUUID().toString()

fun buildDefaultListenTogetherNickname(): String =
    "Neri${UUID.randomUUID().toString().replace("-", "").take(6).uppercase()}"

fun sanitizeListenTogetherNicknameOrNull(nickname: String?): String? {
    val normalized = nickname?.trim().orEmpty()
    return normalized.takeIf {
        it.length in LISTEN_TOGETHER_NICKNAME_MIN_LENGTH..LISTEN_TOGETHER_NICKNAME_MAX_LENGTH &&
            isValidListenTogetherNickname(it)
    }
}

fun isValidListenTogetherNickname(value: String): Boolean {
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
    return isAsciiLetterOrDigit(codePoint) ||
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

private fun isAsciiLetterOrDigit(codePoint: Int): Boolean =
    isAsciiDigit(codePoint) || isAsciiUppercase(codePoint) || isAsciiLowercase(codePoint)

private fun isAsciiDigit(codePoint: Int): Boolean = codePoint in '0'.code..'9'.code

private fun isAsciiUppercase(codePoint: Int): Boolean = codePoint in 'A'.code..'Z'.code

private fun isAsciiLowercase(codePoint: Int): Boolean = codePoint in 'a'.code..'z'.code
