package moe.ouom.neriplayer.ui.viewmodel.playlist

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.data.model.youtube.cache.CachedYouTubeMusicPlaylistTrack
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicPlaylistDetail
import moe.ouom.neriplayer.ui.viewmodel.youtube.YouTubeMusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPlaylistDetailMappingTest {

    private val fallback = YouTubeMusicPlaylist(
        browseId = "VLPL1",
        playlistId = "PL1",
        title = "Fallback",
        subtitle = "Fallback subtitle",
        coverUrl = "https://fallback.jpg",
        trackCount = 9,
        creatorName = " Creator "
    )

    @Test
    fun `usable tracks need both a video id and a name`() {
        assertFalse(detail(tracks = emptyList()).hasUsableTracks())
        assertFalse(
            detail(tracks = listOf(track(videoId = " ", name = "Song"), track(videoId = "v1", name = ""))).hasUsableTracks()
        )
        assertTrue(detail(tracks = listOf(track(videoId = "v1", name = "Song"))).hasUsableTracks())
    }

    @Test
    fun `remote detail overrides fallback metadata only when present`() {
        val blank = detail(playlistId = "", title = "", subtitle = "", coverUrl = "", trackCount = 0, tracks = emptyList())
            .toYouTubeMusicPlaylist(fallback)
        val filled = detail(trackCount = 0, tracks = listOf(track(), track(videoId = "v2")))
            .toYouTubeMusicPlaylist(fallback)

        assertEquals(fallback, blank)
        assertEquals(
            fallback.copy(playlistId = "PL2", title = "Remote", subtitle = "Remote subtitle", coverUrl = "https://remote.jpg", trackCount = 2),
            filled
        )
        assertEquals(30, detail(trackCount = 30).toYouTubeMusicPlaylist(fallback).trackCount)
    }

    @Test
    fun `cached detail restores the creator name`() {
        val cached = CachedYouTubeMusicPlaylistDetail(
            browseId = "VLPL2",
            playlistId = "PL2",
            title = "Cached",
            subtitle = "",
            creatorName = null,
            coverUrl = "",
            trackCount = 0,
            firstPageSignature = "",
            tracks = listOf(cachedTrack())
        )

        val withoutCreator = cached.toYouTubeMusicPlaylist(fallback)
        val withCreator = cached.copy(creatorName = "  Cached Creator ").toYouTubeMusicPlaylist(fallback)

        assertEquals("Creator", withoutCreator.creatorName)
        assertEquals("Cached", withoutCreator.title)
        assertEquals("Fallback subtitle", withoutCreator.subtitle)
        assertEquals(1, withoutCreator.trackCount)
        assertEquals("Cached Creator", withCreator.creatorName)
    }

    @Test
    fun `playlist tracks become youtube songs with playlist context`() {
        val playlist = fallback.copy(creatorName = "Creator")

        val song = track(videoId = "v1", artist = "", albumName = "", coverUrl = "").toPlaylistSongItem(playlist)

        assertEquals(stableYouTubeMusicId("v1"), song.id)
        assertEquals("Creator", song.artist)
        assertEquals("Creator", song.originalArtist)
        assertEquals("Fallback", song.album)
        assertEquals(stableYouTubeMusicId("PL1"), song.albumId)
        assertEquals("https://i.ytimg.com/vi/v1/hqdefault.jpg", song.coverUrl)
        assertEquals(song.coverUrl, song.originalCoverUrl)
        assertEquals(buildYouTubeMusicMediaUri("v1", "PL1"), song.mediaUri)
        assertEquals("PL1", song.playlistContextId)
        assertEquals("youtubeMusic", song.channelId)
        assertEquals("v1", song.audioId)
        assertEquals(1_000L, song.durationMs)
    }

    @Test
    fun `cached tracks without playlist id fall back to the video`() {
        val playlist = fallback.copy(playlistId = "")

        val song = cachedTrack().toPlaylistSongItem(playlist)

        assertEquals("Artist", song.artist)
        assertEquals("Album", song.album)
        assertEquals(stableYouTubeMusicId("v9"), song.albumId)
        assertEquals("https://cached.jpg", song.coverUrl)
        assertEquals(buildYouTubeMusicMediaUri("v9"), song.mediaUri)
        assertNull(song.playlistContextId)
    }

    @Test
    fun `creator context keeps tracks when the creator is blank`() {
        val tracks = listOf(song(artist = ""))

        assertSame(tracks, applyYouTubeMusicPlaylistCreatorContext(tracks, creatorName = "  "))
    }

    @Test
    fun `creator context keeps an existing original artist`() {
        val resolved = applyYouTubeMusicPlaylistCreatorContext(
            tracks = listOf(song(artist = "").copy(originalArtist = "Original"), song(artist = "").copy(originalArtist = "")),
            creatorName = " Creator "
        )

        assertEquals(listOf("Creator", "Creator"), resolved.map { it.artist })
        assertEquals(listOf("Original", "Creator"), resolved.map { it.originalArtist })
    }

    @Test
    fun `song edits keep remote values the user never touched`() {
        val base = editedFields(song(artist = "Remote"), "remote")
        val edited = song(artist = "Edited")

        val merged = mergeYouTubeMusicSongEdits(base, edited)

        assertEquals(base.copy(customCoverUrl = null, customName = null, customArtist = null, userLyricOffsetMs = 0L), merged)
    }

    @Test
    fun `song edits replace every user editable field`() {
        val edited = editedFields(song(artist = "Edited"), "edited")

        val merged = mergeYouTubeMusicSongEdits(editedFields(song(artist = "Remote"), "remote"), edited)

        assertEquals(edited.copy(artist = "Remote"), merged)
    }

    @Test
    fun `current song edits win over local playlist edits`() {
        val base = track(videoId = "v1").toPlaylistSongItem(fallback)
        val current = base.copy(customName = "Now playing")
        val local = LocalPlaylist(id = 1L, name = "Mine", songs = mutableListOf(base.copy(customName = "Saved")))
        val otherSong = track(videoId = "v2").toPlaylistSongItem(fallback).copy(customName = "Other")

        assertEquals("Now playing", overlayYouTubeMusicUserEdits(base, current, listOf(local)).customName)
        assertEquals("Saved", overlayYouTubeMusicUserEdits(base, otherSong, listOf(local)).customName)
        assertEquals("Saved", overlayYouTubeMusicUserEdits(base, null, listOf(local)).customName)
        assertSame(base, overlayYouTubeMusicUserEdits(base, otherSong, emptyList()))
    }

    private fun detail(
        playlistId: String = "PL2",
        title: String = "Remote",
        subtitle: String = "Remote subtitle",
        coverUrl: String = "https://remote.jpg",
        trackCount: Int = 0,
        tracks: List<YouTubeMusicTrack> = listOf(track())
    ) = YouTubeMusicPlaylistDetail(
        playlistId = playlistId,
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl,
        trackCount = trackCount,
        tracks = tracks
    )

    private fun track(
        videoId: String = "v1",
        name: String = "Song",
        artist: String = "Artist",
        albumName: String = "Album",
        coverUrl: String = "https://track.jpg"
    ) = YouTubeMusicTrack(
        videoId = videoId,
        name = name,
        artist = artist,
        albumName = albumName,
        durationMs = 1_000L,
        coverUrl = coverUrl
    )

    private fun cachedTrack() = CachedYouTubeMusicPlaylistTrack(
        videoId = "v9",
        name = "Cached song",
        artist = "Artist",
        albumName = "Album",
        durationMs = 2_000L,
        coverUrl = "https://cached.jpg"
    )

    private fun song(artist: String) = SongItem(
        id = 1L,
        name = "Song",
        artist = artist,
        album = "Album",
        albumId = 1L,
        durationMs = 0L,
        coverUrl = null
    )

    private fun editedFields(song: SongItem, prefix: String) = song.copy(
        matchedLyric = "$prefix lyric",
        matchedTranslatedLyric = "$prefix translated",
        matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
        matchedSongId = "$prefix id",
        userLyricOffsetMs = 300L,
        customCoverUrl = "$prefix cover",
        customName = "$prefix name",
        customArtist = "$prefix artist",
        originalName = "$prefix original name",
        originalArtist = "$prefix original artist",
        originalCoverUrl = "$prefix original cover",
        originalLyric = "$prefix original lyric",
        originalTranslatedLyric = "$prefix original translated"
    )
}
