package moe.ouom.neriplayer.util.search

import android.content.Context
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName

private const val TITLE_BIAS = 0
private const val TITLE_VARIANT_BIAS = 4
private const val ARTIST_BIAS = 24
private const val ARTIST_VARIANT_BIAS = 28
private const val ALBUM_BIAS = 40
private const val ALBUM_VARIANT_BIAS = 44
private const val ORIGINAL_TITLE_BIAS = 52
private const val ORIGINAL_ARTIST_BIAS = 56
private const val FILE_NAME_BIAS = 64
private const val FILE_PATH_BIAS = 72
private const val IDENTIFIER_BIAS = 84

fun SongItem.playlistSearchValues(context: Context): List<Any?> {
    return buildList {
        add(SearchTextMatcher.value(displayName(), TITLE_BIAS))
        add(SearchTextMatcher.value(name, TITLE_VARIANT_BIAS))
        add(SearchTextMatcher.value(customName, TITLE_VARIANT_BIAS))
        add(SearchTextMatcher.value(displayArtist(), ARTIST_BIAS))
        add(SearchTextMatcher.value(artist, ARTIST_VARIANT_BIAS))
        add(SearchTextMatcher.value(customArtist, ARTIST_VARIANT_BIAS))
        add(SearchTextMatcher.value(displayAlbum(context), ALBUM_BIAS))
        add(SearchTextMatcher.value(album, ALBUM_VARIANT_BIAS))
        add(SearchTextMatcher.value(originalName, ORIGINAL_TITLE_BIAS))
        add(SearchTextMatcher.value(originalArtist, ORIGINAL_ARTIST_BIAS))
        add(SearchTextMatcher.value(localFileName, FILE_NAME_BIAS))
        add(SearchTextMatcher.value(localFilePath, FILE_PATH_BIAS))
        add(SearchTextMatcher.value(channelId, IDENTIFIER_BIAS))
        add(SearchTextMatcher.value(audioId, IDENTIFIER_BIAS))
        add(SearchTextMatcher.value(subAudioId, IDENTIFIER_BIAS))
        neteaseArtists
            ?.map { artistSummary -> SearchTextMatcher.value(artistSummary.name, ARTIST_BIAS) }
            ?.let { addAll(it) }
    }
}

fun SongItem.searchValues(): List<Any?> {
    return buildList {
        add(SearchTextMatcher.value(displayName(), TITLE_BIAS))
        add(SearchTextMatcher.value(name, TITLE_VARIANT_BIAS))
        add(SearchTextMatcher.value(customName, TITLE_VARIANT_BIAS))
        add(SearchTextMatcher.value(displayArtist(), ARTIST_BIAS))
        add(SearchTextMatcher.value(artist, ARTIST_VARIANT_BIAS))
        add(SearchTextMatcher.value(customArtist, ARTIST_VARIANT_BIAS))
        add(SearchTextMatcher.value(album, ALBUM_BIAS))
        add(SearchTextMatcher.value(originalName, ORIGINAL_TITLE_BIAS))
        add(SearchTextMatcher.value(originalArtist, ORIGINAL_ARTIST_BIAS))
        add(SearchTextMatcher.value(localFileName, FILE_NAME_BIAS))
        add(SearchTextMatcher.value(localFilePath, FILE_PATH_BIAS))
        add(SearchTextMatcher.value(channelId, IDENTIFIER_BIAS))
        add(SearchTextMatcher.value(audioId, IDENTIFIER_BIAS))
        add(SearchTextMatcher.value(subAudioId, IDENTIFIER_BIAS))
        neteaseArtists
            ?.map { artistSummary -> SearchTextMatcher.value(artistSummary.name, ARTIST_BIAS) }
            ?.let { addAll(it) }
    }
}
