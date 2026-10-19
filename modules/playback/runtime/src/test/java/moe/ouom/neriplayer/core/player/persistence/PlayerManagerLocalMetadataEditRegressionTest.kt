package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.StructStat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileDescriptor
import java.net.URI
import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PlayerManagerLocalMetadataEditRegressionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val manager = PlayerManager
    private val previousQueue = manager.currentQueueSnapshot()
    private val previousCurrentSong = manager._currentSongFlow.value
    private val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
    private val previousApplication = applicationField.get(null)
    private val registry = PlayerDependencies::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val environmentField = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
    private val previousEnvironment = environmentField.get(registry)
    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access").apply { isAccessible = true }
    private val previousMediaAccess = mediaAccessField.get(null)
    private val repositoryField = LocalPlaylistRepository::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val previousRepository = repositoryField.get(null)
    private val persistenceGenerationField = manager.statePersistenceCoordinator.javaClass
        .getDeclaredField("generation").apply { isAccessible = true }
    private val previousPersistenceGeneration = persistenceGenerationField.get(manager.statePersistenceCoordinator)
    private lateinit var application: Application
    private var lyricProbeFailure: RuntimeException? = null
    private var lyricProbeCount = 0

    @Before
    fun setUp() {
        application = spy(ApplicationProvider.getApplicationContext<Application>())
        doReturn(temporaryFolder.root).`when`(application).filesDir
        val repositories = mock(PlayerRepositoryDependencies::class.java)
        `when`(repositories.playHistoryRepo).thenReturn(mock(PlayHistoryRepository::class.java))
        `when`(repositories.playlistUsageRepo).thenReturn(mock(PlaylistUsageRepository::class.java))
        val downloads = mock(PlayerDownloadAccess::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolvePermittedLocalPlaybackUri" -> invocation.getArgument<String?>(2)
                "downloadedSongs" -> emptyList<SongItem>()
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        environmentField.set(registry, null)
        PlayerDependencies.install(PlayerEnvironment(
            application, repositories, downloads,
            mock(PlayerListenTogetherAccess::class.java), mock(PlayerPresentationHost::class.java),
            { false }, { Job().apply { complete() } }
        ))
        val mediaDownloads = mock(LocalMediaDownloadAccess::class.java, Answer { invocation ->
            if (invocation.method.name == "resolveManagedAudioDisplayName") {
                lyricProbeCount++
                lyricProbeFailure?.let { throw it }
            }
            Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        LocalMediaHostAccess.bind(mediaDownloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
        val repository = mock(LocalPlaylistRepository::class.java, Answer { invocation ->
            if (invocation.method.name == "nextUserLyricSyncRevision") 1L
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(repository.playlists).thenReturn(MutableStateFlow(emptyList<LocalPlaylist>()))
        repositoryField.set(null, repository)
        manager.application = application
        manager._currentSongFlow.value = null
        persistenceGenerationField.set(manager.statePersistenceCoordinator, null)
        LocalMediaSupport.clearLyricsLookupCache()
    }

    @After
    fun tearDown() {
        LocalMediaSupport.clearLyricsLookupCache()
        manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
        manager._currentSongFlow.value = previousCurrentSong
        applicationField.set(null, previousApplication)
        repositoryField.set(null, previousRepository)
        environmentField.set(registry, previousEnvironment)
        mediaAccessField.set(null, previousMediaAccess)
        persistenceGenerationField.set(manager.statePersistenceCoordinator, previousPersistenceGeneration)
    }

    @Test
    fun `later cover replacement preserves a no-artwork baseline loaded from metadata`() = runTest {
        val audio = temporaryFolder.newFile("song.mp3").apply { writeText("audio") }
        val editedCover = cover("edited.png")
        val newCover = cover("new.png")
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("originalCoverUrl", "")
            .put("coverPath", editedCover.toURI().toString())
            .toString())
        val song = localSong(audio.path).copy(
            coverUrl = editedCover.toURI().toString(),
            customCoverUrl = editedCover.toURI().toString(),
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)

        assertTrue(manager.updateSongCustomInfoImpl(
            originalSong = song,
            customCoverUrl = newCover.toURI().toString(),
            customName = null,
            customArtist = null
        ))

        assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
        assertEquals("", JSONObject(File(audio.path + ".npmeta.json").readText()).getString("originalCoverUrl"))
    }

    @Test
    fun `legacy metadata lyric fallback does not become an original after reimport`() = runTest {
        val audio = temporaryFolder.newFile("legacy.mp3").apply { writeText("audio") }
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("matchedLyric", "edited lyric")
            .put("matchedTranslatedLyric", "edited translation")
            .put("matchedRomanizedLyric", "edited romanization")
            .toString())
        val song = LocalAudioImportManager.hydrateLocalSongTextMetadata(
            context = application,
            song = localSong(audio.path),
            resolveCoverFallback = false,
            includeEmbeddedFallback = false,
            includeEmbeddedCoverFallback = false,
            resolveDurationFallback = false
        )
        val scanned = LocalMediaSupport.inspectLyricsFast(song, includeStoredFallback = false)
        assertEquals("edited lyric", scanned.lyric)
        assertEquals("edited translation", scanned.translatedLyric)
        assertEquals("edited romanization", scanned.romanizedLyric)

        val prepared = manager.preserveLocalSongMetadataOriginals(song)

        assertNull(prepared.originalLyric)
        assertNull(prepared.originalTranslatedLyric)
        assertNull(prepared.originalRomanizedLyric)
    }

    @Test
    fun `real local lyric sidecar remains the original when legacy metadata contains an override`() = runTest {
        val audio = temporaryFolder.newFile("sidecar.mp3").apply { writeText("audio") }
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("matchedLyric", "edited metadata lyric")
            .toString())
        File(audio.parentFile, "sidecar.lrc").writeText("[00:01.00]local original lyric")

        val prepared = manager.preserveLocalSongMetadataOriginals(localSong(audio.path))

        assertEquals("[00:01.00]local original lyric", prepared.originalLyric)
    }

    @Test
    fun `editing a reimported legacy override keeps unresolved originals unknown`() = runTest {
        ShadowLog.clear()
        val audio = temporaryFolder.newFile("continuous.mp3").apply { writeText("audio") }
        val metadata = File(audio.path + ".npmeta.json").apply {
            writeText(JSONObject()
                .put("matchedLyric", "first edit")
                .put("matchedTranslatedLyric", "first translation")
                .put("matchedRomanizedLyric", "first romanization")
                .toString())
        }
        val song = LocalAudioImportManager.hydrateLocalSongTextMetadata(
            context = application,
            song = localSong(audio.path),
            resolveCoverFallback = false,
            includeEmbeddedFallback = false,
            includeEmbeddedCoverFallback = false,
            resolveDurationFallback = false
        )
        manager.publishCurrentQueue(listOf(song), 0)
        val previousIoScope = manager.ioScope
        manager.ioScope = backgroundScope
        try {
            val applied = withRegularFileDescriptors {
                manager.updateSongLyricsAndTranslationImpl(
                    songToUpdate = song,
                    newLyrics = "second edit",
                    newTranslatedLyrics = "second translation",
                    newRomanizedLyrics = "second romanization"
                )
            }
            assertTrue(ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg}" }, applied)

            val updated = manager.currentPlaylist.single()
            assertNull(updated.originalLyric)
            assertNull(updated.originalTranslatedLyric)
            assertNull(updated.originalRomanizedLyric)
            val stored = JSONObject(metadata.readText())
            assertTrue(stored.isNull("originalLyric"))
            assertTrue(stored.isNull("originalTranslatedLyric"))
            assertTrue(stored.isNull("originalRomanizedLyric"))
        } finally {
            manager.ioScope = previousIoScope
        }
    }

    @Test
    fun `bulk embedding does not capture unresolved legacy metadata overrides`() = runTest {
        val audio = temporaryFolder.newFile("bulk-legacy.mp3").apply { writeText("audio") }
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("matchedLyric", "legacy edit")
            .put("matchedTranslatedLyric", "legacy translation")
            .put("matchedRomanizedLyric", "legacy romanization")
            .toString())
        val song = localSong(audio.path).copy(
            matchedLyric = "legacy edit", matchedTranslatedLyric = "legacy translation",
            matchedRomanizedLyric = "legacy romanization"
        )
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite { writes ->
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS,
                manager.embedLocalSongAssetsImpl(song, replacementLyrics()))

            for (updated in listOf(writes.first(), manager.currentPlaylist.single())) {
                assertNull(updated.originalLyric)
                assertNull(updated.originalTranslatedLyric)
                assertNull(updated.originalRomanizedLyric)
            }
        }
    }

    @Test
    fun `bulk embedding captures real sidecar lyrics before the write replaces them`() = runTest {
        val audio = temporaryFolder.newFile("bulk-sidecar.mp3").apply { writeText("audio") }
        val lyric = File(audio.parentFile, "bulk-sidecar.lrc").apply {
            writeText("[00:01.00]original local lyric")
        }
        val song = localSong(audio.path).copy(matchedLyric = "cached edit")
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite(afterFirstWrite = { lyric.writeText("replacement lyric") }) { writes ->
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS,
                manager.embedLocalSongAssetsImpl(song, replacementLyrics()))

            assertEquals("[00:01.00]original local lyric", writes.first().originalLyric)
            assertEquals("[00:01.00]original local lyric", manager.currentPlaylist.single().originalLyric)
        }
    }

    @Test
    fun `bulk embedding preserves known empty and nonempty original lyric baselines`() = runTest {
        for ((index, original) in listOf("", "known original").withIndex()) {
            val audio = temporaryFolder.newFile("bulk-known-$index.mp3").apply { writeText("audio") }
            val song = localSong(audio.path).copy(
                matchedLyric = "previous edit", matchedTranslatedLyric = "previous translation",
                matchedRomanizedLyric = "previous romanization", originalLyric = original,
                originalTranslatedLyric = original, originalRomanizedLyric = original
            )
            manager.publishCurrentQueue(listOf(song), 0)

            withSuccessfulAssetWrite { writes ->
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS,
                    manager.embedLocalSongAssetsImpl(song, replacementLyrics()))

                for (updated in listOf(writes.first(), manager.currentPlaylist.single())) {
                    assertEquals(original, updated.originalLyric)
                    assertEquals(original, updated.originalTranslatedLyric)
                    assertEquals(original, updated.originalRomanizedLyric)
                }
            }
        }
    }

    @Test
    fun `bulk embedding keeps original baselines determined concurrently with its write`() = runTest {
        val audio = temporaryFolder.newFile("bulk-concurrent.mp3").apply { writeText("audio") }
        File(audio.parentFile, "bulk-concurrent.lrc").writeText("[00:01.00]initial source")
        val song = localSong(audio.path)
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite(afterFirstWrite = {
            manager.publishCurrentQueue(listOf(song.copy(
                originalLyric = "newer original", originalTranslatedLyric = "newer translation",
                originalRomanizedLyric = "newer romanization"
            )), 0)
        }) { writes ->
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS,
                manager.embedLocalSongAssetsImpl(song, replacementLyrics()))

            assertEquals(2, writes.size)
            val updated = manager.currentPlaylist.single()
            assertEquals("newer original", updated.originalLyric)
            assertEquals("newer translation", updated.originalTranslatedLyric)
            assertEquals("newer romanization", updated.originalRomanizedLyric)
        }
    }

    @Test
    fun `bulk cover embedding preserves a no-artwork baseline stored in metadata`() = runTest {
        val audio = temporaryFolder.newFile("bulk-empty-cover.mp3").apply { writeText("audio") }
        val editedCover = cover("bulk-edited.png", Color.BLUE)
        val newCover = cover("bulk-new.png", Color.GREEN)
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("originalCoverUrl", "")
            .put("coverPath", editedCover.toURI().toString())
            .toString())
        val song = localSong(audio.path).copy(
            coverUrl = editedCover.toURI().toString(), customCoverUrl = editedCover.toURI().toString(),
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite { writes ->
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.embedLocalSongAssetsImpl(
                song, LocalSongAssetEmbedding(coverReference = newCover.toURI().toString())
            ))

            assertEquals("", writes.first().originalCoverUrl)
            assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
        }
    }

    @Test
    fun `bulk cover embedding retains an existing original instead of backing up the edited cover`() = runTest {
        val audio = temporaryFolder.newFile("bulk-known-cover.mp3").apply { writeText("audio") }
        val originalCover = cover("bulk-original.png", Color.RED)
        val editedCover = cover("bulk-current.png", Color.BLUE)
        val newCover = cover("bulk-replacement.png", Color.GREEN)
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("coverPath", editedCover.toURI().toString()).toString())
        val song = localSong(audio.path).copy(
            coverUrl = editedCover.toURI().toString(), customCoverUrl = editedCover.toURI().toString(),
            originalCoverUrl = originalCover.toURI().toString(),
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite {
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.embedLocalSongAssetsImpl(
                song, LocalSongAssetEmbedding(coverReference = newCover.toURI().toString())
            ))

            val restored = File(URI(requireNotNull(manager.currentPlaylist.single().originalCoverUrl)))
            assertArrayEquals(originalCover.readBytes(), restored.readBytes())
        }
    }

    @Test
    fun `revoked lyric read permission leaves best effort metadata preparation available`() = runTest {
        lyricProbeFailure = SecurityException("local permission revoked")
        val song = localSong("content://unavailable.local/audio/7")

        val prepared = manager.preserveLocalSongMetadataOriginals(song)

        assertTrue(lyricProbeCount > 0)
        assertEquals(song, prepared)
    }

    @Test
    fun `cancellation during local lyric inspection is propagated`() = runTest {
        val cancelled = CancellationException("cancelled local inspection")
        lyricProbeFailure = cancelled

        val failure = runCatching {
            manager.preserveLocalSongMetadataOriginals(localSong("content://unavailable.local/audio/8"))
        }.exceptionOrNull()

        assertTrue(lyricProbeCount > 0)
        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cancelled })
    }

    private fun localSong(reference: String) = SongItem(
        7L, "song", "artist", "__local_files__", 0L, 1000L, null,
        mediaUri = reference,
        localFilePath = reference.takeIf { it.startsWith('/') },
        localFileName = "song.mp3"
    )

    private fun replacementLyrics() = LocalSongAssetEmbedding(
        lyrics = "replacement lyric", translatedLyrics = "replacement translation",
        romanizedLyrics = "replacement romanization"
    )

    private suspend fun <T> withRegularFileDescriptors(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        // Robolectric 的 fstat 丢失路径，按真实打开文件补回类型和身份
        val files = IdentityHashMap<FileDescriptor, File>()
        mockStatic(ParcelFileDescriptor::class.java, CALLS_REAL_METHODS).use { descriptors ->
            descriptors.`when`<ParcelFileDescriptor> {
                ParcelFileDescriptor.open(any(File::class.java), anyInt())
            }.thenAnswer { invocation ->
                (invocation.callRealMethod() as ParcelFileDescriptor).also { descriptor ->
                    files[descriptor.fileDescriptor] = invocation.getArgument(0)
                }
            }
            mockStatic(Os::class.java, CALLS_REAL_METHODS).use { os ->
                os.`when`<StructStat> { Os.fstat(any()) }.thenAnswer { invocation ->
                    Os.stat(files.getValue(invocation.getArgument(0)).absolutePath)
                }
                block()
            }
        }
    }

    private suspend fun withSuccessfulAssetWrite(
        afterFirstWrite: ((SongItem) -> Unit)? = null,
        block: suspend (List<SongItem>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val writes = mutableListOf<SongItem>()
        val facade = Class.forName("moe.ouom.neriplayer.core.player.persistence.PlayerManagerPersistenceExtensionsKt")
        mockStatic(facade, Answer { invocation ->
            if (invocation.method.name == "writeLocalEditableMetadata") {
                val target = invocation.getArgument<SongItem>(1)
                writes += target
                if (writes.size == 1) afterFirstWrite?.invoke(target)
                LocalMediaMetadataWriteOutcome.SUCCESS
            } else {
                invocation.callRealMethod()
            }
        }).use { block(writes) }
    }

    private fun cover(name: String, color: Int = Color.BLACK): File = temporaryFolder.newFile(name).also { file ->
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
