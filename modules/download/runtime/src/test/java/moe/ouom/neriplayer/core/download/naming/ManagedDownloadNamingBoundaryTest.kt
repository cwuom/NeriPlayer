package moe.ouom.neriplayer.core.download.naming

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadNamingBoundaryTest {

    @Test
    fun `bounded file names only keep extensions that follow a non empty base`() {
        assertEquals("Song.mp3", boundManagedDownloadFileName("Song.mp3"))
        assertEquals("a".repeat(128), boundManagedDownloadFileName("a".repeat(200)))
        assertEquals("b".repeat(128), boundManagedDownloadFileName("b".repeat(200) + "."))
        assertEquals(
            "." + "c".repeat(127),
            boundManagedDownloadFileName("." + "c".repeat(200))
        )
    }

    @Test
    fun `bounded file names cap oversized extensions before trimming the base`() {
        val result = boundManagedDownloadFileName("d".repeat(150) + "." + "e".repeat(40))

        assertEquals("d".repeat(112) + "." + "e".repeat(15), result)
        assertEquals(MAX_MANAGED_DOWNLOAD_FILE_NAME_UTF8_BYTES, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `song base names prefer custom display fields and expose sub audio ids`() {
        val song = song(
            customName = "Custom Title",
            customArtist = "Custom Artist",
            audioId = "audio-1",
            subAudioId = "p2"
        )

        assertEquals(
            "Custom Title - Custom Artist - Album - netease",
            renderManagedDownloadBaseName(song)
        )
        assertEquals(
            "Custom Title - Custom Artist - audio-1 - p2",
            renderManagedDownloadBaseName(song, "%title% - %artist% - %audioId% - %subAudioId%")
        )
    }

    @Test
    fun `source segment uses bilibili albums and ignores blank channel ids`() {
        assertEquals(
            "Song - Artist - bilibili-archive - bilibili",
            renderManagedDownloadBaseName(song(album = "bilibili-archive", channelId = "netease"))
        )
        assertEquals(
            "Song - Artist - Album - netease",
            renderManagedDownloadBaseName(song(channelId = "   "))
        )
        assertEquals(
            "Song - Artist - Album - kuwo",
            renderManagedDownloadBaseName(song(channelId = "kuwo"))
        )
    }

    @Test
    fun `legacy source candidates keep historical bilibili youtube and netease names`() {
        val bilibili = candidateManagedDownloadBaseNames(song(album = "bili-archive", channelId = "  "))
        assertTrue(bilibili.contains("bilibili - Artist - Song"))
        assertFalse(bilibili.contains("netease - Artist - Song"))

        val youtubeMirror = candidateManagedDownloadBaseNames(
            song(mediaUri = "https://cdn.youtube-mirror.example/track.mp3")
        )
        assertTrue(youtubeMirror.contains("youtube_music - Artist - Song"))
        assertTrue(youtubeMirror.contains("netease - Artist - Song"))

        val plainRemote = candidateManagedDownloadBaseNames(song(mediaUri = "https://cdn.example.com/track.mp3"))
        assertTrue(plainRemote.contains("netease - Artist - Song"))
        assertFalse(plainRemote.any { it.startsWith("youtube_music") })

        val withoutMedia = candidateManagedDownloadBaseNames(song())
        assertTrue(withoutMedia.contains("netease - Artist - Song"))
        assertFalse(withoutMedia.any { it.startsWith("youtube_music") })

        val channel = candidateManagedDownloadBaseNames(song(channelId = "qq"))
        assertTrue(channel.contains("qq - Artist - Song"))
        assertFalse(channel.contains("netease - Artist - Song"))
    }

    @Test
    fun `parsing rejects blank names and templates that cannot be matched`() {
        assertNull(parseManagedDownloadBaseName("   "))
        assertNull(parseManagedDownloadBaseName("Song - Artist", template = "plain text"))
        assertNull(parseManagedDownloadBaseName("SongArtist", template = "%title%%artist%"))
        assertNull(parseManagedDownloadBaseName("Song - Song", template = "%title% - %title%"))

        val parsed = parseManagedDownloadBaseName(" Song - Artist - Album - netease ", template = "   ")
        assertEquals("Song", parsed?.title)
        assertEquals("Artist", parsed?.artist)
        assertEquals("Album", parsed?.album)
        assertEquals("netease", parsed?.source)
        assertEquals(parsed, parseManagedDownloadBaseName("Song - Artist - Album - netease", template = null))
    }

    @Test
    fun `parsing exposes identity placeholders from custom templates`() {
        val parsed = parseManagedDownloadBaseName(
            "42 - audio-1 - p2",
            template = "%id% - %audioId% - %subAudioId%"
        )

        assertEquals("42", parsed?.songId)
        assertEquals("audio-1", parsed?.audioId)
        assertEquals("p2", parsed?.subAudioId)
        assertNull(parsed?.title)
    }

    @Test
    fun `local locations without a file name add no candidates`() {
        val withoutFileNames = song(mediaUri = "?token=1").copy(
            localFileName = "  ",
            localFilePath = "/storage/Music/"
        )

        assertEquals(
            listOf(
                "Song - Artist - Album - netease",
                "Song - Artist [${managedDownloadIdentityHash(withoutFileNames)}]",
                "netease - Artist - Song",
                "Artist - Song"
            ),
            candidateManagedDownloadBaseNames(withoutFileNames)
        )
    }

    @Test
    fun `media uri file names drop query and fragment before deriving candidates`() {
        val candidates = candidateManagedDownloadBaseNames(
            song(mediaUri = "content://media/external/Track Name (2).flac?x=1#frag")
        )

        assertTrue(candidates.contains("Track Name (2)"))
        assertTrue(candidates.contains("Track Name"))
        assertFalse(candidates.any { it.contains("x=1") || it.contains("frag") })
    }

    private fun song(
        album: String = "Album",
        channelId: String? = null,
        mediaUri: String? = null,
        customName: String? = null,
        customArtist: String? = null,
        audioId: String? = null,
        subAudioId: String? = null
    ): SongItem {
        return SongItem(
            id = 7L,
            name = "Song",
            artist = "Artist",
            album = album,
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = mediaUri,
            channelId = channelId,
            audioId = audioId,
            subAudioId = subAudioId,
            customName = customName,
            customArtist = customArtist
        )
    }
}
