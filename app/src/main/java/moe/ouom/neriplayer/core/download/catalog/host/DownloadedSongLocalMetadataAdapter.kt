package moe.ouom.neriplayer.core.download.catalog.host

import moe.ouom.neriplayer.core.download.catalog.assembly.DownloadedSongLocalMetadata
import moe.ouom.neriplayer.data.model.local.LocalMediaDetails

internal fun LocalMediaDetails.toSongLocalMetadata(): DownloadedSongLocalMetadata =
    DownloadedSongLocalMetadata(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUri = coverUri,
        lyricContent = lyricContent,
        originalTitle = originalTitle,
        originalArtist = originalArtist,
        sourceStableKey = sourceStableKey
    )
