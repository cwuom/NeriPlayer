package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaSupportSafLyricsTest {
    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var recoveryDirectory: File
    private val targetContext = object : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = recoveryDirectory
    }

    @Before
    fun resetProviderLyricsFixtures() {
        // fixture 每次重置为新内容，恢复凭据也必须属于同一次测试
        recoveryDirectory = File(baseContext.cacheDir, "saf-lyrics-recovery-${UUID.randomUUID()}")
        check(recoveryDirectory.mkdirs())
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

    @After
    fun removeOwnedRecoveryFixture() {
        if (::recoveryDirectory.isInitialized) {
            check(recoveryDirectory.deleteRecursively())
        }
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

            assertEquals(LocalMediaMetadataWriteOutcome.SIDECAR_ONLY, outcome)
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
        assertEquals(LocalMediaMetadataWriteOutcome.SIDECAR_ONLY, outcome)

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
    fun verifiedExistingMetadataReferenceWritesWithoutChildEnumeration() {
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        targetContext.contentResolver.call(
            providerUri, Issue339LyricsTestDocumentProvider.CREATE_EMPTY_METADATA, null, null
        )
        val audioUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val metadataUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.METADATA_ID
        )
        val song = SongItem(
            id = 339L,
            name = "Edited title",
            artist = "Edited artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            localFileName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        )
        targetContext.contentResolver.call(
            providerUri, Issue339LyricsTestDocumentProvider.FAIL_CHILD_DOCUMENT_QUERIES, null, null
        )

        assertTrue(LocalMediaSupport.writeLocalLyricsMetadata(
            context = targetContext,
            sourceUri = audioUri,
            file = null,
            displayName = Issue339LyricsTestDocumentProvider.AUDIO_NAME,
            song = song,
            knownReference = metadataUri.toString(),
            writeFullMetadata = true,
            writeLyricFields = false,
            useVerifiedExistingReference = true
        ))
        val raw = LocalMediaSupport.readTextContent(targetContext, metadataUri.toString())
        assertTrue(raw?.contains("Edited title") == true)
        val childQueries = targetContext.contentResolver.call(
            providerUri, Issue339LyricsTestDocumentProvider.QUERY_MUSIC_CHILD_COUNT, null, null
        )?.getInt("result")
        assertEquals(0, childQueries)
    }

    @Test
    fun cachedManagedMetadataSidecarUpdatesWithoutChildEnumeration() = runBlocking {
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val metadataUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, Issue339LyricsTestDocumentProvider.METADATA_ID
        )
        val audioName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        val audio = ManagedDownloadStorage.StoredEntry(
            name = audioName,
            reference = audioUri.toString(),
            mediaUri = audioUri.toString(),
            localFilePath = null,
            sizeBytes = 100,
            lastModifiedMs = 1
        )
        val metadata = audio.copy(
            name = "$audioName.npmeta.json",
            reference = metadataUri.toString(),
            mediaUri = metadataUri.toString()
        )
        val song = SongItem(
            id = 339L,
            name = "Edited title",
            artist = "Edited artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            localFileName = audioName
        )
        try {
            targetContext.contentResolver.call(
                providerUri, Issue339LyricsTestDocumentProvider.CREATE_EMPTY_METADATA, null, null
            )
            ManagedDownloadStorage.primeSettings(treeUri.toString(), "Issue 339")
            ManagedDownloadStorage.snapshotCacheStore.putSnapshot(
                context = targetContext,
                cacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(targetContext),
                snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
                    audioEntries = listOf(audio),
                    audioEntriesByLookupKey = mapOf(audio.reference to audio),
                    metadataEntriesByAudioName = mapOf(audioName to metadata)
                )
            )
            assertEquals(
                metadataUri.toString(),
                LocalMediaSupport.selectCachedEditableMetadataReference(
                    ManagedDownloadStorage.cachedDownloadLibrarySnapshot(
                        targetContext, restorePersisted = false
                    ),
                    audioUri.toString(),
                    audioName
                )
            )
            assertEquals(
                metadataUri.toString(),
                LocalMediaSupport.resolveCachedEditableMetadataReference(
                    targetContext, audioUri, audioName
                )
            )
            targetContext.contentResolver.call(
                providerUri, Issue339LyricsTestDocumentProvider.FAIL_CHILD_DOCUMENT_QUERIES,
                null, null
            )

            assertTrue(LocalMediaSupport.writeLocalMetadataSidecar(targetContext, song))
            val raw = LocalMediaSupport.readTextContent(targetContext, metadataUri.toString())
            assertTrue(raw?.contains("Edited title") == true)
            val childQueries = targetContext.contentResolver.call(
                providerUri, Issue339LyricsTestDocumentProvider.QUERY_MUSIC_CHILD_COUNT,
                null, null
            )?.getInt("result")
            assertEquals(0, childQueries)
        } finally {
            ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
        }
    }

    @Test
    fun cachedManagedLyricsCreationDoesNotRescanAudioDirectory() = runBlocking {
        val previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val providerUri = DocumentsContract.buildDocumentUri(
            Issue339LyricsTestDocumentProvider.AUTHORITY,
            Issue339LyricsTestDocumentProvider.ROOT_ID
        )
        val audioUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, Issue339LyricsTestDocumentProvider.AUDIO_ID
        )
        val metadataUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, Issue339LyricsTestDocumentProvider.METADATA_ID
        )
        val audioName = Issue339LyricsTestDocumentProvider.AUDIO_NAME
        val audio = ManagedDownloadStorage.StoredEntry(
            name = audioName,
            reference = audioUri.toString(),
            mediaUri = audioUri.toString(),
            localFilePath = null,
            sizeBytes = 100,
            lastModifiedMs = 1
        )
        val metadata = audio.copy(
            name = "$audioName.npmeta.json",
            reference = metadataUri.toString(),
            mediaUri = metadataUri.toString()
        )
        val song = SongItem(
            id = 339L,
            name = "Edited lyrics",
            artist = "Artist",
            album = "Local",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = audioUri.toString(),
            matchedRomanizedLyric = "[00:01.00]new romanized lyric",
            localFileName = audioName
        )
        try {
            targetContext.contentResolver.call(
                providerUri, Issue339LyricsTestDocumentProvider.CREATE_EMPTY_METADATA, null, null
            )
            targetContext.contentResolver.call(
                providerUri, Issue339LyricsTestDocumentProvider.CLEAR_LYRICS, null, null
            )
            ManagedDownloadStorage.primeSettings(treeUri.toString(), "Issue 339")
            ManagedDownloadStorage.snapshotCacheStore.putSnapshot(
                context = targetContext,
                cacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(targetContext),
                snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
                    audioEntries = listOf(audio),
                    audioEntriesByLookupKey = mapOf(audio.reference to audio),
                    metadataEntriesByAudioName = mapOf(audioName to metadata)
                )
            )
            val navigation = LocalMediaSupport.resolveLocalDocumentNavigation(
                targetContext, audioUri
            )!!
            val parentId = requireNotNull(navigation.parentDocumentId)
            LocalMediaSupport.queryDocumentChildrenForMutation(
                targetContext, navigation.treeUri ?: navigation.baseUri,
                parentId
            )!!
            val cacheKey = LocalMediaSupport.documentParentCacheKey(
                navigation.treeUri ?: navigation.baseUri,
                parentId
            )
            synchronized(LocalMediaSupport.documentChildrenCache) {
                val cached = LocalMediaSupport.documentChildrenCache[cacheKey]!!
                LocalMediaSupport.documentChildrenCache[cacheKey] = cached.copy(cachedAtMs = 0L)
            }
            val before = musicChildQueryCount()

            val outcome = LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )

            assertEquals(LocalMediaMetadataWriteOutcome.SIDECAR_ONLY, outcome)
            assertEquals(before, musicChildQueryCount())
            assertEquals(
                "[00:01.00]new romanized lyric",
                LocalMediaSupport.inspect(targetContext, audioUri).romanizedLyricContent
            )

            val romanizedUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, Issue339LyricsTestDocumentProvider.ROMANIZED_ID
            )
            val romanized = audio.copy(
                name = Issue339LyricsTestDocumentProvider.ROMANIZED_NAME,
                reference = romanizedUri.toString(),
                mediaUri = romanizedUri.toString()
            )
            ManagedDownloadStorage.snapshotCacheStore.putSnapshot(
                context = targetContext,
                cacheKey = ManagedDownloadStorage.currentSnapshotCacheKey(targetContext),
                snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
                    audioEntries = listOf(audio),
                    audioEntriesByLookupKey = mapOf(audio.reference to audio),
                    metadataEntriesByAudioName = mapOf(audioName to metadata),
                    lyricEntriesByName = mapOf(romanized.name to romanized)
                )
            )
            LocalMediaSupport.invalidateDocumentChildrenCache(
                navigation.treeUri ?: navigation.baseUri, parentId
            )
            val beforeIndexed = musicChildQueryCount()
            val translatedSong = song.copy(
                matchedTranslatedLyric = "[00:01.00]new translated lyric"
            )

            assertEquals(
                LocalMediaMetadataWriteOutcome.SIDECAR_ONLY,
                LocalMediaSupport.writeEditableMetadata(
                    context = targetContext,
                    song = translatedSong,
                    writeCover = false,
                    writeLyrics = true
                )
            )
            assertEquals(beforeIndexed, musicChildQueryCount())
            assertEquals(
                "[00:01.00]new translated lyric",
                LocalMediaSupport.inspect(targetContext, audioUri).translatedLyricContent
            )
        } finally {
            ManagedDownloadStorage.primeSettings(previousDirectoryUri, null)
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
            val outcome = LocalMediaSupport.writeEditableMetadata(
                context = targetContext,
                song = song,
                writeCover = false,
                writeLyrics = true
            )
            assertEquals(LocalMediaMetadataWriteOutcome.SIDECAR_ONLY, outcome)
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
            assertTrue(outcomes.all { it == LocalMediaMetadataWriteOutcome.SIDECAR_ONLY })
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
                coverReference = null,
                writeCover = true,
                writeLyrics = true
            )

            assertEquals(LocalMediaMetadataWriteOutcome.SIDECAR_ONLY, outcome)
            assertEquals(0, metadataCreateCount())
            assertEquals(0, lyricsDirectoryCreateCount())
            assertEquals(1, musicChildQueryCount())
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

    private fun musicChildQueryCount(): Int {
        return targetContext.contentResolver.call(
            providerUri,
            Issue339LyricsTestDocumentProvider.QUERY_MUSIC_CHILD_COUNT,
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
