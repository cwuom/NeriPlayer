package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePoTokenUnavailableException
import moe.ouom.neriplayer.platform.youtube.api.playback.YouTubePoTokenUnavailableReason

/**
 * YouTube 解析抛错时给用户看的提示
 *
 * 缺 PoToken 要单独说明, 否则用户只看到泛泛的"获取播放地址失败", 会误以为是网络断了
 */
internal fun youTubeResolveFailureMessage(
    error: Throwable,
    getLocalizedString: (resId: Int, arg: String) -> String
): String = when (error) {
    is YouTubePoTokenUnavailableException -> getLocalizedString(
        error.reason.messageRes(),
        error.reason.code
    )
    else -> getLocalizedString(
        CoreCommonR.string.player_playback_url_error_detail,
        error.message.orEmpty()
    )
}

private fun YouTubePoTokenUnavailableReason.messageRes(): Int = when (this) {
    YouTubePoTokenUnavailableReason.EMPTY -> CoreCommonR.string.player_youtube_po_token_unavailable_empty
    YouTubePoTokenUnavailableReason.TIMEOUT -> CoreCommonR.string.player_youtube_po_token_unavailable_timeout
}
