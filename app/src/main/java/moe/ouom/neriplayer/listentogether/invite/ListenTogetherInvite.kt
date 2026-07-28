package moe.ouom.neriplayer.listentogether.invite

data class ListenTogetherInvite(
    val roomId: String,
    val inviterNickname: String? = null,
    val baseUrl: String? = null,
    val joinSecret: String? = null,
    val hasInvalidBaseUrl: Boolean = false
) {
    val signature: String
        get() = listOf(
            roomId,
            inviterNickname.orEmpty(),
            baseUrl.orEmpty(),
            joinSecret.orEmpty(),
            hasInvalidBaseUrl.toString()
        ).joinToString("|")
}
