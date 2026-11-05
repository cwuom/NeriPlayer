package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.model.SongItem

internal class SubsonicCoverProjection(private val projectUrl: (String?) -> String?) {
    fun page(source: ServerBrowsePage): ServerBrowsePage {
        val albums = source.albums.map { album ->
            val cover = cover(album.coverUrl)
            if (cover == album.coverUrl) album else album.copy(coverUrl = cover)
        }
        val songs = source.songs.map(::song)
        val artists = source.artists.map(::artist)
        val artwork = source.artistArtwork?.mapValues { (_, raw) -> cover(raw) ?: raw }
        if (albums == source.albums && songs == source.songs && artists == source.artists && artwork == source.artistArtwork) return source
        return source.copy(albums = albums, songs = songs, artists = artists, artistArtwork = artwork)
    }

    fun artist(source: ServerArtist): ServerArtist {
        val cover = cover(source.coverUrl)
        val fallback = cover(source.fallbackCoverUrl)
        if (cover == source.coverUrl && fallback == source.fallbackCoverUrl) return source
        return source.copy(coverUrl = cover, fallbackCoverUrl = fallback)
    }

    private fun song(source: SongItem): SongItem {
        val cover = cover(source.coverUrl)
        val custom = cover(source.customCoverUrl)
        val original = cover(source.originalCoverUrl)
        if (cover == source.coverUrl && custom == source.customCoverUrl && original == source.originalCoverUrl) return source
        return source.copy(coverUrl = cover, customCoverUrl = custom, originalCoverUrl = original)
    }

    private fun cover(raw: String?): String? = raw?.let { projectUrl(it) ?: it }
}
