package moe.ouom.neriplayer.data.ltw.profile

const val LISTEN_TOGETHER_NICKNAME_MIN_LENGTH =
    moe.ouom.neriplayer.listentogether.profile.LISTEN_TOGETHER_NICKNAME_MIN_LENGTH
const val LISTEN_TOGETHER_NICKNAME_MAX_LENGTH =
    moe.ouom.neriplayer.listentogether.profile.LISTEN_TOGETHER_NICKNAME_MAX_LENGTH

fun buildListenTogetherUserUuid(): String =
    moe.ouom.neriplayer.listentogether.profile.buildListenTogetherUserUuid()

fun buildDefaultListenTogetherNickname(): String =
    moe.ouom.neriplayer.listentogether.profile.buildDefaultListenTogetherNickname()

fun sanitizeListenTogetherNicknameOrNull(nickname: String?): String? =
    moe.ouom.neriplayer.listentogether.profile.sanitizeListenTogetherNicknameOrNull(nickname)

fun isValidListenTogetherNickname(value: String): Boolean =
    moe.ouom.neriplayer.listentogether.profile.isValidListenTogetherNickname(value)
