package moe.ouom.neriplayer.data.sync.merge.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat

internal fun mergePlaylistUsageMetadata(newer: SyncPlaylistUsageStat, older: SyncPlaylistUsageStat): SyncPlaylistUsageStat {
    val presentation = mergePlaylistUsagePresentation(newer, older)
    return presentation.copy(
        fid = nonZeroUsageId(newer.fid, older.fid),
        mid = nonZeroUsageId(newer.mid, older.mid),
        browseId = newer.browseId ?: older.browseId,
        playlistId = newer.playlistId ?: older.playlistId,
        subtitle = newer.subtitle ?: older.subtitle
    )
}

private fun mergePlaylistUsagePresentation(newer: SyncPlaylistUsageStat, older: SyncPlaylistUsageStat): SyncPlaylistUsageStat =
    newer.copy(
        source = newer.source.ifBlank { older.source },
        id = nonZeroUsageId(newer.id, older.id),
        subtype = newer.subtype ?: older.subtype,
        name = newer.name.ifBlank { older.name },
        coverUrl = newer.coverUrl ?: older.coverUrl,
        trackCount = maxOf(newer.trackCount, older.trackCount).coerceAtLeast(0)
    )

private fun nonZeroUsageId(newer: Long, older: Long): Long = if (newer != 0L) newer else older
