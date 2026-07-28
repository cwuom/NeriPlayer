package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull

internal fun SongItem?.isShareableForListenTogether(): Boolean {
    return this?.toListenTogetherTrackOrNull() != null
}

internal fun List<SongItem>.hasShareableListenTogetherTrackAt(index: Int): Boolean {
    return getOrNull(index).isShareableForListenTogether()
}
