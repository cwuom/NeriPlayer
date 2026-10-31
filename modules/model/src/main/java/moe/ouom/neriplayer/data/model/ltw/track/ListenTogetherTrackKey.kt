package moe.ouom.neriplayer.data.model.ltw.track

fun listenTogetherTrackKey(channelId: String, audioId: String, subAudioId: String? = null, playlistContextId: String? = null): String = when (channelId) {
    ListenTogetherChannels.BILIBILI -> listOf(channelId, audioId, subAudioId).filterNot { it.isNullOrBlank() }.joinToString(":")
    ListenTogetherChannels.YOUTUBE_MUSIC -> listOf(channelId, audioId, playlistContextId).filterNot { it.isNullOrBlank() }.joinToString(":")
    else -> "$channelId:$audioId"
}
