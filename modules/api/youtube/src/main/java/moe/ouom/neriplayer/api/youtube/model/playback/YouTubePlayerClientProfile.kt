package moe.ouom.neriplayer.api.youtube.model.playback

data class YouTubePlayerClientProfile(
    val clientId: String,
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val endpointPath: String,
    val responseField: String? = null,
    val platform: String = "MOBILE",
    val clientScreen: String = "WATCH",
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
    val wrapPlayerRequest: Boolean = false,
    val supportsAuthenticatedContext: Boolean = true,
    val includeUserAgentInContext: Boolean = false,
    val includeSignatureTimestamp: Boolean = true
)
