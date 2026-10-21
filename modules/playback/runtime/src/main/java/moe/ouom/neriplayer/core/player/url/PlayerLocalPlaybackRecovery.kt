package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.data.identity.recoverNeteaseRemoteSourceFromStaleLocalCopy
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem

internal fun recoverNeteaseSourceAfterMissingLocal(
    song: SongItem,
    rawLocalReference: String?,
    sourceContext: ResolvedLocalMediaSource? = null,
    explicitLocalFileReference: (String) -> Boolean? = LocalPlaylistRepository::peekExplicitLocalFileReference
): SongItem? {
    val reference = rawLocalReference ?: return null
    val snapshotReference = LocalSongSupport.validExplicitLocalSourceReference(song)
    fun explicitEvidence(candidate: String): Boolean? =
        if (snapshotReference == candidate) true else explicitLocalFileReference(candidate)

    val unverifiedDeclaredReference = sourceContext?.unverifiedDeclaredReferenceFor(song, reference)
    if (unverifiedDeclaredReference != null && explicitEvidence(unverifiedDeclaredReference) != false) return null
    val provenanceReference = sourceContext?.anchorFor(song, reference) ?: reference
    if (explicitEvidence(provenanceReference) != false) return null
    return song.recoverNeteaseRemoteSourceFromStaleLocalCopy()
}
