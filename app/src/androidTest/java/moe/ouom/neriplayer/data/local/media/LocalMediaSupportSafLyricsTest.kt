package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaSupportSafLyricsTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetProviderLyricsFixtures() {
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        targetContext.contentResolver.call(
            providerUri,
            Issue339LyricsTestDocumentProvider.RESET_LYRICS,
            null,
            null
        )
    }

    @Test
    fun inspectContentDocumentReadsLyricsDirectorySidecarsForOpaqueDocumentIds() {
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )

        val details = LocalMediaSupport.inspect(targetContext, audioUri)

        assertEquals(
            "[00:00.10]original from Lyrics",
            details.lyricContent
        )
        assertEquals(
            "[00:00.10]translated from Lyrics",
            details.translatedLyricContent
        )
        assertEquals(
            "[00:00.10]romanized from Lyrics",
            details.romanizedLyricContent
        )
        assertNotNull(details.lyricPath)
        assertEquals("content", Uri.parse(details.lyricPath).scheme)
    }

    @Test
    fun inspectPlainDocumentReadsLyricsDirectorySidecars() {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )

        val details = LocalMediaSupport.inspect(targetContext, audioUri)

        assertEquals(
            "[00:00.10]original from Lyrics",
            details.lyricContent
        )
        assertEquals(
            "[00:00.10]translated from Lyrics",
            details.translatedLyricContent
        )
        assertEquals(
            "[00:00.10]romanized from Lyrics",
            details.romanizedLyricContent
        )
        assertNotNull(details.lyricPath)
    }

    @Test
    fun writeLyricsToLocalFileCreatesMetadataSidecar() = runBlocking {
        val audio = File.createTempFile("issue339-local-", ".wav", targetContext.cacheDir)
        val metadata = File(audio.parentFile, audio.name + ".npmeta.json")
        audio.writeBytes(byteArrayOf(0))
        try {
            val song = SongItem(
                id = 339L,
                name = "Local song",
                artist = "Artist",
                album = "Local",
                albumId = 0L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = audio.toURI().toString(),
                matchedLyric = "[00:01.00]local original",
                matchedTranslatedLyric = "[00:01.00]local translation",
                localFileName = audio.name,
                localFilePath = audio.absolutePath
            )
            val outcome = LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )

            assertEquals("SUCCESS", outcome.name)
            assertTrue(metadata.isFile)
            val parsed = LocalMediaSupport.parseLocalMetadataSidecar(
                metadata.absolutePath,
                metadata.readText()
            )
            assertEquals("[00:01.00]local original", parsed?.lyric)
            assertEquals("[00:01.00]local translation", parsed?.translatedLyric)
        } finally {
            metadata.delete()
            audio.delete()
        }
    }

    @Test
    fun writeLyricsToSafDocumentCreatesMetadataSidecar() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 340L,
            name = "SAF song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]saf original",
            matchedTranslatedLyric = "[00:01.00]saf translation",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        val outcome = LocalMediaSupport.writeEditableMetadata(
            context = targetContext,
            song = song,
            writeCover = false,
            writeLyrics = true
        )
        assertEquals("SUCCESS", outcome.name)

        val metadataUri = findMetadataUri()
        try {
            assertNotNull(metadataUri)
            val raw = LocalMediaSupport.readTextContent(targetContext, metadataUri.toString())
            val parsed = LocalMediaSupport.parseLocalMetadataSidecar(metadataUri.toString(), raw.orEmpty())
            assertEquals("[00:01.00]saf original", parsed?.lyric)
            assertEquals("[00:01.00]saf translation", parsed?.translatedLyric)
            val details = LocalMediaSupport.inspect(targetContext, audioUri)
            assertEquals("[00:01.00]saf original", details.lyricContent)
            assertEquals("[00:01.00]saf translation", details.translatedLyricContent)
        } finally {
            metadataUri?.let { DocumentsContract.deleteDocument(targetContext.contentResolver, it) }
        }
    }

    private fun findMetadataUri(): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.MUSIC_ID
        )
        return targetContext.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == Issue339LyricsTestDocumentProvider.METADATA_NAME) {
                    return@use DocumentsContract.buildDocumentUri(
                        Issue339LyricsTestDocumentProvider.AUTHORITY,
                        cursor.getString(idIndex)
                    )
                }
            }
            null
        }
    }

    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        Issue339LyricsTestDocumentProvider.AUTHORITY,
        Issue339LyricsTestDocumentProvider.ROOT_ID
    )
}
