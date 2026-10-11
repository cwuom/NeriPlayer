package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadLibrarySnapshot
import moe.ouom.neriplayer.data.model.local.LocalAudioScanPhase
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

@RunWith(AndroidJUnit4::class)
class LocalAudioImportManagedDownloadProbeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val downloads = FakeManagedDownloads()

    @Before
    fun bindDownloads() {
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { true })
    }

    @Test
    fun `only configured tree uris yield a managed download tree document id`() {
        assertNull(LocalAudioImportManager.configuredManagedDownloadTreeDocumentId())

        downloads.configuredUri = "  "
        assertNull(LocalAudioImportManager.configuredManagedDownloadTreeDocumentId())

        downloads.configuredUri = "content://media/external/audio/media/7"
        assertNull(LocalAudioImportManager.configuredManagedDownloadTreeDocumentId())

        downloads.configuredUri = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Music/NeriPlayer"
        ).toString()
        assertEquals("primary:Music/NeriPlayer", LocalAudioImportManager.configuredManagedDownloadTreeDocumentId())
    }

    @Test
    fun `external audio probes are named after the file then the uri`() {
        val file = File("/storage/emulated/0/Music/Artist - Song.flac")
        val document = Uri.parse("content://media/external/audio/media/42")
        val opaque = Uri.parse("content://media")
        downloads.managedAnswer = { song -> song.localFilePath != null }

        assertTrue(LocalAudioImportManager.isManagedExternalAudioCandidate(context, document, file))
        assertFalse(LocalAudioImportManager.isManagedExternalAudioCandidate(context, document, null))
        assertFalse(LocalAudioImportManager.isManagedExternalAudioCandidate(context, opaque, null))

        val (fromFile, fromSegment, fromUri) = downloads.probes
        assertEquals("Artist - Song.flac", fromFile.name)
        assertEquals("Artist - Song.flac", fromFile.localFileName)
        assertEquals(file.absolutePath, fromFile.localFilePath)
        assertEquals(document.toString(), fromFile.mediaUri)
        assertEquals("local", fromFile.channelId)
        assertEquals("42", fromSegment.name)
        assertNull(fromSegment.localFileName)
        assertNull(fromSegment.localFilePath)
        assertEquals("content://media", fromUri.name)
    }

    @Test
    fun `failing managed audio checks treat the audio as external`() {
        downloads.managedAnswer = { throw IOException("index unavailable") }

        assertFalse(
            LocalAudioImportManager.isManagedExternalAudioCandidate(
                context,
                Uri.parse("content://media/external/audio/media/1"),
                null
            )
        )
    }

    @Test
    fun `complete cached snapshots skip rebuilding the download index`() = runTest {
        val cached = snapshot(rootComplete = true, sidecarComplete = true)
        downloads.cached = { cached }

        assertSame(cached, LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context))
        assertEquals(0, downloads.builds)
    }

    @Test
    fun `incomplete missing or bypassed caches rebuild the download index`() = runTest {
        val rebuilt = snapshot(rootComplete = true, sidecarComplete = true)
        downloads.built = { rebuilt }

        downloads.cached = { snapshot(rootComplete = false, sidecarComplete = true) }
        assertSame(rebuilt, LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context))
        downloads.cached = { snapshot(rootComplete = true, sidecarComplete = false) }
        assertSame(rebuilt, LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context))
        downloads.cached = { throw IOException("cache unreadable") }
        assertSame(rebuilt, LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context))
        downloads.cached = { snapshot(rootComplete = true, sidecarComplete = true) }
        assertSame(rebuilt, LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context, forceRefresh = true))

        assertEquals(4, downloads.builds)
        assertEquals(3, downloads.cacheReads)
        assertEquals(listOf(true, true, true, true), downloads.buildForceRefreshes)
    }

    @Test
    fun `scan progress reports reading the download index around both reads`() = runTest {
        val reported = mutableListOf<LocalAudioScanProgress>()
        val progress = LocalAudioScanProgressEmitter(scanId = 3L, startedAt = 0L, onProgress = reported::add)
        val rebuilt = snapshot(rootComplete = true, sidecarComplete = true)
        downloads.cached = { null }
        downloads.built = { rebuilt }

        assertSame(rebuilt, LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context, progress))

        assertEquals(1, downloads.cacheReads)
        assertEquals(1, downloads.builds)
        assertTrue(reported.size >= 2)
        assertTrue(reported.all { it.phase == LocalAudioScanPhase.READING_DOWNLOAD_INDEX && it.waitingForProvider })
    }

    @Test
    fun `unbuildable download indexes leave the scan without a snapshot`() = runTest {
        downloads.cached = { null }
        downloads.built = { throw IOException("tree revoked") }

        assertNull(LocalAudioImportManager.loadManagedDownloadSnapshotForScan(context))
        assertEquals(1, downloads.builds)
    }

    private fun snapshot(rootComplete: Boolean, sidecarComplete: Boolean): DownloadLibrarySnapshot {
        return mock(DownloadLibrarySnapshot::class.java).also { snapshot ->
            doReturn(rootComplete).`when`(snapshot).rootEntriesComplete
            doReturn(sidecarComplete).`when`(snapshot).sidecarEntriesComplete
        }
    }

    private class FakeManagedDownloads(
        private val unused: LocalMediaDownloadAccess = mock(LocalMediaDownloadAccess::class.java)
    ) : LocalMediaDownloadAccess by unused {
        var configuredUri: String? = null
        var managedAnswer: (SongItem) -> Boolean = { false }
        var cached: () -> DownloadLibrarySnapshot? = { null }
        var built: () -> DownloadLibrarySnapshot = { error("no snapshot") }
        val probes = mutableListOf<SongItem>()
        val buildForceRefreshes = mutableListOf<Boolean>()
        var cacheReads = 0
        var builds = 0

        override fun configuredDirectoryUri(): String? = configuredUri

        override fun isLikelyManagedDownloadSong(context: Context, song: SongItem): Boolean {
            probes += song
            return managedAnswer(song)
        }

        override fun cachedDownloadLibrarySnapshot(context: Context, restorePersisted: Boolean): DownloadLibrarySnapshot? {
            cacheReads++
            return cached()
        }

        override suspend fun buildDownloadLibrarySnapshot(context: Context, forceRefresh: Boolean): DownloadLibrarySnapshot {
            builds++
            buildForceRefreshes += forceRefresh
            return built()
        }
    }
}
