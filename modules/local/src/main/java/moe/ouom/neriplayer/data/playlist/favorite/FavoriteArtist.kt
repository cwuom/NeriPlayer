package moe.ouom.neriplayer.data.playlist.favorite

import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist

const val FAVORITE_SOURCE_NETEASE_ARTIST = "neteaseArtist"
const val FAVORITE_SOURCE_BILI_ARTIST = "biliArtist"
const val FAVORITE_SOURCE_YOUTUBE_ARTIST = "youtubeMusicArtist"

fun isArtistFavoriteSource(source: String): Boolean = source in setOf(
    FAVORITE_SOURCE_NETEASE_ARTIST,
    FAVORITE_SOURCE_BILI_ARTIST,
    FAVORITE_SOURCE_YOUTUBE_ARTIST
)

data class FavoriteArtist(
    val id: Long,
    val name: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val browseId: String? = null,
    val subtitle: String? = null
)

internal data class FavoriteArtistMergeResult(
    val favorites: List<FavoritePlaylist>,
    val addedCount: Int
)

internal fun mergeFollowedArtistFavorites(
    existing: List<FavoritePlaylist>,
    source: String,
    artists: List<FavoriteArtist>,
    importStartedAt: Long,
    now: Long
): FavoriteArtistMergeResult {
    require(isArtistFavoriteSource(source)) { "Unsupported artist source" }
    require(importStartedAt > 0L) { "Import start time must be positive" }
    val existingById = existing.filter { it.source == source }.associateBy { it.id }
    val imported = artists.distinctBy { it.id }.mapNotNull { artist ->
        importedArtistFavorite(artist, source, existingById[artist.id], importStartedAt, now)
    }.associateBy { it.id }
    val merged = existing.map { favorite ->
        if (favorite.source == source) imported[favorite.id] ?: favorite else favorite
    } + imported.values.filterNot { it.id in existingById }
    return FavoriteArtistMergeResult(merged, imported.size)
}

private fun importedArtistFavorite(
    artist: FavoriteArtist,
    source: String,
    existing: FavoritePlaylist?,
    importStartedAt: Long,
    now: Long
): FavoritePlaylist? {
    if (artist.id == 0L || artist.name.isBlank()) return null
    if (existing != null && (!existing.isDeleted || existing.modifiedAt >= importStartedAt)) return null
    return FavoritePlaylist(
        id = artist.id,
        name = artist.name.trim(),
        coverUrl = artist.coverUrl ?: existing?.coverUrl,
        trackCount = artist.trackCount.coerceAtLeast(0),
        source = source,
        browseId = artist.browseId ?: existing?.browseId,
        subtitle = artist.subtitle ?: existing?.subtitle,
        songs = emptyList(),
        addedTime = now,
        sortOrder = now,
        modifiedAt = now
    )
}
