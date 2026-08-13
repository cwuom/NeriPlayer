package moe.ouom.neriplayer.data.local.audioimport

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.Issue339LyricsTestDocumentProvider
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun scanFolderCarriesLyricsDirectorySidecarsIntoSongItem() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )

        val result = LocalAudioImportManager.scanFolderSongs(targetContext, treeUri)

        assertEquals(0, result.failedCount)
        val song = result.songs.single()
        assertEquals(
            "[00:00.10]original from Lyrics",
            song.matchedLyric
        )
        assertEquals(
            "[00:00.10]translated from Lyrics",
            song.matchedTranslatedLyric
        )
        assertEquals(song.matchedLyric, song.originalLyric)
        assertEquals(song.matchedTranslatedLyric, song.originalTranslatedLyric)
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
