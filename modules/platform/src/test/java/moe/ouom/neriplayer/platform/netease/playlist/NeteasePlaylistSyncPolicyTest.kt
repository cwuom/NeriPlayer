package moe.ouom.neriplayer.platform.netease.playlist

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

class NeteasePlaylistSyncPolicyTest {
    private val noPause: (Long) -> Unit = { error("unexpected backoff") }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `bulk netease candidate filtering preserves duplicate original rows`() {
        val sync = NeteasePlaylistSync(noPause) { null }
        val first = song(id = 46L, name = "first")
        val duplicate = first.copy(name = "edited duplicate")
        val path = File(tempFolder.root, "song-47.mp3").absolutePath
        val unsupported = SongItem(
            id = 47L,
            name = "song-47",
            artist = "artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 1_047L,
            coverUrl = null,
            mediaUri = path,
            localFilePath = path
        )

        assertEquals(
            listOf(first, duplicate),
            sync.filterNeteaseLikeSyncCandidatesPreservingDuplicates(listOf(first, duplicate, unsupported))
        )
    }

    @Test
    fun `candidate identification retains source priority and legacy fallback rules`() {
        val remote = song(id = 11L)
        val local = remote.copy(channelId = "local", audioId = null, album = "__local_files__")

        assertEquals(71L, resolveNeteaseSongId(remote.copy(id = 0L, channelId = "NETEASE", audioId = "71")))
        assertEquals(11L, resolveNeteaseSongId(remote.copy(audioId = "invalid")))
        assertEquals(11L, resolveNeteaseSongId(local.copy(album = "NeteaseAlbum")))
        assertEquals(72L, resolveNeteaseSongId(local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "72")))
        assertEquals(11L, resolveNeteaseSongId(local.copy(coverUrl = "https://p1.music.126.net/cover.jpg")))
        assertEquals(11L, resolveNeteaseSongId(local.copy(originalCoverUrl = "https://P1.MUSIC.126.NET/cover.jpg")))
        assertNull(resolveNeteaseSongId(local))
        assertNull(resolveNeteaseSongId(local.copy(album = "neteaseAlbum")))
        assertNull(resolveNeteaseSongId(local.copy(id = 0L, matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "72")))
    }

    @Test
    fun `invalid explicit ids fall back without overriding higher priority source identities`() {
        val remote = song(id = 11L).copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "72",
            coverUrl = "https://p1.music.126.net/cover.jpg"
        )

        assertEquals(71L, resolveNeteaseSongId(remote.copy(audioId = "71")))
        assertEquals(11L, resolveNeteaseSongId(remote.copy(audioId = "0")))
        assertEquals(11L, resolveNeteaseSongId(remote.copy(audioId = null)))
        assertEquals(11L, resolveNeteaseSongId(remote.copy(channelId = "local", audioId = null)))
        val local = remote.copy(channelId = "local", audioId = null, album = "local")
        assertEquals(72L, resolveNeteaseSongId(local))
        assertEquals(11L, resolveNeteaseSongId(local.copy(matchedSongId = "invalid")))
        assertEquals(11L, resolveNeteaseSongId(local.copy(matchedSongId = "-1")))
        assertNull(resolveNeteaseSongId(local.copy(matchedSongId = null, coverUrl = null)))
    }

    @Test
    fun `unique candidates retain the first original row and input order`() {
        val first = song(id = 8L)
        val duplicate = first.copy(id = 80L, name = "duplicate", audioId = "8")
        val second = song(id = 3L)
        val unsupported = first.copy(channelId = "local", audioId = null, album = "local")

        val summary = buildLocalNeteaseCandidates(listOf(first, unsupported, duplicate, second))

        assertEquals(listOf(first, second), summary.candidates.map { it.song })
        assertEquals(listOf(8L, 3L), summary.candidates.map { it.neteaseId })
        assertEquals(2, summary.supportedSongs)
        assertEquals(1, summary.skippedExisting)
        assertEquals(1, summary.skippedUnsupported)
        assertEquals(emptyList<SongItem>(), NeteasePlaylistSync(noPause) { null }.filterNeteaseLikeSyncCandidates(emptyList()))
    }

    @Test
    fun `liked playlist parsing distinguishes failed and successful missing ids`() {
        for (raw in listOf("", "invalid", "{\"code\":301}", "{\"code\":500,\"playlistId\":42}")) {
            val parsed = parseNeteaseLikedPlaylistId(raw)
            assertFalse(parsed.success)
            assertNull(parsed.playlistId)
        }

        assertEquals(42L, parseNeteaseLikedPlaylistId("{\"code\":200,\"playlistId\":42}").playlistId)
        val empty = parseNeteaseLikedPlaylistId("{\"code\":200,\"playlistId\":0}")
        assertTrue(empty.success)
        assertNull(empty.playlistId)
    }

    @Test
    fun `track ids retain remote order while dropping invalid and repeated ids`() {
        val parsed = parseNeteaseTrackIdsFromPlaylistDetail(
            """{"code":200,"playlist":{"trackIds":[{"id":3},{"id":0},{"id":2},{"id":3},{"id":-1},null]}}"""
        )

        assertTrue(parsed.success)
        assertEquals(listOf(3L, 2L), parsed.trackIds)
        assertEquals(2, parsed.trackCount)
        assertFalse(parseNeteaseTrackIdsFromPlaylistDetail("{\"code\":301}").success)
        assertFalse(parseNeteaseTrackIdsFromPlaylistDetail("invalid").success)
    }

    @Test
    fun `playlist detail retains missing track list and explicit count fallbacks`() {
        val missingPlaylist = parseNeteaseTrackIdsFromPlaylistDetail("{\"code\":200}")
        assertTrue(missingPlaylist.success)
        assertTrue(missingPlaylist.trackIds.isEmpty())
        assertEquals(0, missingPlaylist.trackCount)

        val missingIds = parseNeteaseTrackIdsFromPlaylistDetail("{\"code\":200,\"playlist\":{\"trackCount\":4}}")
        assertTrue(missingIds.success)
        assertTrue(missingIds.trackIds.isEmpty())
        assertEquals(4, missingIds.trackCount)

        val explicitZero = parseNeteaseTrackIdsFromPlaylistDetail("{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":3}],\"trackCount\":0}}")
        assertEquals(listOf(3L), explicitZero.trackIds)
        assertEquals(0, explicitZero.trackCount)
        val invalidCount = parseNeteaseTrackIdsFromPlaylistDetail("{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":3}],\"trackCount\":\"invalid\"}}")
        assertEquals(1, invalidCount.trackCount)
    }

    @Test
    fun `song details retain artist normalization and rounded duration fingerprints`() {
        val parsed = parseNeteaseSongDetailSummary(
            """{"code":200,"songs":[{"id":3,"name":" Song! ","ar":[{"name":"B"},{"name":"A"}],"dt":2499},{"id":3,"name":"Song","ar":[{"name":"A"},{"name":"B"}],"dt":2500},{"id":0,"name":"","ar":[]},null]}"""
        )

        assertTrue(parsed.success)
        assertEquals(setOf(3L), parsed.ids)
        assertEquals(setOf("song|a|b|0", "song|a|b|1"), parsed.fingerprints)
        assertFalse(parseNeteaseSongDetailSummary("{\"code\":500}").success)
        assertFalse(parseNeteaseSongDetailSummary("invalid").success)
        assertEquals("a|b", normalizeArtistToken("B / A & A，B"))
        assertEquals("晴天abc", normalizeFingerprintToken(" 晴天 A-B_C! "))
        assertNull(buildNeteaseFingerprint("", "artist", 1L))
        assertNull(buildNeteaseFingerprint("song", null, 1L))
    }

    @Test
    fun `song artists preserve original order while ignoring missing and blank entries`() {
        assertEquals("", parseNeteaseSongArtist(JSONObject("{}")))
        assertEquals("", parseNeteaseSongArtist(JSONObject("{\"ar\":[]}")))
        assertEquals(
            "A / B / A",
            parseNeteaseSongArtist(JSONObject("""{"ar":[null,3,{}, {"name":""},{"name":" "},{"name":null},{"name":" A "},{"name":"B"},{"name":"A"}]}"""))
        )
    }

    @Test
    fun `successful song details may omit songs but failures cannot become a successful empty summary`() {
        for (raw in listOf("{\"code\":200}", "{\"code\":200,\"songs\":[]}")) {
            val parsed = parseNeteaseSongDetailSummary(raw)
            assertTrue(parsed.success)
            assertTrue(parsed.ids.isEmpty())
            assertTrue(parsed.fingerprints.isEmpty())
        }
        assertFalse(parseNeteaseSongDetailSummary(" ").success)
        assertFalse(parseNeteaseSongDetailSummary("{\"code\":301}").success)
        val parsed = parseNeteaseSongDetailSummary("""{"code":200,"songs":[{"id":1}, {"id":0,"name":"song","ar":[{"name":"artist"}],"dt":1000}]}""")
        assertEquals(setOf(1L), parsed.ids)
        assertEquals(setOf("song|artist|0"), parsed.fingerprints)
    }

    @Test
    fun `fingerprints prefer original source metadata before local edits`() {
        val original = song(id = 1L).copy(
            originalName = "Original title",
            originalArtist = "Original artist",
            customName = "Edited title",
            customArtist = "Edited artist"
        )

        assertEquals("originaltitle|originalartist|0", original.toNeteaseFingerprint())
        assertEquals("editedtitle|editedartist|0", original.copy(originalName = null, originalArtist = null).toNeteaseFingerprint())
        assertEquals("song|artist|0", song(id = 1L).toNeteaseFingerprint())
    }

    private fun song(id: Long, name: String = "song"): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 7L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = id.toString()
        )
    }
}
