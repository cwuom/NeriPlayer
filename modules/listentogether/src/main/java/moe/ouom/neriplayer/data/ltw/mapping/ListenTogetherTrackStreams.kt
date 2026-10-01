package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

fun ListenTogetherTrack.withStreamUrl(streamUrl: String?): ListenTogetherTrack =
    withStreamUrls(listOfNotNull(streamUrl))

fun ListenTogetherTrack.withStreamUrls(streamUrls: List<String>?): ListenTogetherTrack {
    val trustedStreamUrls = trustedListenTogetherStreamUrls(
        channelId = channelId,
        streamUrls = streamUrls,
        legacyStreamUrl = null
    )
    val primaryStreamUrl = trustedStreamUrls.firstOrNull()
    if (primaryStreamUrl == streamUrl && trustedStreamUrls == this.streamUrls) return this
    return copy(streamUrl = primaryStreamUrl, streamUrls = trustedStreamUrls)
}
