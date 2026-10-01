package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.database.entity.stats.PlaylistUsageEntity
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.usageKey

internal fun UsageEntry.toEntity(): PlaylistUsageEntity {
    return PlaylistUsageEntity(
        usageKey = usageKey(),
        id = id,
        name = name,
        picUrl = picUrl,
        trackCount = trackCount,
        source = source,
        lastOpened = lastOpened,
        openCount = openCount,
        firstOpened = firstOpened,
        counterBaseOpenCount = counterBaseOpenCount,
        fid = fid,
        mid = mid,
        browseId = browseId,
        playlistId = playlistId,
        subtype = subtype,
        subtitle = subtitle
    )
}
