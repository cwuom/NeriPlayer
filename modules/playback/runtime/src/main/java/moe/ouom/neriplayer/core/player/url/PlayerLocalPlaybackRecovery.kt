package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.data.identity.recoverNeteaseRemoteSourceFromStaleLocalCopy
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem

internal fun recoverNeteaseSourceAfterMissingLocal(
    song: SongItem,
    rawLocalReference: String?,
    explicitLocalFileReference: (String) -> Boolean? = LocalPlaylistRepository::peekExplicitLocalFileReference
): SongItem? {
    val reference = rawLocalReference ?: return null
    if (explicitLocalFileReference(reference) != false) return null
    return song.recoverNeteaseRemoteSourceFromStaleLocalCopy()
}
