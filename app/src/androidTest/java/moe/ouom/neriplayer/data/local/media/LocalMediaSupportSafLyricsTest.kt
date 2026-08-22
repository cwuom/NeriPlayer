package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        LocalMediaSupport.clearLyricsLookupCache()
    }

    @Test
    fun fastManagedLyricsReadResolvesOpaqueDocumentIdOnColdStart() {
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 339L,
            name = "Issue 339",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            localFileName = "opaque%2Faudio-issue339"
        )
        try {
            ManagedDownloadStorage.primeSettings(treeUri.toString(), "Issue 339")
            assertTrue(
                ManagedDownloadStorage.isLikelyManagedDownloadSong(targetContext, song)
            )
            assertEquals(
                Issue339LyricsTestDocumentProvider.AUDIO_NAME,
                ManagedDownloadStorage.resolveManagedAudioDisplayName(targetContext, song)
            )
            val localFast = LocalMediaSupport.inspectLyricsFast(
                context = targetContext,
                song = song,
                includeStoredFallback = false,
                includeEmbeddedFallback = false
            )
            assertEquals("[00:00.10]original from Lyrics", localFast.lyric)
            val lyrics = ManagedDownloadStorage.readLyricsBundleFast(targetContext, song)

            assertEquals("[00:00.10]original from Lyrics", lyrics.lyric)
            assertEquals("[00:00.10]translated from Lyrics", lyrics.translatedLyric)
            assertEquals("[00:00.10]romanized from Lyrics", lyrics.romanizedLyric)
            assertTrue(lyrics.hasOriginalSidecar)
            assertTrue(lyrics.hasTranslatedSidecar)
            assertTrue(lyrics.hasRomanizedSidecar)
        } finally {
            ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        }
    }

    @Test
    fun nearbyCoverReadDoesNotCrashWhenProviderReturnsOutOfScopeChild() {
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 339L,
            name = "Issue 339",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        try {
            ManagedDownloadStorage.primeSettings(treeUri.toString(), "Issue 339")
            targetContext.contentResolver.call(
                providerUri,
                Issue339LyricsTestDocumentProvider.USE_OUT_OF_SCOPE_COVERS,
                null,
                null
            )

            assertNull(LocalMediaSupport.resolveNearbyCoverUri(targetContext, song))
        } finally {
            targetContext.contentResolver.call(
                providerUri,
                Issue339LyricsTestDocumentProvider.RESET_OUT_OF_SCOPE_COVERS,
                null,
                null
            )
            ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        }
    }

    @Test
    fun fastManagedLyricsReadRecoversSourceTreeBeforeSettingsRestore() {
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 339L,
            name = "Issue 339",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            localFileName = "opaque%2Faudio-issue339"
        )
        try {
            // 模拟首屏 catalog 已有歌曲, 但下载目录设置尚未恢复
            ManagedDownloadStorage.primeSettings(null, null)
            val lyrics = ManagedDownloadStorage.readLyricsBundleFast(targetContext, song)

            assertEquals("[00:00.10]original from Lyrics", lyrics.lyric)
            assertEquals("[00:00.10]translated from Lyrics", lyrics.translatedLyric)
            assertEquals("[00:00.10]romanized from Lyrics", lyrics.romanizedLyric)
            assertTrue(lyrics.hasOriginalSidecar)
            assertTrue(lyrics.hasTranslatedSidecar)
            assertTrue(lyrics.hasRomanizedSidecar)
        } finally {
            ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        }
    }

    @Test
    fun managedLyricsReadRefreshesAnEmptySafLyricsCache() {
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val song = SongItem(
            id = 339L,
            name = "Issue 339",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        try {
            ManagedDownloadStorage.primeSettings(treeUri.toString(), "Issue 339")
            targetContext.contentResolver.call(
                providerUri,
                Issue339LyricsTestDocumentProvider.CREATE_EMPTY_METADATA,
                null,
                null
            )
            targetContext.contentResolver.call(
                providerUri,
                Issue339LyricsTestDocumentProvider.CLEAR_LYRICS,
                null,
                null
            )
            runBlocking {
                ManagedDownloadStorage.buildDownloadLibrarySnapshot(
                    context = targetContext,
                    forceRefresh = true
                )
            }

            val initial = ManagedDownloadStorage.readLyricsBundleFast(targetContext, song)
            assertFalse(initial.hasOriginalSidecar)
            assertFalse(initial.hasTranslatedSidecar)
            assertFalse(initial.hasRomanizedSidecar)

            targetContext.contentResolver.call(
                providerUri,
                Issue339LyricsTestDocumentProvider.RESTORE_LYRICS,
                null,
                null
            )
            val fastAfterRecreate = ManagedDownloadStorage.readLyricsBundleFast(
                targetContext,
                song
            )
            assertEquals("[00:00.10]original from Lyrics", fastAfterRecreate.lyric)
            assertEquals("[00:00.10]translated from Lyrics", fastAfterRecreate.translatedLyric)
            assertEquals("[00:00.10]romanized from Lyrics", fastAfterRecreate.romanizedLyric)
            assertTrue(fastAfterRecreate.hasOriginalSidecar)
            assertTrue(fastAfterRecreate.hasTranslatedSidecar)
            assertTrue(fastAfterRecreate.hasRomanizedSidecar)
            val refreshed = ManagedDownloadStorage.readLyricsBundle(targetContext, song)

            assertEquals("[00:00.10]original from Lyrics", refreshed.lyric)
            assertEquals("[00:00.10]translated from Lyrics", refreshed.translatedLyric)
            assertEquals("[00:00.10]romanized from Lyrics", refreshed.romanizedLyric)
            assertTrue(refreshed.hasOriginalSidecar)
            assertTrue(refreshed.hasTranslatedSidecar)
            assertTrue(refreshed.hasRomanizedSidecar)
        } finally {
            ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        }
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

    @Test
    fun writeAllLyricVariantsToSafRecreatesDeletedSidecars() = runBlocking {
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        targetContext.contentResolver.call(
            providerUri,
            Issue339LyricsTestDocumentProvider.CLEAR_LYRICS,
            null,
            null
        )
        val song = SongItem(
            id = 345L,
            name = "SAF all variants",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]saf original recreated",
            matchedTranslatedLyric = "[00:01.00]saf translation recreated",
            matchedRomanizedLyric = "[00:01.00]saf romanized recreated",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )

        try {
            assertEquals(
                "SUCCESS",
                LocalMediaSupport.writeEditableMetadata(
                    context = targetContext,
                    song = song,
                    writeCover = false,
                    writeLyrics = true
                ).name
            )
            val details = LocalMediaSupport.inspect(targetContext, audioUri)
            assertEquals("[00:01.00]saf original recreated", details.lyricContent)
            assertEquals("[00:01.00]saf translation recreated", details.translatedLyricContent)
            assertEquals("[00:01.00]saf romanized recreated", details.romanizedLyricContent)
        } finally {
            targetContext.contentResolver.call(
                providerUri,
                Issue339LyricsTestDocumentProvider.RESET_LYRICS,
                null,
                null
            )
        }
    }

    @Test
    fun concurrentSafMetadataWritesCreateOneSidecar() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 341L,
            name = "Concurrent SAF song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]concurrent original",
            matchedTranslatedLyric = "[00:01.00]concurrent translation",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        val outcomes = coroutineScope {
            List(2) {
                async(Dispatchers.Default) {
                    LocalMediaSupport.writeEditableMetadata(
                        context = targetContext,
                        song = song,
                        writeCover = false,
                        writeLyrics = true
                    )
                }
            }.awaitAll()
        }
        val metadataUri = findMetadataUri()
        try {
            assertTrue(outcomes.all { it.name == "SUCCESS" })
            assertEquals(1, metadataCreateCount())
            assertNotNull(metadataUri)
        } finally {
            metadataUri?.let { DocumentsContract.deleteDocument(targetContext.contentResolver, it) }
        }
    }

    @Test
    fun safMetadataWriteDoesNotCreateSidecarWhenChildrenQueryFails() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 342L,
            name = "Unavailable SAF song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]unavailable original",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        setChildDocumentQueryFailure(enabled = true)
        val outcome = try {
            LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )
        } finally {
            setChildDocumentQueryFailure(enabled = false)
        }

        assertFalse(outcome.name == "SUCCESS")
        assertEquals(0, metadataCreateCount())
        assertNull(findMetadataUri())
    }

    @Test
    fun safMetadataWritePropagatesPermissionFailureWithoutCreatingSidecars() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 342L,
            name = "Revoked SAF permission",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]permission failure",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        setSecurityException(enabled = true)
        var permissionFailure: SecurityException? = null
        try {
            LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )
        } catch (error: SecurityException) {
            permissionFailure = error
        } finally {
            setSecurityException(enabled = false)
        }

        assertNotNull(permissionFailure)
        assertEquals(0, metadataCreateCount())
        assertEquals(0, lyricsDirectoryCreateCount())
        assertNull(findMetadataUri())
    }

    @Test
    fun safMetadataWriteDoesNotCreateSidecarWhenChildrenQueryOmitsSource() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 344L,
            name = "Empty SAF children",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]empty children original",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        setChildDocumentQueryEmpty(enabled = true)
        val outcome = try {
            LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )
        } finally {
            setChildDocumentQueryEmpty(enabled = false)
        }

        assertFalse(outcome.name == "SUCCESS")
        assertEquals(0, metadataCreateCount())
        assertEquals(0, lyricsDirectoryCreateCount())
        assertNull(findMetadataUri())
    }

    @Test
    fun safWriteReusesNumberedLyricsAndMetadataSidecars() = runBlocking {
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val song = SongItem(
            id = 343L,
            name = "Numbered SAF song",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedLyric = "[00:01.00]numbered original",
            matchedTranslatedLyric = "[00:01.00]numbered translation",
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        targetContext.contentResolver.call(
            providerUri,
            Issue339LyricsTestDocumentProvider.CREATE_EMPTY_METADATA,
            null,
            null
        )
        setNumberedSidecars(enabled = true)
        val metadataUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.METADATA_ID
        )
        try {
            val outcome = LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )

            assertEquals("SUCCESS", outcome.name)
            assertEquals(0, metadataCreateCount())
            assertEquals(0, lyricsDirectoryCreateCount())
            val raw = LocalMediaSupport.readTextContent(targetContext, metadataUri.toString())
            val parsed = LocalMediaSupport.parseLocalMetadataSidecar(metadataUri.toString(), raw.orEmpty())
            assertEquals("[00:01.00]numbered original", parsed?.lyric)
            assertEquals("[00:01.00]numbered translation", parsed?.translatedLyric)
        } finally {
            setNumberedSidecars(enabled = false)
            DocumentsContract.deleteDocument(targetContext.contentResolver, metadataUri)
        }
    }

    private fun metadataCreateCount(): Int {
        return targetContext.contentResolver.call(
            providerUri,
            Issue339LyricsTestDocumentProvider.QUERY_METADATA_CREATE_COUNT,
            null,
            null
        )?.getInt("result") ?: -1
    }

    private fun setChildDocumentQueryFailure(enabled: Boolean) {
        val method = if (enabled) {
            Issue339LyricsTestDocumentProvider.FAIL_CHILD_DOCUMENT_QUERIES
        } else {
            Issue339LyricsTestDocumentProvider.RESET_CHILD_DOCUMENT_QUERY_FAILURE
        }
        targetContext.contentResolver.call(providerUri, method, null, null)
    }

    private fun setSecurityException(enabled: Boolean) {
        val method = if (enabled) {
            Issue339LyricsTestDocumentProvider.FAIL_WITH_SECURITY_EXCEPTION
        } else {
            Issue339LyricsTestDocumentProvider.RESET_SECURITY_EXCEPTION
        }
        targetContext.contentResolver.call(providerUri, method, null, null)
    }

    private fun setChildDocumentQueryEmpty(enabled: Boolean) {
        val method = if (enabled) {
            Issue339LyricsTestDocumentProvider.EMPTY_CHILD_DOCUMENT_QUERIES
        } else {
            Issue339LyricsTestDocumentProvider.RESET_EMPTY_CHILD_DOCUMENT_QUERIES
        }
        targetContext.contentResolver.call(providerUri, method, null, null)
    }

    private fun setNumberedSidecars(enabled: Boolean) {
        val method = if (enabled) {
            Issue339LyricsTestDocumentProvider.USE_NUMBERED_SIDECARS
        } else {
            Issue339LyricsTestDocumentProvider.RESET_NUMBERED_SIDECARS
        }
        targetContext.contentResolver.call(providerUri, method, null, null)
    }

    private fun lyricsDirectoryCreateCount(): Int {
        return targetContext.contentResolver.call(
            providerUri,
            Issue339LyricsTestDocumentProvider.QUERY_LYRICS_DIRECTORY_CREATE_COUNT,
            null,
            null
        )?.getInt("result") ?: -1
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

    private val providerUri = DocumentsContract.buildDocumentUri(
        Issue339LyricsTestDocumentProvider.AUTHORITY,
        Issue339LyricsTestDocumentProvider.ROOT_ID
    )
}
