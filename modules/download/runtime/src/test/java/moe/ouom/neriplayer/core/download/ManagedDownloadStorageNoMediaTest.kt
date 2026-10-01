package moe.ouom.neriplayer.core.download

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadMediaScanIsolation
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadStorageNoMediaTest {

    @Test
    fun `concurrent SAF initialization creates exactly one marker`() {
        val context = mock(Context::class.java)
        val directory = mock(DocumentFile::class.java)
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn("content://test/tree/root/document/tmp")
        `when`(directory.uri).thenReturn(uri)
        val marker = mock(DocumentFile::class.java)
        `when`(marker.name).thenReturn(".nomedia")
        val ensured = ConcurrentHashMap<String, Boolean>()
        val firstCreate = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val duplicateCreate = CountDownLatch(1)
        val creates = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        fun ensure() = ManagedDownloadMediaScanIsolation.ensureTreeDirectory(
            context, ".tmp", directory, ensured,
            hasCachedChild = { _, _, _ -> creates.get() > 0 && releaseCreate.count == 0L },
            createMarker = {
                if (creates.incrementAndGet() == 1) {
                    firstCreate.countDown()
                    check(releaseCreate.await(5, TimeUnit.SECONDS))
                } else {
                    duplicateCreate.countDown()
                }
                marker
            },
            isMarkerAccessible = { _, _ -> ManagedDownloadReferenceIo.AccessResult.Accessible },
            rememberMarker = { _, _ -> }
        )
        try {
            val first = executor.submit { ensure() }
            assertTrue(firstCreate.await(5, TimeUnit.SECONDS))
            val second = executor.submit { secondStarted.countDown(); ensure() }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            val duplicated = duplicateCreate.await(250, TimeUnit.MILLISECONDS)
            releaseCreate.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertFalse("same directory must serialize marker creation", duplicated)
            assertEquals(1, creates.get())
        } finally {
            releaseCreate.countDown()
            executor.shutdownNow()
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `shouldCreateNoMediaMarker targets cover and temporary directories`() {
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker("Covers"))
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker("covers"))
        assertTrue(ManagedDownloadStorage.shouldCreateNoMediaMarker(".tmp"))
        assertFalse(ManagedDownloadStorage.shouldCreateNoMediaMarker("Lyrics"))
    }

    @Test
    fun `file directory isolation creates nomedia marker for cover directory`() {
        val coverDirectory = tempFolder.newFolder("Covers")

        ensureManagedMediaScanIsolation("Covers", coverDirectory)

        assertTrue(File(coverDirectory, ".nomedia").exists())
    }

    @Test
    fun `file directory isolation restores a removed cover marker`() {
        val coverDirectory = tempFolder.newFolder("Covers")
        val ensured = ConcurrentHashMap<String, Boolean>()
        val marker = File(coverDirectory, ".nomedia")

        ManagedDownloadMediaScanIsolation.ensureFileDirectory("Covers", coverDirectory, ensured)
        assertTrue(marker.delete())
        ManagedDownloadMediaScanIsolation.ensureFileDirectory("Covers", coverDirectory, ensured)

        assertTrue(marker.isFile)
    }

    @Test
    fun `SAF directory isolation rechecks a cached marker before the next cover write`() {
        val context = mock(Context::class.java)
        val directory = mock(DocumentFile::class.java)
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn("content://test/tree/root/document/Covers")
        `when`(directory.uri).thenReturn(uri)
        val marker = mock(DocumentFile::class.java)
        `when`(marker.name).thenReturn(".nomedia")
        val ensured = ConcurrentHashMap<String, Boolean>()
        var exists = false
        var creations = 0
        fun ensure() = ManagedDownloadMediaScanIsolation.ensureTreeDirectory(
            context, "Covers", directory, ensured,
            hasCachedChild = { _, _, _ -> exists },
            createMarker = {
                creations++
                exists = true
                marker
            },
            isMarkerAccessible = { _, _ -> ManagedDownloadReferenceIo.AccessResult.Accessible },
            rememberMarker = { _, _ -> }
        )

        ensure()
        exists = false
        ensure()

        assertEquals(2, creations)
    }

    @Test
    fun `SAF directory isolation leaves an existing marker alone`() {
        val context = mock(Context::class.java)
        val directory = mock(DocumentFile::class.java)
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn("content://test/tree/root/document/Covers")
        `when`(directory.uri).thenReturn(uri)
        val ensured = ConcurrentHashMap<String, Boolean>()
        var creations = 0

        ManagedDownloadMediaScanIsolation.ensureTreeDirectory(
            context, "Covers", directory, ensured,
            hasCachedChild = { _, _, _ -> true },
            createMarker = { creations++; null },
            isMarkerAccessible = { _, _ -> ManagedDownloadReferenceIo.AccessResult.Accessible },
            rememberMarker = { _, _ -> }
        )

        assertEquals(0, creations)
    }

    @Test
    fun `file directory isolation skips nomedia marker for lyric directory`() {
        val lyricDirectory = tempFolder.newFolder("Lyrics")

        ensureManagedMediaScanIsolation("Lyrics", lyricDirectory)

        assertFalse(File(lyricDirectory, ".nomedia").exists())
    }

    @Test
    fun `file directory isolation creates nomedia marker for temporary directory`() {
        val temporaryDirectory = tempFolder.newFolder(".tmp")

        ensureManagedMediaScanIsolation(".tmp", temporaryDirectory)

        assertTrue(File(temporaryDirectory, ".nomedia").exists())
    }

    private fun ensureManagedMediaScanIsolation(subdirectory: String, directory: File) {
        ManagedDownloadMediaScanIsolation.ensureFileDirectory(
            subdirectory = subdirectory,
            directory = directory,
            ensuredMarkers = ConcurrentHashMap()
        )
    }
}
