package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels

fun buildStableTrackKey(
    channelId: String,
    audioId: String,
    subAudioId: String? = null,
    playlistContextId: String? = null
): String {
    return when (channelId) {
        ListenTogetherChannels.BILIBILI -> {
            listOf(channelId, audioId, subAudioId).filterNot { it.isNullOrBlank() }.joinToString(":")
        }

        ListenTogetherChannels.YOUTUBE_MUSIC -> {
            listOf(channelId, audioId, playlistContextId).filterNot { it.isNullOrBlank() }.joinToString(":")
        }

        else -> "$channelId:$audioId"
    }
}
