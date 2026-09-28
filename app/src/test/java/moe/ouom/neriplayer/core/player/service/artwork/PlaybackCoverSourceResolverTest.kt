package moe.ouom.neriplayer.core.player.service.artwork

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoverSourceResolverTest {
    @Test
    fun `local source prefers nearby artwork over managed and remote references`() = runTest {
        val sources = FakeSources().apply {
            local = true
            nearbyReference = "content://nearby/cover.jpg"
            downloadedReference = DownloadedArtworkReference("content://managed/cover.jpg", "https://old/cover.jpg")
        }

        assertEquals("content://nearby/cover.jpg", PlaybackCoverSourceResolver(sources).resolve(song(), null))
        assertFalse(sources.rebound)
    }

    @Test
    fun `local source chooses a usable cached cover ahead of nearby artwork`() = runTest {
        val sources = FakeSources().apply {
            local = true
            peek = "content://cache/cover.jpg"
            nearbyReference = "content://nearby/cover.jpg"
        }
        assertEquals("content://cache/cover.jpg", PlaybackCoverSourceResolver(sources).resolve(song(), null))
        assertFalse(sources.rebound)
    }

    @Test
    fun `unusable local candidate falls through to local metadata resolution`() = runTest {
        val sources = FakeSources().apply {
            local = true
            peek = "content://broken/cover.jpg"
            resolvedLocal = "content://metadata/cover.jpg"
            usable = { it != "content://broken/cover.jpg" }
        }
        assertEquals("content://metadata/cover.jpg", PlaybackCoverSourceResolver(sources).resolve(song(), null))
    }

    @Test
    fun `failed cached reference is refreshed before remote fallback`() = runTest {
        val sources = FakeSources().apply {
            downloadedReference = DownloadedArtworkReference("content://old/cover.jpg", "https://remote/cover.jpg")
            rebindResponse = { _, refresh -> if (refresh) "content://new/cover.jpg" else "content://old/cover.jpg" }
        }

        assertEquals(
            "content://new/cover.jpg",
            PlaybackCoverSourceResolver(sources).resolve(song(), "content://old/cover.jpg"),
        )
        assertEquals(listOf(false, true), sources.refreshes)
    }

    @Test
    fun `valid cached managed reference does not force a second lookup`() = runTest {
        val sources = FakeSources().apply {
            downloadedReference = DownloadedArtworkReference("content://old/cover.jpg", null)
            rebindResponse = { _, _ -> "content://current/cover.jpg" }
        }
        assertEquals("content://current/cover.jpg", PlaybackCoverSourceResolver(sources).resolve(song(), null))
        assertEquals(listOf(false), sources.refreshes)
    }

    @Test
    fun `local song rejects implicit platform remote fallback`() = runTest {
        val sources = FakeSources().apply { local = true }

        assertNull(PlaybackCoverSourceResolver(sources).resolve(song(coverUrl = "https://platform/cover.jpg"), null))
    }

    @Test
    fun `explicit custom remote cover is allowed for a local song`() = runTest {
        val sources = FakeSources().apply { local = true }

        assertEquals(
            "https://custom/cover.jpg",
            PlaybackCoverSourceResolver(sources).resolve(song(customCoverUrl = "https://custom/cover.jpg"), null),
        )
    }

    @Test
    fun `remote song uses cached downloaded artwork before platform URL`() = runTest {
        val sources = FakeSources().apply {
            downloadedReference = DownloadedArtworkReference(null, "https://downloaded/cover.jpg")
        }
        assertEquals(
            "https://downloaded/cover.jpg",
            PlaybackCoverSourceResolver(sources).resolve(song(coverUrl = "https://platform/cover.jpg"), null),
        )
        assertEquals(
            "https://platform/cover.jpg",
            PlaybackCoverSourceResolver(sources).resolve(
                song(coverUrl = "https://platform/cover.jpg"), "https://downloaded/cover.jpg",
            ),
        )
    }

    @Test
    fun `immediate local source uses custom then downloaded then local display`() {
        val localReads = mutableListOf<String>()
        assertEquals(
            "content://custom/cover.jpg",
            resolveImmediateArtworkSource(
                isLocal = true,
                customCover = "content://custom/cover.jpg",
                isDirectoryReference = { false },
                localCover = { localReads += "local"; "content://download/cover.jpg" },
                displayedCover = { localReads += "display"; "content://display/cover.jpg" },
            ),
        )
        assertTrue(localReads.isEmpty())
        assertEquals(
            "content://download/cover.jpg",
            resolveImmediateArtworkSource(true, null, { false }, { "content://download/cover.jpg" }, { null }),
        )
        assertNull(resolveImmediateArtworkSource(false, null, { false }, { null }, { null }))
        assertEquals(
            "content://display/cover.jpg",
            resolveImmediateArtworkSource(true, null, { false }, { null }, { "content://display/cover.jpg" }),
        )
        assertNull(resolveImmediateArtworkSource(true, null, { false }, { null }, { "https://platform/cover.jpg" }))
        assertEquals(
            "content://download/cover.jpg",
            resolveImmediateArtworkSource(
                true, "content://tree/directory", { true },
                { "content://download/cover.jpg" }, { null },
            ),
        )
        assertEquals(
            "https://platform/cover.jpg",
            resolveImmediateArtworkSource(
                false, "content://custom/cover.jpg", { false },
                { null }, { "https://platform/cover.jpg" },
            ),
        )
        assertEquals(
            "content://download/cover.jpg",
            resolveImmediateArtworkSource(
                true, "  ", { false },
                { "content://download/cover.jpg" }, { null },
            ),
        )
    }

    @Test
    fun `artwork policy rejects ready bitmap reload and invalid decoded cover names`() {
        assertFalse(shouldRequestArtworkLoad(
            coverSource = "https://covers/song.jpg",
            artworkReady = true,
            inFlightCoverSource = null,
            lastFailedCoverSource = null,
            lastFailureAtElapsedRealtime = -1,
            nowElapsedRealtime = 1_000,
        ))
        assertNull(coverReferenceFileName("content://covers/%2E"))
        assertEquals("name+part.jpg", coverReferenceFileName("content://covers/name%2Bpart.jpg"))
    }

    private fun song(coverUrl: String? = null, customCoverUrl: String? = null) = SongItem(
        id = 1,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 1,
        durationMs = 1_000,
        coverUrl = coverUrl,
        customCoverUrl = customCoverUrl,
    )

    private class FakeSources : PlaybackCoverSources {
        var local = false
        var peek: String? = null
        var nearbyReference: String? = null
        var resolvedLocal: String? = null
        var downloadedReference: DownloadedArtworkReference? = null
        var rebound = false
        val refreshes = mutableListOf<Boolean>()
        var rebindResponse: suspend (String, Boolean) -> String? = { _, _ -> null }
        var usable: (String) -> Boolean = { true }

        override fun isLocal(song: SongItem): Boolean = local
        override fun immediate(song: SongItem): String? = null
        override fun peekLocal(song: SongItem): String? = peek
        override fun nearby(song: SongItem): String? = nearbyReference
        override fun resolveLocal(song: SongItem): String? = resolvedLocal
        override fun downloaded(song: SongItem): DownloadedArtworkReference? = downloadedReference
        override suspend fun rebind(fileName: String, forceRefresh: Boolean): String? {
            rebound = true
            refreshes += forceRefresh
            return rebindResponse(fileName, forceRefresh)
        }
        override fun isUsable(reference: String): Boolean = usable(reference)
    }
}
