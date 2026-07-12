package moe.ouom.neriplayer.data.local.playlist.model

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtistSummaryTest {

    @Test
    fun `slash separated duet belongs to each local artist`() {
        val duet = song(
            id = 1L,
            name = "花的共用世界",
            artist = "yihuik苡慧 / 吴炳文"
        )

        val summaries = buildLocalArtistSummaries(
            songs = listOf(duet),
            unknownArtist = "Unknown Artist"
        ).associateBy { artist -> artist.name }

        assertTrue("yihuik苡慧" in summaries)
        assertTrue("吴炳文" in summaries)
        assertFalse("yihuik苡慧 / 吴炳文" in summaries)
        assertEquals(listOf(duet), summaries.getValue("yihuik苡慧").songs)
        assertEquals(listOf(duet), summaries.getValue("吴炳文").songs)
    }

    @Test
    fun `compact slash artist name is not split`() {
        assertEquals(
            listOf("AC/DC"),
            splitLocalArtistNames("AC/DC", unknownArtist = "Unknown Artist")
        )
    }

    @Test
    fun `compact slash collaboration is split into every artist`() {
        assertEquals(
            listOf("尹美莱", "Tiger JK", "Bizzy"),
            splitLocalArtistNames("尹美莱/Tiger JK/Bizzy", unknownArtist = "Unknown Artist")
        )
    }

    @Test
    fun `mixed spaced and compact slash collaboration is fully split`() {
        assertEquals(
            listOf("尹美莱", "Tiger JK", "Bizzy"),
            splitLocalArtistNames("尹美莱 / Tiger JK/Bizzy", unknownArtist = "Unknown Artist")
        )
    }

    @Test
    fun `structured netease artists are used before raw artist text`() {
        val duet = song(
            id = 2L,
            name = "是多遗憾",
            artist = "吴俊佑 / 庄淇玟(29#)",
            neteaseArtists = listOf(
                NeteaseArtistSummary(id = 100L, name = "吴俊佑"),
                NeteaseArtistSummary(id = 101L, name = "庄淇玟(29#)")
            )
        )

        val names = buildLocalArtistSummaries(
            songs = listOf(duet),
            unknownArtist = "Unknown Artist"
        ).map { artist -> artist.name }.toSet()

        assertEquals(setOf("吴俊佑", "庄淇玟(29#)"), names)
    }

    @Test
    fun `downloaded local file rescans are collapsed in artist summaries`() {
        val contentEntry = song(
            id = 10L,
            name = "晴天",
            artist = "周杰伦",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            durationMs = 269_000L,
            mediaUri = "content://media/external/audio/media/10",
            localFileName = "周杰伦 - 晴天.mp3"
        )
        val fileEntry = song(
            id = 11L,
            name = "晴天",
            artist = "周杰伦",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            durationMs = 269_000L,
            mediaUri = "/storage/emulated/0/Music/NeriPlayer/周杰伦 - 晴天.mp3",
            localFileName = "周杰伦 - 晴天.mp3",
            localFilePath = "/storage/emulated/0/Music/NeriPlayer/周杰伦 - 晴天.mp3"
        )

        val summary = buildLocalArtistSummaries(
            songs = listOf(contentEntry, fileEntry),
            unknownArtist = "Unknown Artist"
        ).single()

        assertEquals("周杰伦", summary.name)
        assertEquals(listOf(contentEntry), summary.songs)
    }

    @Test
    fun `downloaded local copy does not duplicate original remote song in artist summaries`() {
        val remoteEntry = song(
            id = 20L,
            name = "一路向北",
            artist = "周杰伦",
            album = "netease",
            durationMs = 295_000L,
            channelId = "netease",
            audioId = "18888"
        )
        val localCopy = song(
            id = 21L,
            name = "一路向北",
            artist = "周杰伦",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            durationMs = 295_000L,
            mediaUri = "/storage/emulated/0/Music/NeriPlayer/周杰伦 - 一路向北.mp3",
            localFileName = "周杰伦 - 一路向北.mp3",
            localFilePath = "/storage/emulated/0/Music/NeriPlayer/周杰伦 - 一路向北.mp3"
        )

        val summary = buildLocalArtistSummaries(
            songs = listOf(remoteEntry, localCopy),
            unknownArtist = "Unknown Artist"
        ).single()

        assertEquals("周杰伦", summary.name)
        assertEquals(listOf(remoteEntry), summary.songs)
    }

    @Test
    fun `blank artist falls back to unknown artist`() {
        assertEquals(
            listOf("Unknown Artist"),
            splitLocalArtistNames("  ", unknownArtist = "Unknown Artist")
        )
    }

    private fun song(
        id: Long,
        name: String,
        artist: String,
        album: String = "album",
        durationMs: Long = 0L,
        mediaUri: String? = null,
        localFileName: String? = null,
        localFilePath: String? = null,
        channelId: String? = null,
        audioId: String? = null,
        neteaseArtists: List<NeteaseArtistSummary>? = emptyList()
    ): SongItem {
        val isLocalReference = mediaUri != null || localFilePath != null
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = album,
            albumId = 1L,
            durationMs = durationMs,
            coverUrl = null,
            mediaUri = mediaUri,
            localFileName = localFileName,
            localFilePath = localFilePath,
            channelId = channelId ?: if (isLocalReference) "local" else null,
            audioId = audioId ?: if (isLocalReference) id.toString() else null,
            neteaseArtists = neteaseArtists,
            addedAt = id
        )
    }
}
