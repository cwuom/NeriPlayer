package moe.ouom.neriplayer.platform.netease.api.request

import java.nio.charset.StandardCharsets
import moe.ouom.neriplayer.common.json.JsonUtil

/*
 * 听歌打卡参照 NeteaseCloudMusicApiEnhanced/api-enhanced 的 module/scrobble.js：
 * 旧的 weapi/feedback/weblog 会返回 success 但服务端直接丢弃，
 * 目前只有以桌面客户端（os=osx）身份发往 clientlog 的 eapi 日志会计入听歌排行。
 */
internal const val NETEASE_SCROBBLE_URL = "https://clientlog.music.163.com/eapi/feedback/weblog"
internal const val NETEASE_SCROBBLE_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

// 没有可靠的歌单来源时与 YesPlayMusic 一致传 0
private const val NETEASE_SCROBBLE_NO_SOURCE = "0"

internal data class NeteaseScrobbleIdentity(
    val musicU: String,
    val csrf: String,
    val deviceId: String
)

/**
 * startplay 写入"最近播放"，play 累计播放次数与听歌排行；
 * 与 api-enhanced 保持一致，两条日志分两次请求发送。
 */
internal fun buildNeteaseScrobbleLogs(songId: Long, listenedSec: Long): List<String> {
    require(songId > 0L) { "songId must be positive" }
    val id = songId.toString()
    val content = "id=$NETEASE_SCROBBLE_NO_SOURCE"
    val startPlay = linkedMapOf<String, Any>(
        "action" to "startplay",
        "json" to linkedMapOf(
            "id" to id,
            "type" to "song",
            "mainsite" to "1",
            "mainsiteWeb" to "1",
            "content" to content
        )
    )
    val play = linkedMapOf<String, Any>(
        "action" to "play",
        "json" to linkedMapOf(
            "download" to 0,
            "end" to "playend",
            "id" to id,
            "sourceId" to NETEASE_SCROBBLE_NO_SOURCE,
            "time" to maxOf(1L, listenedSec),
            "type" to "song",
            "wifi" to 0,
            "source" to "list",
            "mainsite" to "1",
            "mainsiteWeb" to "1",
            "content" to content
        )
    )
    return listOf(startPlay, play).map { log -> JsonUtil.toJsonValue(listOf(log)) }
}

/** eapi 的 header 字段与 Cookie 头必须一致，服务端按其中的 os 判定客户端类型 */
internal fun buildNeteaseScrobbleHeader(
    identity: NeteaseScrobbleIdentity,
    nowMs: Long,
    requestSuffix: Int
): Map<String, String> = linkedMapOf(
    "osver" to "15.5",
    "deviceId" to identity.deviceId,
    "os" to "osx",
    "appver" to "3.1.10.5100",
    "versioncode" to "140",
    "mobilename" to "",
    "buildver" to (nowMs / 1000L).toString(),
    "resolution" to "1920x1080",
    "__csrf" to identity.csrf,
    "channel" to "netease",
    "requestId" to "${nowMs}_${Math.floorMod(requestSuffix, 10_000).toString().padStart(4, '0')}",
    "MUSIC_U" to identity.musicU
)

internal fun buildNeteaseScrobblePayload(log: String, header: Map<String, String>): Map<String, Any> =
    linkedMapOf("logs" to log, "e_r" to false, "header" to header)

internal fun neteaseScrobbleCookieHeader(header: Map<String, String>): String =
    header.entries.joinToString("; ") { (name, value) -> "${encodeCookiePart(name)}=${encodeCookiePart(value)}" }

private const val COOKIE_HEX_DIGITS = "0123456789ABCDEF"
private const val COOKIE_UNRESERVED = "-_.!~*'()"

// 与 api-enhanced 的 encodeURIComponent 保持一致
private fun encodeCookiePart(value: String): String = buildString {
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        val code = byte.toInt() and 0xff
        val char = code.toChar()
        if (code < 0x80 && (char.isLetterOrDigit() || char in COOKIE_UNRESERVED)) {
            append(char)
        } else {
            append('%').append(COOKIE_HEX_DIGITS[code shr 4]).append(COOKIE_HEX_DIGITS[code and 0x0f])
        }
    }
}
