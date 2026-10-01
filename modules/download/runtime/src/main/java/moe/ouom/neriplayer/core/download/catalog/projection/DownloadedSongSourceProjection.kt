package moe.ouom.neriplayer.core.download.catalog.projection

import moe.ouom.neriplayer.data.identity.stableKey

import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.core.download.policy.remoteSourceIdentityOrNull as downloadedRemoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey

internal class DownloadedSongSourceProjection(
    private val existing: DownloadedSong,
    private val updated: SongItem
) {
    private val existingSource = existing.downloadedRemoteSourceIdentityOrNull()
    private val source = existingSource ?: updated.remoteSourceIdentityOrNull()
    private val updatedChannel = remoteChannel(updated.channelId)

    val preservesExistingSource: Boolean
        get() = existingSource != null

    val id: Long
        get() = if (preservesExistingSource) existing.id else updated.id

    val stableKey: String?
        get() = source?.stableKey() ?: updated.stableKey().ifBlank { existing.stableKey }

    val identityAlbum: String?
        get() = source?.album ?: existing.sourceIdentityAlbum

    val mediaUri: String?
        get() = source?.mediaUri ?: existing.sourceMediaUri

    val channelId: String?
        get() = remoteChannel(existing.sourceChannelId) ?: updatedChannel ?: source?.album

    val audioId: String?
        get() = normalizedId(existing.sourceAudioId)
            ?: updatedRemoteId(updated.audioId)
            ?: neteaseSourceId()

    val subAudioId: String?
        get() = normalizedId(existing.sourceSubAudioId) ?: updatedRemoteId(updated.subAudioId)

    val playlistContextId: String?
        get() = existing.sourcePlaylistContextId ?: updated.playlistContextId

    private fun updatedRemoteId(value: String?): String? {
        if (updatedChannel == null) return null
        return normalizedId(value)
    }

    private fun neteaseSourceId(): String? {
        if (!channelId.equals("netease", ignoreCase = true)) return null
        return source?.id?.toString()
    }
}

private fun remoteChannel(value: String?): String? {
    val channel = normalizedId(value) ?: return null
    return channel.takeUnless { it.equals("local", ignoreCase = true) }
}

private fun normalizedId(value: String?): String? {
    val normalized = value?.trim() ?: return null
    return normalized.ifBlank { null }
}
