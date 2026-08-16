package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.Issue339LyricsTestDocumentProvider
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAudioImportSafLyricsTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importExternalDocumentCopiesLyricsDirectorySidecars() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            "moe.ouom.neriplayer.test.issue339lyrics",
            "opaque/audio-issue339"
        )

        val result = LocalAudioImportManager.importExternalSongs(targetContext, listOf(audioUri))

        assertEquals(0, result.failedCount)
        val importedFile = File(requireNotNull(result.songs.single().localFilePath))
        try {
            val details = LocalMediaSupport.inspect(targetContext, Uri.fromFile(importedFile))
            assertEquals("[00:00.10]original from Lyrics", details.lyricContent)
            assertEquals("[00:00.10]translated from Lyrics", details.translatedLyricContent)
            assertEquals("[00:00.10]romanized from Lyrics", details.romanizedLyricContent)
            assertNotNull(details.lyricPath)
        } finally {
            importedFile.delete()
            File(importedFile.parentFile, "${importedFile.nameWithoutExtension}.lrc").delete()
            File(importedFile.parentFile, "${importedFile.nameWithoutExtension}_trans.lrc").delete()
            File(importedFile.parentFile, "${importedFile.nameWithoutExtension}_roma.lrc").delete()
        }
    }

    @Test
    fun scanFolderReturnsFastEntriesAndHydratesLyricsAfterImport() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )

        val result = LocalAudioImportManager.scanFolderSongs(targetContext, treeUri)

        assertEquals(0, result.failedCount)
        assertTrue(result.metadataDeferred)
        val song = result.songs.single()
        assertNull(song.matchedLyric)
        val hydratedSong = LocalAudioImportManager.hydrateLocalSongTextMetadata(
            context = targetContext,
            song = song
        )
        assertEquals("[00:00.10]original from Lyrics", hydratedSong.matchedLyric)
        assertEquals("[00:00.10]translated from Lyrics", hydratedSong.matchedTranslatedLyric)
    }

    @Test
    fun emptyMediaStoreResultRunsTheSafFallbackScan() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )

        val result = LocalAudioImportManager.scanFolderSongsWithMediaStoreResultForTest(
            context = targetContext,
            folderUri = treeUri,
            mediaStoreResult = LocalAudioImportResult(
                songs = emptyList(),
                failedCount = 0,
                completed = true
            )
        )

        assertEquals(0, result.failedCount)
        assertEquals(1, result.songs.size)
        assertEquals(
            Issue339LyricsTestDocumentProvider.AUDIO_NAME,
            result.songs.single().localFileName
        )
    }

    @Test
    fun safAudioWithUnknownSizeIsStillReadable() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val entry = ManagedDownloadStorage.StoredEntry(
            name = Issue339LyricsTestDocumentProvider.AUDIO_NAME,
            reference = audioUri.toString(),
            mediaUri = audioUri.toString(),
            localFilePath = null,
            sizeBytes = 0L,
            lastModifiedMs = 0L,
            isDirectory = false
        )

        assertTrue(ManagedDownloadStorage.hasReadableContent(targetContext, entry))

        val emptyFile = File.createTempFile("empty-audio", ".wav", targetContext.cacheDir)
        try {
            assertEquals(0L, emptyFile.length())
            assertFalse(
                ManagedDownloadStorage.hasReadableContent(
                    targetContext,
                    entry.copy(
                        name = emptyFile.name,
                        reference = emptyFile.absolutePath,
                        mediaUri = emptyFile.toURI().toString(),
                        localFilePath = emptyFile.absolutePath
                    )
                )
            )
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun cancelledSafScanPropagatesCancellation() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        var cancelled = false
        try {
            LocalAudioImportManager.scanFolderSongsWithMediaStoreResultForTest(
                context = targetContext,
                folderUri = treeUri,
                mediaStoreResult = LocalAudioImportResult(
                    songs = emptyList(),
                    failedCount = 0,
                    completed = true
                ),
                onProgress = { throw CancellationException("scan cancelled") }
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun largeFolderScanUsesDirectoryRowsWithoutPerAudioQueries() = runBlocking {
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioCount = 2_000
        targetContext.contentResolver.call(
            providerUri,
            "test:configureLargeScan",
            null,
            Bundle().apply { putInt("count", audioCount) }
        )

        val startedAt = SystemClock.elapsedRealtime()
        try {
            val result = LocalAudioImportManager.scanFolderSongs(
                targetContext,
                DocumentsContract.buildTreeDocumentUri(
                    Issue339LyricsTestDocumentProvider.AUTHORITY,
                    Issue339LyricsTestDocumentProvider.ROOT_ID
                )
            )
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val queryCount = targetContext.contentResolver.call(
                providerUri,
                "test:queryLargeScanCount",
                null,
                null
            )?.getInt("result", -1)

            assertEquals(0, result.failedCount)
            assertEquals(audioCount, result.songs.size)
            assertEquals(0, queryCount)
            assertTrue(
                "large SAF scan took ${elapsedMs}ms",
                elapsedMs < 3_000L
            )
        } finally {
            targetContext.contentResolver.call(providerUri, "test:resetLargeScan", null, null)
        }
    }

    @Test
    fun quickSafSongHydratesLyricsAfterImport() {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val quickSong = LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = audioUri.toString(),
                displayName = Issue339LyricsTestDocumentProvider.AUDIO_NAME,
                title = null,
                artist = null,
                album = null,
                durationMs = null
            ),
            unknownArtistLabel = "Unknown Artist"
        )

        val hydratedSong = LocalAudioImportManager.hydrateLocalSongTextMetadata(
            context = targetContext,
            song = quickSong
        )

        assertEquals(
            "[00:00.10]original from Lyrics",
            hydratedSong.matchedLyric
        )
        assertEquals(
            "[00:00.10]translated from Lyrics",
            hydratedSong.matchedTranslatedLyric
        )
    }

    @Test
    fun importExternalDocumentCopiesLocalMetadataSidecar() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            "moe.ouom.neriplayer.test.issue339lyrics",
            "opaque/audio-issue339"
        )
        val sourceSong = SongItem(
            id = 339L,
            name = "Issue 339",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]saved original",
            matchedTranslatedLyric = "[00:01.00]saved translation",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        assertEquals(
            "SUCCESS",
            LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = sourceSong,
                writeCover = false,
                writeLyrics = true
            ).name
        )

        val result = LocalAudioImportManager.importExternalSongs(targetContext, listOf(audioUri))
        val importedFile = File(requireNotNull(result.songs.single().localFilePath))
        val metadata = File(importedFile.parentFile, importedFile.name + ".npmeta.json")
        try {
            assertEquals(0, result.failedCount)
            assertTrue(metadata.isFile)
            val details = LocalMediaSupport.inspect(targetContext, Uri.fromFile(importedFile))
            assertEquals("[00:01.00]saved original", details.lyricContent)
            assertEquals("[00:01.00]saved translation", details.translatedLyricContent)
        } finally {
            DocumentsContract.deleteDocument(
                targetContext.contentResolver,
                DocumentsContract.buildDocumentUri(
                    "moe.ouom.neriplayer.test.issue339lyrics",
                    "opaque/metadata-issue339"
                )
            )
            metadata.delete()
            importedFile.delete()
            File(importedFile.parentFile, "${importedFile.nameWithoutExtension}.lrc").delete()
            File(importedFile.parentFile, "${importedFile.nameWithoutExtension}_trans.lrc").delete()
            File(importedFile.parentFile, "${importedFile.nameWithoutExtension}_roma.lrc").delete()
        }
    }
}
