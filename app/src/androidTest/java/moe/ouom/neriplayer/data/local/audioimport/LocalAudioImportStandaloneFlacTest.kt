package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadMigrationTestDocumentProvider
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioImportStandaloneFlacTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID
    )
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID
    )
    private var previousDirectoryUri: String? = null
    private val fixtures = listOf(
        FlacFixture(
            fileName = "netease - 鹿乃 - 夜明けと蛍 (1).flac",
            title = "夜明けと蛍",
            artist = "鹿乃",
            album = "Embedded album one"
        ),
        FlacFixture(
            fileName = "netease - Yael Naim - New Soul.flac",
            title = "New Soul",
            artist = "Yael Naïm",
            album = "Embedded album two"
        )
    )

    @Before
    fun setUp() {
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        resetProvider()
        ManagedDownloadStorage.updateCustomDirectoryUri(treeUri.toString())
    }

    @After
    fun tearDown() {
        ManagedDownloadStorage.updateCustomDirectoryUri(previousDirectoryUri)
        resetProvider()
    }

    @Test
    fun managedFolderReadsBothStandaloneFlacTags() = runBlocking {
        fixtures.forEach(::createAudio)

        assertStandaloneSongs(LocalAudioImportManager.scanFolderSongs(context, treeUri))
        val children = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)).listFiles()
        assertFalse(children.any { it.name.orEmpty().contains(".npmeta") })
    }

    @Test
    fun managedFolderDoesNotTrustPartialMediaStoreResults() = runBlocking {
        val uri = createAudio(fixtures.first())
        createAudio(fixtures.last())
        val indexedSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = uri.toString(),
                displayName = fixtures.first().fileName,
                title = null,
                artist = null,
                album = null,
                durationMs = null
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        val result = LocalAudioImportManager.scanFolderSongsWithMediaStoreResultForTest(
            context = context,
            folderUri = treeUri,
            mediaStoreResult = LocalAudioImportResult(
                songs = listOf(indexedSong),
                failedCount = 0,
                completed = true
            )
        )

        assertStandaloneSongs(result)
    }

    @Test
    fun explicitFolderScanRefreshesTheCachedDownloadSnapshot() = runBlocking {
        createAudio(fixtures.first())
        ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        createAudio(fixtures.last())

        assertStandaloneSongs(LocalAudioImportManager.scanFolderSongs(context, treeUri))
    }

    @Test
    fun documentFileFallbackKeepsStandaloneFlacCandidates() = runBlocking {
        fixtures.forEach(::createAudio)
        val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
        val result = LocalAudioImportManager.collectFolderCandidatesWithDocumentFile(
            context = context,
            root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)),
            progress = LocalAudioScanProgressEmitter(
                scanId = 1L,
                startedAt = SystemClock.elapsedRealtime(),
                onProgress = {}
            ),
            managedDownloadGate = ManagedDownloadCandidatePublicationGate(
                snapshot = snapshot,
                treeDocumentId = ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID
            )
        )

        assertEquals(0, result.failedCount)
        assertEquals(fixtures.map { it.fileName }.toSet(), result.candidates.map { it.displayName }.toSet())
    }

    @Test
    fun standaloneFallbackStillWithholdsUnfinishedDownloads() = runBlocking {
        fixtures.forEach(::createAudio)
        val unfinished = fixtures.first().copy(fileName = "unfinished.flac")
        createAudio(unfinished)
        val metadataUri = createDocument("${unfinished.fileName}.npmeta.json", "application/json")
        requireNotNull(context.contentResolver.openOutputStream(metadataUri)).use { output ->
            output.write("{\"downloadFinalized\":false}".toByteArray(Charsets.UTF_8))
        }
        createAudio(fixtures.first().copy(fileName = "pending.flac.npdl_pending.001.pending"))

        assertStandaloneSongs(LocalAudioImportManager.scanFolderSongs(context, treeUri))
    }

    private fun assertStandaloneSongs(result: LocalAudioImportResult) {
        assertTrue(result.completed)
        assertEquals(0, result.failedCount)
        assertFalse(result.metadataDeferred)
        assertEquals(fixtures.size, result.songs.size)
        fixtures.forEach { fixture ->
            val song = result.songs.single { it.localFileName == fixture.fileName }
            assertEquals(fixture.title, song.name)
            assertEquals(fixture.artist, song.artist)
            assertEquals(fixture.album, song.album)
            assertTrue("FLAC duration was not read: ${song.durationMs}", song.durationMs >= 900L)
        }
    }

    private fun createAudio(fixture: FlacFixture): Uri {
        val file = File.createTempFile("standalone-scan-", ".flac", context.cacheDir)
        try {
            file.writeBytes(Base64.decode(SILENT_FLAC, Base64.DEFAULT))
            val properties = hashMapOf(
                "TITLE" to arrayOf(fixture.title),
                "ARTIST" to arrayOf(fixture.artist),
                "ALBUM" to arrayOf(fixture.album)
            )
            val written = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
                TagLib.savePropertyMap(it.dup().detachFd(), properties)
            }
            assertTrue("failed to tag synthetic FLAC", written)
            val uri = createDocument(fixture.fileName, "audio/flac")
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            return uri
        } finally {
            file.delete()
        }
    }

    private fun createDocument(name: String, mimeType: String): Uri {
        return requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, rootUri, mimeType, name)
        )
    }

    private fun resetProvider() {
        context.contentResolver.call(
            rootUri,
            ManagedDownloadMigrationTestDocumentProvider.RESET,
            null,
            null
        )
    }

    private data class FlacFixture(
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String
    )

    private companion object {
        // 一秒 8 kHz 单声道静音，由合成 PCM 经 afconvert 编码，不包含歌曲内容
        const val SILENT_FLAC = "ZkxhQwAAACISABIAAAALAAANAfQA8AAAH0Ae4Bk2cWCcfWPP6JuSCtMThAAANgUA" +
            "AABBcHBsZQEAAAAlAAAAV0FWRUZPUk1BVEVYVEVOU0lCTEVfQ0hBTk5FTF9NQVNLPTB4NP/4VAgArQAAANVn" +
            "//h0CAENP6oAAAAw/A=="
    }
}
