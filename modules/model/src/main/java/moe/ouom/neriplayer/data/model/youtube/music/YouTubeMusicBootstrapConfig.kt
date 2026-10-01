package moe.ouom.neriplayer.data.model.youtube.music

data class YouTubeMusicBootstrapConfig(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val sessionIndex: String,
    val loggedIn: Boolean,
    val userSessionId: String,
    val cookieHeader: String,
    val authFingerprint: String,
    val webUserAgent: String,
    val fetchedAtMs: Long
)
