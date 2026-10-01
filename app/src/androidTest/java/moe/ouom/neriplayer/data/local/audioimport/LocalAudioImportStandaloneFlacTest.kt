package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.model.local.LocalAudioImportResult

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadMigrationTestDocumentProvider
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.playback.LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    private val targetTreeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.TARGET_ROOT_ID
    )
    private val targetRootUri = DocumentsContract.buildDocumentUriUsingTree(
        targetTreeUri,
        ManagedDownloadMigrationTestDocumentProvider.TARGET_ROOT_ID
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
        val indexedSong = LocalAudioImportTestSupport.buildQuickImportedSong(
            sourceRef = uri.toString(),
            displayName = fixtures.first().fileName
        )

        val result = LocalAudioImportTestSupport.scanWithMediaStoreResult(
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
        val result = LocalAudioImportTestSupport.collectDocumentFileCandidates(
            context = context,
            root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)),
            managedDownloadGate = ManagedDownloadCandidatePublicationGate(
                snapshot = snapshot,
                treeDocumentId = ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID
            )
        )

        assertEquals(0, result.failedCount)
        assertEquals(fixtures.map { it.fileName }.toSet(), result.candidateDisplayNames.toSet())
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

    @Test
    fun externalImportRemainsPlayableWithAnotherSafDownloadRoot() = runBlocking {
        AudioDownloadManager.initialize(context)
        val sourceUri = createAudio(fixtures.first())
        ManagedDownloadStorage.updateCustomDirectoryUri(targetTreeUri.toString())

        val result = LocalAudioImportManager.importExternalSongs(context, listOf(sourceUri))
        assertEquals(0, result.failedCount)
        val importedSong = result.songs.single()
        val importedFile = File(requireNotNull(importedSong.localFilePath))
        try {
            assertTrue(importedFile.isFile)
            assertTrue(importedFile.length() > 0L)
            assertEquals(
                File(LocalMediaSupport.downloadDirectory(context), "Imports").canonicalFile,
                importedFile.parentFile?.canonicalFile
            )
            assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, importedSong))
            assertEquals(
                LocalPlaybackReferenceResolution.Playable(importedFile.absolutePath),
                AudioDownloadManager.resolvePermittedLocalPlayback(
                    context, importedSong, importedFile.absolutePath
                )
            )

            val detailedSong = LocalMediaSupport.toSongItem(
                requireNotNull(LocalMediaSupport.inspect(context, importedSong))
            )
            val hydratedSong = LocalAudioImportManager.mergeImportedSongMetadata(importedSong, detailedSong)
            assertEquals(fixtures.first().title, hydratedSong.name)
            assertEquals(importedSong.id, hydratedSong.id)
            assertEquals(importedFile.absolutePath, hydratedSong.localFilePath)
            assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, hydratedSong))
            assertEquals(
                LocalPlaybackReferenceResolution.Playable(importedFile.absolutePath),
                AudioDownloadManager.resolvePermittedLocalPlayback(
                    context, hydratedSong, hydratedSong.localFilePath
                )
            )
        } finally {
            removeOwnedAudioAndSidecars(importedFile)
        }
    }

    @Test
    fun externalImportKeepsItsLocalCopyWhenRemoteDownloadIsCached() = runBlocking {
        AudioDownloadManager.initialize(context)
        val fixture = fixtures.first().copy(fileName = "cached-import-${UUID.randomUUID()}.flac")
        val sourceUri = createAudio(fixture)
        ManagedDownloadStorage.updateCustomDirectoryUri(targetTreeUri.toString())
        val result = LocalAudioImportManager.importExternalSongs(context, listOf(sourceUri))
        assertEquals(0, result.failedCount)
        val importedSong = result.songs.single()
        val importedFile = File(requireNotNull(importedSong.localFilePath))
        try {
            val stableKey = "42|netease|"
            val detailedSong = LocalMediaSupport.toSongItem(
                requireNotNull(LocalMediaSupport.inspect(context, importedSong))
            ).copy(sourceStableKey = stableKey)
            val hydratedSong = LocalAudioImportManager.mergeImportedSongMetadata(importedSong, detailedSong)
            assertEquals("local", hydratedSong.channelId)
            assertEquals(stableKey, hydratedSong.sourceStableKey)
            assertEquals(importedFile.absolutePath, hydratedSong.localFilePath)

            val downloadedFixture = fixture.copy(fileName = "cached-download-${UUID.randomUUID()}.flac")
            val downloadedUri = createAudio(downloadedFixture, targetRootUri)
            val metadataUri = createDocument(
                "${downloadedFixture.fileName}.npmeta.json", "application/json", targetRootUri
            )
            val metadata = JSONObject().apply {
                put("stableKey", stableKey)
                put("songId", 42L)
                put("identityAlbum", "netease")
                put("name", fixture.title)
                put("artist", fixture.artist)
                put("mediaUri", downloadedUri.toString())
                put("downloadFinalized", true)
                put("createdAtMs", 123456L)
            }
            requireNotNull(context.contentResolver.openOutputStream(metadataUri)).use { output ->
                output.write(metadata.toString().toByteArray(Charsets.UTF_8))
            }
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            val cachedAudio = ManagedDownloadStorage.peekDownloadedAudio(hydratedSong)
            assertNotNull("Expected the hydrated source identity to hit the SAF download", cachedAudio)
            val cachedUri = Uri.parse(requireNotNull(cachedAudio).reference)
            assertEquals(downloadedUri.authority, cachedUri.authority)
            assertEquals(
                DocumentsContract.getDocumentId(downloadedUri),
                DocumentsContract.getDocumentId(cachedUri)
            )
            assertTrue(importedFile.isFile)
            assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, hydratedSong))
            assertEquals(
                LocalPlaybackReferenceResolution.Playable(importedFile.absolutePath),
                AudioDownloadManager.resolvePermittedLocalPlayback(
                    context, hydratedSong, hydratedSong.localFilePath
                )
            )
        } finally {
            removeOwnedAudioAndSidecars(importedFile)
        }
    }

    @Test
    fun externalImportUsesItsOwnCopyInsteadOfReadableSourceAlias() = runBlocking {
        AudioDownloadManager.initialize(context)
        val sourceUri = createAudio(
            fixtures.first().copy(fileName = "source-alias-${UUID.randomUUID()}.flac")
        )
        ManagedDownloadStorage.updateCustomDirectoryUri(targetTreeUri.toString())
        val result = LocalAudioImportManager.importExternalSongs(context, listOf(sourceUri))
        assertEquals(0, result.failedCount)
        val importedSong = result.songs.single()
        val importedFile = File(requireNotNull(importedSong.localFilePath))
        try {
            val songWithAlias = importedSong.copy(mediaUri = sourceUri.toString())
            assertReadableAudio(sourceUri)
            assertTrue(importedFile.isFile)
            assertEquals(
                importedFile.canonicalPath,
                AudioDownloadManager.getLocalPlaybackUri(context, songWithAlias)
            )
            assertEquals(
                LocalPlaybackReferenceResolution.Playable(importedFile.canonicalPath),
                AudioDownloadManager.resolveIndexedLocalPlaybackReference(context, songWithAlias)
            )
            for (rawReference in listOf(sourceUri.toString(), importedFile.absolutePath, null)) {
                assertEquals(
                    "The source alias must not replace the imported copy: raw=$rawReference",
                    LocalPlaybackReferenceResolution.Playable(importedFile.canonicalPath),
                    AudioDownloadManager.resolvePermittedLocalPlayback(context, songWithAlias, rawReference)
                )
            }
            assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, songWithAlias))
        } finally {
            removeOwnedAudioAndSidecars(importedFile)
        }
    }

    @Test
    fun externalImportIgnoresPendingAliasAndSameSourceDownloadCache() = runBlocking {
        AudioDownloadManager.initialize(context)
        val sourceUri = createAudio(
            fixtures.first().copy(fileName = "pending-alias-import-${UUID.randomUUID()}.flac")
        )
        ManagedDownloadStorage.updateCustomDirectoryUri(targetTreeUri.toString())
        val result = LocalAudioImportManager.importExternalSongs(context, listOf(sourceUri))
        assertEquals(0, result.failedCount)
        val importedSong = result.songs.single()
        val importedFile = File(requireNotNull(importedSong.localFilePath))
        try {
            val stableKey = "42|netease|"
            val pendingFixture = fixtures.first().copy(
                fileName = "pending-alias-${UUID.randomUUID()}.flac.npdl_pending.001.pending"
            )
            val pendingUri = createAudio(pendingFixture, targetRootUri)
            val downloadedUri = createFinalizedDownload(stableKey)
            val songWithAlias = importedSong.copy(
                mediaUri = pendingUri.toString(),
                sourceStableKey = stableKey
            )
            assertEquals("local", songWithAlias.channelId)
            assertReadableAudio(pendingUri)
            val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            assertTrue(snapshot.pendingAudioEntries.any { it.name == pendingFixture.fileName })
            val cachedAudio = ManagedDownloadStorage.peekDownloadedAudio(songWithAlias)
            assertNotNull("Expected a real same-source SAF download cache hit", cachedAudio)
            assertSameDocument(downloadedUri, Uri.parse(requireNotNull(cachedAudio).reference))
            assertTrue(importedFile.isFile)
            assertEquals(
                importedFile.canonicalPath,
                AudioDownloadManager.getLocalPlaybackUri(context, songWithAlias)
            )
            assertEquals(
                LocalPlaybackReferenceResolution.Playable(importedFile.canonicalPath),
                AudioDownloadManager.resolveIndexedLocalPlaybackReference(context, songWithAlias)
            )
            for (rawReference in listOf(pendingUri.toString(), importedFile.absolutePath, null)) {
                assertEquals(
                    "Pending and cached download aliases must not replace the imported copy: raw=$rawReference",
                    LocalPlaybackReferenceResolution.Playable(importedFile.canonicalPath),
                    AudioDownloadManager.resolvePermittedLocalPlayback(context, songWithAlias, rawReference)
                )
            }
            assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, songWithAlias))
        } finally {
            removeOwnedAudioAndSidecars(importedFile)
        }
    }

    @Test
    fun missingExternalImportDoesNotFallBackToReadableAliasOrSameSourceDownload() = runBlocking {
        AudioDownloadManager.initialize(context)
        val sourceUri = createAudio(
            fixtures.first().copy(fileName = "missing-alias-import-${UUID.randomUUID()}.flac")
        )
        ManagedDownloadStorage.updateCustomDirectoryUri(targetTreeUri.toString())
        val result = LocalAudioImportManager.importExternalSongs(context, listOf(sourceUri))
        assertEquals(0, result.failedCount)
        val importedSong = result.songs.single()
        val importedFile = File(requireNotNull(importedSong.localFilePath))
        try {
            val stableKey = "42|netease|"
            val downloadedUri = createFinalizedDownload(stableKey)
            val songWithAlias = importedSong.copy(
                mediaUri = sourceUri.toString(),
                sourceStableKey = stableKey
            )
            assertEquals("local", songWithAlias.channelId)
            ManagedDownloadStorage.buildDownloadLibrarySnapshot(context, forceRefresh = true)
            val cachedAudio = ManagedDownloadStorage.peekDownloadedAudio(songWithAlias)
            assertNotNull("Expected a real same-source SAF download cache hit", cachedAudio)
            assertSameDocument(downloadedUri, Uri.parse(requireNotNull(cachedAudio).reference))
            assertTrue("Cannot remove the owned imported copy", importedFile.delete())
            assertReadableAudio(sourceUri)
            assertReadableAudio(downloadedUri)
            assertNull(AudioDownloadManager.getLocalPlaybackUri(context, songWithAlias))
            assertEquals(
                LocalPlaybackReferenceResolution.Missing,
                AudioDownloadManager.resolveIndexedLocalPlaybackReference(context, songWithAlias)
            )
            for (rawReference in listOf(sourceUri.toString(), importedFile.absolutePath, null)) {
                assertEquals(
                    "A missing imported copy must not borrow another readable reference: raw=$rawReference",
                    LocalPlaybackReferenceResolution.Missing,
                    AudioDownloadManager.resolvePermittedLocalPlayback(context, songWithAlias, rawReference)
                )
            }
            assertFalse(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, songWithAlias))
        } finally {
            removeOwnedAudioAndSidecars(importedFile)
        }
    }

    @Test
    fun defaultDownloadRootFileRemainsMissingWithAnotherSafDownloadRoot() = runBlocking {
        AudioDownloadManager.initialize(context)
        ManagedDownloadStorage.updateCustomDirectoryUri(targetTreeUri.toString())
        val audio = File(
            LocalMediaSupport.downloadDirectory(context),
            "default-root-regression-${UUID.randomUUID()}.flac"
        )
        try {
            audio.writeBytes(Base64.decode(SILENT_FLAC, Base64.DEFAULT))
            val song = LocalAudioImportManager.buildQuickImportedSong(context, Uri.fromFile(audio))
            assertTrue(audio.isFile)
            assertTrue(ManagedDownloadStorage.isLikelyManagedDownloadSongFast(context, song))
            assertEquals(
                LocalPlaybackReferenceResolution.Missing,
                AudioDownloadManager.resolvePermittedLocalPlayback(context, song, audio.absolutePath)
            )
        } finally {
            removeOwnedAudioAndSidecars(audio)
        }
    }

    private fun createFinalizedDownload(stableKey: String): Uri {
        val fixture = fixtures.first().copy(fileName = "same-source-download-${UUID.randomUUID()}.flac")
        val audioUri = createAudio(fixture, targetRootUri)
        val metadataUri = createDocument("${fixture.fileName}.npmeta.json", "application/json", targetRootUri)
        val metadata = JSONObject().apply {
            put("stableKey", stableKey)
            put("songId", 42L)
            put("identityAlbum", "netease")
            put("name", fixture.title)
            put("artist", fixture.artist)
            put("mediaUri", audioUri.toString())
            put("downloadFinalized", true)
            put("createdAtMs", 123456L)
        }
        requireNotNull(context.contentResolver.openOutputStream(metadataUri)).use { output ->
            output.write(metadata.toString().toByteArray(Charsets.UTF_8))
        }
        return audioUri
    }

    private fun assertReadableAudio(uri: Uri) {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            assertTrue("Expected a readable audio fixture: $uri", input.read() >= 0)
        }
    }

    private fun assertSameDocument(expected: Uri, actual: Uri) {
        assertEquals(expected.authority, actual.authority)
        assertEquals(DocumentsContract.getDocumentId(expected), DocumentsContract.getDocumentId(actual))
    }

    private fun removeOwnedAudioAndSidecars(audio: File) {
        val parent = requireNotNull(audio.parentFile)
        val baseName = audio.nameWithoutExtension
        val ownedFiles = listOf(
            audio,
            File(parent, "${audio.name}.npmeta.json"),
            File(parent, "${audio.name}.npmeta.pending.json"),
            File(parent, "$baseName.lrc"),
            File(parent, "${baseName}_trans.lrc"),
            File(parent, "${baseName}_roma.lrc")
        )
        ownedFiles.filter(File::exists).forEach { file ->
            assertTrue("Cannot remove owned fixture: ${file.name}", file.delete())
        }
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

    private fun createAudio(fixture: FlacFixture): Uri = createAudio(fixture, rootUri)

    private fun createAudio(fixture: FlacFixture, parentUri: Uri): Uri {
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
            val uri = createDocument(fixture.fileName, "audio/flac", parentUri)
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            return uri
        } finally {
            file.delete()
        }
    }

    private fun createDocument(name: String, mimeType: String, parentUri: Uri = rootUri): Uri {
        return requireNotNull(
            DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
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
