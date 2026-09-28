package moe.ouom.neriplayer.ui.screen

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongBaseline
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongBaselineReader
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.readManagedEditSongBaselineSnapshot
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveManagedEditSongBaselineAtRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingSongEditRestoreSourcesTest {
    private val context = Mockito.mock(Context::class.java)
    private val localSong = SongItem(
        8L, "Title", "Artist", "Album", 2L, 60_000L, null,
        mediaUri = "content://media/external/audio/media/8"
    )
    private val baseline = EditSongBaseline("Base title", "Base artist", "base-cover", "base lyric", null, null)

    @Test
    fun `remote song keeps baseline without touching local readers`() = runTest {
        val reader = FakeReader()
        val result = resolveManagedEditSongBaselineAtRestore(
            context, localSong.copy(mediaUri = null), baseline, reader
        )
        assertSame(baseline, result)
        assertEquals(0, reader.metadataCalls)
        assertEquals(0, reader.sidecarCalls)
    }

    @Test
    fun `managed sidecar wins and skips slower local fallback`() = runTest {
        val reader = FakeReader().apply {
            sidecarValue = ManagedDownloadStorage.DownloadedLyricsBundle(
                lyric = "sidecar lyric", translatedLyric = null, romanizedLyric = null,
                hasOriginalSidecar = true, hasTranslatedSidecar = false, hasRomanizedSidecar = false
            )
        }
        val result = resolveManagedEditSongBaselineAtRestore(context, localSong, baseline, reader)
        assertEquals("sidecar lyric", result.lyric)
        assertEquals(1, reader.sidecarCalls)
        assertEquals(0, reader.localCalls)
    }

    @Test
    fun `unavailable managed sidecar falls back to real local sidecar`() = runTest {
        val reader = FakeReader().apply {
            sidecarFailure = IllegalStateException("stale index")
            localValue = LocalLyricsScanMetadata(
                lyric = "local lyric", translatedLyric = null, romanizedLyric = null,
                hasOriginalSidecar = true
            )
        }
        val result = resolveManagedEditSongBaselineAtRestore(context, localSong, baseline, reader)
        assertEquals("local lyric", result.lyric)
        assertEquals(1, reader.sidecarCalls)
        assertEquals(1, reader.localCalls)
    }

    @Test
    fun `managed baseline snapshot reads cover and local sidecar only after metadata exists`() = runTest {
        val reader = FakeReader()
        val dispatcher = StandardTestDispatcher(testScheduler)
        assertEquals(
            null,
            readManagedEditSongBaselineSnapshot(context, localSong, reader, dispatcher)
        )
        assertEquals(0, reader.coverCalls)
        assertEquals(0, reader.sidecarCalls)

        reader.metadataValue = ManagedDownloadRestorableMetadata(
            sourceStableKey = "stable",
            baseline = ManagedDownloadRestorableMetadata.Baseline(title = "Original"),
            overrides = ManagedDownloadRestorableMetadata.Overrides()
        )
        reader.coverValue = "content://cover/base"
        reader.sidecarValue = ManagedDownloadStorage.DownloadedLyricsBundle(
            lyric = "sidecar", translatedLyric = null, romanizedLyric = null,
            hasOriginalSidecar = true, hasTranslatedSidecar = false, hasRomanizedSidecar = false
        )
        val local = readManagedEditSongBaselineSnapshot(context, localSong, reader, dispatcher)
        assertEquals("content://cover/base", local?.coverReference)
        assertEquals("sidecar", local?.sidecarLyrics?.lyric)
        assertEquals(1, reader.coverCalls)
        assertEquals(1, reader.sidecarCalls)

        val remote = readManagedEditSongBaselineSnapshot(
            context, localSong.copy(mediaUri = null), reader, dispatcher
        )
        assertEquals("content://cover/base", remote?.coverReference)
        assertEquals(null, remote?.sidecarLyrics)
        assertEquals(1, reader.sidecarCalls)
    }

    private class FakeReader : EditSongBaselineReader {
        var metadataCalls = 0
        var coverCalls = 0
        var sidecarCalls = 0
        var localCalls = 0
        var metadataValue: ManagedDownloadRestorableMetadata? = null
        var coverValue: String? = null
        var sidecarValue: ManagedDownloadStorage.DownloadedLyricsBundle? = null
        var sidecarFailure: Exception? = null
        var localValue: LocalLyricsScanMetadata? = null

        override suspend fun metadata(
            context: Context, song: SongItem
        ): ManagedDownloadRestorableMetadata? {
            metadataCalls++
            return metadataValue
        }

        override suspend fun cover(
            context: Context, metadata: ManagedDownloadRestorableMetadata
        ): String? {
            coverCalls++
            return coverValue
        }

        override suspend fun sidecar(
            context: Context, song: SongItem
        ): ManagedDownloadStorage.DownloadedLyricsBundle? {
            sidecarCalls++
            sidecarFailure?.let { throw it }
            return sidecarValue
        }

        override suspend fun local(context: Context, song: SongItem): LocalLyricsScanMetadata? {
            localCalls++
            return localValue
        }
    }
}
