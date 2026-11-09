package moe.ouom.neriplayer.data.ltw.mapping

import moe.ouom.neriplayer.data.model.ltw.track.listenTogetherTrackKey

fun buildStableTrackKey(channelId: String, audioId: String, subAudioId: String? = null, playlistContextId: String? = null): String =
    listenTogetherTrackKey(channelId, audioId, subAudioId, playlistContextId)
