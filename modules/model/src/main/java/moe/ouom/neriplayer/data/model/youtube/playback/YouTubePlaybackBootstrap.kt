package moe.ouom.neriplayer.data.model.youtube.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** 存档结构变了就整份作废, 拿旧字段拼出来的 bootstrap 只会让首播失败得更难查 */
const val BOOTSTRAP_SNAPSHOT_VERSION_CURRENT = 1

@Serializable
data class YouTubePlaybackBootstrap(
    val apiKey: String,
    val webRemixClientVersion: String,
    val visitorData: String,
    val playerJsUrl: String,
    /** 整串登录 cookie 不落盘, 恢复存档时按当时的 auth 重新拼一份 */
    @Transient val cookieHeader: String = "",
    val authFingerprint: String,
    val sessionIndex: String,
    val userAgent: String,
    val remoteHost: String,
    val signatureTimestamp: Int?,
    val appInstallData: String,
    val coldConfigData: String,
    val coldHashData: String,
    val hotHashData: String,
    val deviceExperimentId: String,
    val rolloutToken: String,
    val dataSyncId: String,
    val delegatedSessionId: String,
    val userSessionId: String,
    val loggedIn: Boolean,
    val fetchedAtMs: Long,
    val version: Int = BOOTSTRAP_SNAPSHOT_VERSION_CURRENT
)
