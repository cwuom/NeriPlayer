package moe.ouom.neriplayer.platform.youtube.api.playback

import java.io.IOException

/**
 * WEB_REMIX 缺 GVS PoToken 时拿不到 token 的原因
 *
 * [code] 会出现在日志和用户提示里, 只能是固定短码, 不能带 token、visitorData、URL 或 cookie
 */
enum class YouTubePoTokenUnavailableReason(val code: String) {
    /** provider 已经返回, 但给的是空 token */
    EMPTY("empty"),

    /** 在等待预算内 token 还没铸好 */
    TIMEOUT("timeout")
}

/**
 * 解析失败的直接原因是 GVS PoToken 缺失, 让调用方能和普通的"拿不到地址"区分开
 */
class YouTubePoTokenUnavailableException(
    val reason: YouTubePoTokenUnavailableReason
) : IOException("YouTube GVS PO token unavailable: ${reason.code}")
