package moe.ouom.neriplayer.ui.screen

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongEmbeddedReadResult
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricVariant
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsSourceReader
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsSources
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.hasEmbeddedLyricText
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.readEditSongEmbeddedLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.readEditSongLyricsSources
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldProbeEditSongLocalLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingSongEditLyricsSourcesTest {
    private val context = Mockito.mock(Context::class.java)
    private val song = SongItem(8L, "Title", "Artist", "Album", 2L, 60_000L, null)

    @Test
    fun `downloaded sidecars win per variant and local sidecars fill gaps`() {
        val sources = EditSongLyricsSources(
            downloaded = bundle("downloaded", "downloaded translation", null, original = true),
            local = scan(
                "local", "local translation", "local romanized",
                translatedSidecar = true, romanizedSidecar = true
            ),
            embedded = null
        )
        assertEquals("downloaded", sources.sidecarText(EditSongLyricVariant.ORIGINAL))
        assertEquals("local translation", sources.sidecarText(EditSongLyricVariant.TRANSLATED))
        assertEquals("local romanized", sources.sidecarText(EditSongLyricVariant.ROMANIZED))
        assertTrue(sources.hasAnySidecar())
        assertTrue(EditSongLyricVariant.entries.all(sources::hasSidecar))
    }

    @Test
    fun `embedded fallback respects non sidecar downloaded text then stored and displayed`() {
        val sources = EditSongLyricsSources(
            downloaded = bundle("downloaded", null, null),
            local = null,
            embedded = scan(null, "embedded translation", null, embeddedLyric = "tag")
        )
        assertEquals("downloaded", sources.embeddedText(EditSongLyricVariant.ORIGINAL, "stored", "displayed"))
        assertEquals("embedded translation", sources.embeddedText(EditSongLyricVariant.TRANSLATED, "stored", "displayed"))
        assertEquals("stored", sources.embeddedText(EditSongLyricVariant.ROMANIZED, "stored", "displayed"))
        assertEquals("displayed", sources.embeddedText(EditSongLyricVariant.ROMANIZED, null, "displayed"))
        assertTrue(sources.hasEmbeddedText())
        assertFalse(EditSongLyricsSources(null, null, null).hasEmbeddedText())
        assertFalse(EditSongLyricsSources(null, null, null).hasAnySidecar())
    }

    @Test
    fun `present downloaded sidecar stays authoritative even when its text is empty`() {
        val sources = EditSongLyricsSources(
            downloaded = bundle(null, null, null, original = true),
            local = scan("local original", null, null, original = true),
            embedded = scan("tag original", null, null)
        )
        assertNull(sources.sidecarText(EditSongLyricVariant.ORIGINAL))
        assertEquals("tag original", sources.embeddedText(EditSongLyricVariant.ORIGINAL, "stored", "displayed"))
        assertNull(sources.sidecarText(EditSongLyricVariant.TRANSLATED))
        assertEquals("stored", sources.embeddedText(EditSongLyricVariant.TRANSLATED, "stored", "displayed"))
    }

    @Test
    fun `missing sidecars leave source text empty while embedded uses stored fallback`() {
        val sources = EditSongLyricsSources(null, null, null)
        assertNull(sources.sidecarText(EditSongLyricVariant.ORIGINAL))
        assertEquals("stored", sources.embeddedText(EditSongLyricVariant.ORIGINAL, "stored", "shown"))
        assertEquals("shown", sources.embeddedText(EditSongLyricVariant.ORIGINAL, null, "shown"))

        val downloaded = EditSongLyricsSources(bundle("index", null, null), null, null)
        assertNull(downloaded.sidecarText(EditSongLyricVariant.ORIGINAL))
        assertEquals("index", downloaded.embeddedText(EditSongLyricVariant.ORIGINAL, "stored", "shown"))
    }

    @Test
    fun `translation or romanization alone counts as embedded lyrics`() {
        assertTrue(
            LocalLyricsScanMetadata(null, null, null, embeddedTranslatedLyric = "translation")
                .hasEmbeddedLyricText()
        )
        assertTrue(
            LocalLyricsScanMetadata(null, null, null, embeddedRomanizedLyric = "romanized")
                .hasEmbeddedLyricText()
        )
        assertFalse(LocalLyricsScanMetadata(null, null, null).hasEmbeddedLyricText())
    }

    @Test
    fun `managed and incomplete snapshots probe local sidecars`() {
        val complete = bundle("a", "b", "c", true, true, true)
        assertTrue(shouldProbeEditSongLocalLyrics(true, complete))
        assertTrue(shouldProbeEditSongLocalLyrics(false, null))
        assertTrue(
            shouldProbeEditSongLocalLyrics(
                false,
                complete.copy(hasRomanizedSidecar = false)
            )
        )
        assertFalse(shouldProbeEditSongLocalLyrics(false, complete))
    }

    @Test
    fun `managed read probes real sidecars and skips embedded when found`() = runTest {
        val reader = FakeReader().apply {
            managed = true
            downloadedValue = bundle("downloaded", null, null)
            localValue = scan("local", null, null, original = true)
        }
        val result =
            readEditSongLyricsSources(context, song, reader, StandardTestDispatcher(testScheduler))
        assertEquals(1, reader.downloadedCalls)
        assertEquals(1, reader.localCalls)
        assertEquals(0, reader.embeddedCalls)
        assertEquals(reader.downloadedValue, result.downloaded)
        assertEquals(reader.localValue, result.local)
        assertNull(result.embedded)
    }

    @Test
    fun `unmanaged read probes embedded only after sidecar miss`() = runTest {
        val reader = FakeReader().apply {
            embeddedValue = scan(null, null, null, embeddedLyric = "embedded")
        }
        val result =
            readEditSongLyricsSources(context, song, reader, StandardTestDispatcher(testScheduler))
        assertEquals(0, reader.downloadedCalls)
        assertEquals(1, reader.localCalls)
        assertEquals(1, reader.embeddedCalls)
        assertEquals(reader.embeddedValue, result.embedded)
    }

    @Test
    fun `ordinary download failure falls through while security failure propagates`() = runTest {
        val reader = FakeReader().apply {
            managed = true
            downloadedFailure = IllegalStateException("stale index")
        }
        val fallback =
            readEditSongLyricsSources(context, song, reader, StandardTestDispatcher(testScheduler))
        assertNull(fallback.downloaded)
        assertEquals(1, reader.localCalls)
        assertEquals(1, reader.embeddedCalls)

        reader.downloadedFailure = SecurityException("permission lost")
        try {
            readEditSongLyricsSources(context, song, reader, StandardTestDispatcher(testScheduler))
            throw AssertionError("security failure must propagate")
        } catch (expected: SecurityException) {
            assertEquals("permission lost", expected.message)
        }
    }

    @Test
    fun `cancelled source read never falls through to a later source`() = runTest {
        val reader = FakeReader().apply {
            managed = true
            downloadedFailure = CancellationException("cancelled edit")
        }
        try {
            readEditSongLyricsSources(context, song, reader, StandardTestDispatcher(testScheduler))
            throw AssertionError("cancelled read must stop")
        } catch (expected: CancellationException) {
            assertEquals("cancelled edit", expected.message)
        }
        assertEquals(0, reader.localCalls)
        assertEquals(0, reader.embeddedCalls)
    }

    @Test
    fun `embedded followup distinguishes content permission loss and ordinary failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val loaded = readEditSongEmbeddedLyrics(
            context, song,
            reader = { _, _ -> scan(null, null, null, embeddedLyric = "tag lyric") },
            dispatcher = dispatcher
        )
        assertTrue(loaded is EditSongEmbeddedReadResult.Loaded)
        assertEquals("tag lyric", (loaded as EditSongEmbeddedReadResult.Loaded).lyrics?.embeddedLyric)

        val denied = readEditSongEmbeddedLyrics(
            context, song,
            reader = { _, _ -> throw SecurityException("lost grant") },
            dispatcher = dispatcher
        )
        assertEquals(EditSongEmbeddedReadResult.PermissionLost, denied)

        val failed = readEditSongEmbeddedLyrics(
            context, song,
            reader = { _, _ -> throw IllegalStateException("bad tag") },
            dispatcher = dispatcher
        )
        assertEquals(EditSongEmbeddedReadResult.Loaded(null), failed)
    }

    private fun bundle(
        lyric: String?, translated: String?, romanized: String?,
        original: Boolean = false, translation: Boolean = false, romanization: Boolean = false
    ) = ManagedDownloadStorage.DownloadedLyricsBundle(
        lyric, translated, romanized, original, translation, romanization
    )

    private fun scan(
        lyric: String?, translated: String?, romanized: String?,
        original: Boolean = false, translatedSidecar: Boolean = false,
        romanizedSidecar: Boolean = false, embeddedLyric: String? = null
    ) = LocalLyricsScanMetadata(
        lyric = lyric,
        translatedLyric = translated,
        romanizedLyric = romanized,
        hasOriginalSidecar = original,
        hasTranslatedSidecar = translatedSidecar,
        hasRomanizedSidecar = romanizedSidecar,
        embeddedLyric = embeddedLyric
    )

    private class FakeReader : EditSongLyricsSourceReader {
        var managed = false
        var downloadedValue: ManagedDownloadStorage.DownloadedLyricsBundle? = null
        var localValue: LocalLyricsScanMetadata? = null
        var embeddedValue: LocalLyricsScanMetadata? = null
        var downloadedFailure: Exception? = null
        var downloadedCalls = 0
        var localCalls = 0
        var embeddedCalls = 0

        override fun isManagedLocalDownload(song: SongItem) = managed
        override fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle? {
            downloadedCalls++
            downloadedFailure?.let { throw it }
            return downloadedValue
        }
        override fun local(context: Context, song: SongItem, managed: Boolean): LocalLyricsScanMetadata? {
            localCalls++
            return localValue
        }
        override fun embedded(context: Context, song: SongItem): LocalLyricsScanMetadata? {
            embeddedCalls++
            return embeddedValue
        }
    }
}
