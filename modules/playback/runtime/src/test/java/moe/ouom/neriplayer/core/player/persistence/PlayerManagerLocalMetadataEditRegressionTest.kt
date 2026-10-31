package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.content.ContentResolver
import android.content.UriPermission
import android.content.res.AssetFileDescriptor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.StructStat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyOptions
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyStage
import moe.ouom.neriplayer.core.player.metadata.ExternalBluetoothLyricPayload
import moe.ouom.neriplayer.core.player.service.notification.serviceNotificationTitle
import moe.ouom.neriplayer.core.player.service.presentation.serviceMetadataText
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.curation.LocalCoverBaselineInspector
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.search.api.client.CloudMusicSearchApi
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
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails
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
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

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
    private lateinit var repositories: PlayerRepositoryDependencies
    private var lyricProbeFailure: RuntimeException? = null
    private var lyricProbeCount = 0
    private var denyLocalPlayback = false
    private var managedDownloadCached = false
    private var editSourceContext: ResolvedLocalMediaSource? = null

    @Before
    fun setUp() {
        application = spy(ApplicationProvider.getApplicationContext<Application>())
        doReturn(temporaryFolder.root).`when`(application).filesDir
        repositories = mock(PlayerRepositoryDependencies::class.java)
        `when`(repositories.playHistoryRepo).thenReturn(mock(PlayHistoryRepository::class.java))
        `when`(repositories.playlistUsageRepo).thenReturn(mock(PlaylistUsageRepository::class.java))
        val downloads = mock(PlayerDownloadAccess::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "resolvePermittedLocalPlaybackUri" -> {
                    editSourceContext = invocation.getArgument(3)
                    if (denyLocalPlayback) null else invocation.getArgument<String?>(2)
                }
                "downloadedSongs" -> emptyList<SongItem>()
                "hasDownloadedSongCached" -> managedDownloadCached
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
    fun `local metadata editing uses a readable source instead of an inaccessible media store reference`() = runTest {
        val audio = temporaryFolder.newFile("editable-source.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(mediaUri = "content://media/external/audio/media/7")

        val writable = manager.resolveLocalSidecarWriteSong(song)

        assertEquals(audio.absolutePath, writable?.mediaUri)
        assertEquals(audio.absolutePath, writable?.localFilePath)
        assertEquals(song.id, writable?.id)
        assertEquals(song.localFileName, writable?.localFileName)
    }

    @Test
    fun `local metadata editing keeps the completion gate for a recovered source`() = runTest {
        val audio = temporaryFolder.newFile("blocked-editable-source.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(mediaUri = "content://media/external/audio/media/7")
        denyLocalPlayback = true

        assertNull(manager.resolveLocalSidecarWriteSong(song))
    }

    @Test
    fun `local metadata editing carries verified folder provenance through the download host`() = runTest {
        val original = "content://media/external/audio/media/7"
        val song = localSong("/storage/emulated/0/Music/Album/song.mp3").copy(
            mediaUri = original, channelId = "netease", audioId = "7", sourceStableKey = "7|netease|"
        )
        val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/Album/song.mp3")
        val resolver = mock(ContentResolver::class.java)
        val permission = mock(UriPermission::class.java)
        doReturn(resolver).`when`(application).contentResolver
        doReturn(listOf(permission)).`when`(resolver).persistedUriPermissions
        doReturn(tree).`when`(permission).uri
        doReturn(true).`when`(permission).isReadPermission
        doReturn(mock(AssetFileDescriptor::class.java)).`when`(resolver).openAssetFileDescriptor(document, "r")
        doAnswer {
            MatrixCursor(arrayOf("_data")).apply { addRow(arrayOf(song.localFilePath)) }
        }.`when`(resolver).query(eq(Uri.parse(original)), any(), any(), any(), any())

        val writable = manager.resolveLocalSidecarWriteSong(song)

        assertEquals(document.toString(), writable?.mediaUri)
        val sourceContext = requireNotNull(editSourceContext)
        assertEquals(document.toString(), sourceContext.reference)
        assertEquals(original, sourceContext.anchorFor(song, sourceContext.reference))
        assertNull(sourceContext.anchorFor(song.copy(id = 8L), sourceContext.reference))
    }

    @Test
    fun `unchanged app-only title and artist edits still reach an explicitly requested file write`() = runTest {
        val audio = temporaryFolder.newFile("retry-title.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)
        withRegularFileDescriptors {
            assertTrue(manager.updateSongCustomInfoImpl(song, null, "Edited song", "Edited artist"))
        }
        val edited = manager.currentPlaylist.single()

        withSuccessfulAssetWrite { writes ->
            repeat(2) {
                assertTrue(manager.updateSongCustomInfoImpl(
                    edited, null, "Edited song", "Edited artist", writeLocalMetadata = true
                ))
            }

            assertEquals(2, writes.size)
            writes.forEach { written ->
                assertEquals("Edited song", written.customName)
                assertEquals("Edited artist", written.customArtist)
                assertEquals("song", written.originalName)
                assertEquals("artist", written.originalArtist)
            }
        }
    }

    @Test
    fun `title and artist edits do not inspect untouched local lyrics`() = runTest {
        val audio = temporaryFolder.newFile("title-without-lyric-probe.mp3").apply { writeText("audio") }
        val song = localSong(audio.path)
        manager.publishCurrentQueue(listOf(song), 0)

        withRegularFileDescriptors {
            assertTrue(manager.updateSongCustomInfoImpl(song, null, "Edited title", "Edited artist"))
        }

        assertEquals(0, lyricProbeCount)
        assertNull(manager.currentPlaylist.single().originalLyric)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first restore stays visible after an older local playback hydration finishes`() = runTest {
        val playbackStats = mock(PlaybackStatsRepository::class.java)
        `when`(playbackStats.statsClearedAtFlow).thenReturn(MutableStateFlow(0L))
        `when`(repositories.playbackStatsRepo).thenReturn(playbackStats)
        val audio = temporaryFolder.newFile("late-playback-hydration.mp3").apply { writeText("audio") }
        val originalCover = cover("original-playback.png", Color.RED).toURI().toString()
        val editedCover = cover("edited-playback.png", Color.BLUE).toURI().toString()
        val originalLyric = "[00:01.00]original lyric"
        val song = localSong(audio.path).copy(
            name = "Original title", artist = "Original artist", coverUrl = originalCover,
            customName = "Edited title", customArtist = "Edited artist", customCoverUrl = editedCover,
            originalName = "Original title", originalArtist = "Original artist", originalCoverUrl = originalCover,
            matchedLyric = "[00:01.00]edited lyric", matchedTranslatedLyric = "edited translation",
            matchedRomanizedLyric = "edited romanization", originalLyric = originalLyric,
            originalTranslatedLyric = "original translation", originalRomanizedLyric = "original romanization",
            lyricSyncEdited = true
        )
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("name", song.name).put("artist", song.artist).put("coverUrl", originalCover)
            .put("customName", song.customName).put("customArtist", song.customArtist)
            .put("customCoverUrl", editedCover).put("coverPath", editedCover)
            .put("originalName", song.originalName).put("originalArtist", song.originalArtist)
            .put("originalCoverUrl", originalCover).put("originalLyric", originalLyric)
            .put("originalTranslatedLyric", song.originalTranslatedLyric)
            .put("originalRomanizedLyric", song.originalRomanizedLyric)
            .put("matchedLyric", song.matchedLyric).put("matchedTranslatedLyric", song.matchedTranslatedLyric)
            .put("matchedRomanizedLyric", song.matchedRomanizedLyric).put("sourceModifiedAtMs", 123L)
            .toString())
        manager.publishCurrentQueue(listOf(song), 0)
        manager._currentSongFlow.value = song
        managedDownloadCached = true
        val main = HeldPlaybackMainDispatcher()
        val hydrationLifetime = SupervisorJob()
        val previousIoScope = manager.ioScope
        manager.ioScope = CoroutineScope(hydrationLifetime + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            main.publicationQueued.completeExceptionally(failure)
        })
        Dispatchers.setMain(main)
        try {
            val playback = Class.forName("moe.ouom.neriplayer.core.player.playback.PlayerManagerPlaybackExtensionsKt")
            playback.getDeclaredMethod(
                "maybeHydrateSongForPlayback", PlayerManager::class.java,
                Int::class.javaPrimitiveType, SongItem::class.java, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(null, manager, 0, song, manager.playbackRequestToken)
            main.publicationQueued.await()
            val hydration = hydrationLifetime.children.single()

            withRegularFileDescriptors {
                assertTrue(manager.updateSongLyricsAndTranslationImpl(
                    song, originalLyric, song.originalTranslatedLyric, song.originalRomanizedLyric,
                    persistLocalSidecars = true, syncDownloadedMetadata = false
                ))
                assertTrue(manager.updateSongCustomInfoImpl(
                    manager.currentPlaylist.single(), originalCover, song.originalName, song.originalArtist,
                    restoreBaseCover = true, restoreBaseName = true, restoreBaseArtist = true,
                    restoreBaseLyrics = true, clearMatchedMetadata = true
                ))
            }
            val expected = listOf(
                "Original title", "Original artist", originalCover, originalLyric,
                "original translation", "original romanization", "Original title", "Original artist"
            )
            assertEquals(expected, playbackDisplayFields(requireNotNull(manager._currentSongFlow.value)))
            assertEquals("Original title", LocalMediaSupport.readLocalMetadataSidecarFast(
                application, manager.currentPlaylist.single()
            )?.name)

            main.release()
            hydration.join()

            assertEquals(expected, playbackDisplayFields(requireNotNull(manager._currentSongFlow.value)))
            assertEquals(expected, playbackDisplayFields(manager.currentPlaylist.single()))
        } finally {
            hydrationLifetime.cancel()
            main.release()
            hydrationLifetime.join()
            manager.ioScope = previousIoScope
            Dispatchers.resetMain()
        }
    }

    private fun playbackDisplayFields(song: SongItem): List<String?> = listOf(
        song.displayName(), song.displayArtist(), song.displayCoverUrl(), song.matchedLyric,
        song.matchedTranslatedLyric, song.matchedRomanizedLyric, serviceNotificationTitle(song),
        serviceMetadataText(song, ExternalBluetoothLyricPayload(), null, false).artist
    )

    private class HeldPlaybackMainDispatcher : CoroutineDispatcher() {
        val publicationQueued = CompletableDeferred<Unit>()
        private val pending = ConcurrentLinkedQueue<Runnable>()
        @Volatile private var released = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (released) {
                block.run()
                return
            }
            pending.add(block)
            publicationQueued.complete(Unit)
        }

        fun release() {
            released = true
            while (true) (pending.poll() ?: return).run()
        }
    }

    @Test
    fun `local playback hydration still publishes untouched metadata`() = runTest {
        val song = songAwaitingPlaybackHydration("untouched")
        withHeldLocalPlaybackHydration(song) { main, hydration ->
            main.release()
            hydration.join()

            assertEquals("Hydrated title", manager._currentSongFlow.value?.displayName())
            assertEquals("Hydrated artist", manager.currentPlaylist.single().displayArtist())
            assertEquals(1, metadataRepositoryWrites())
        }
    }

    @Test
    fun `local playback hydration skips a superseded playback request`() = runTest {
        val song = songAwaitingPlaybackHydration("superseded")
        val previousToken = manager.playbackRequestToken
        try {
            withHeldLocalPlaybackHydration(song) { main, hydration ->
                manager.playbackRequestToken++
                main.release()
                hydration.join()

                assertEquals(song, manager._currentSongFlow.value)
                assertEquals(song, manager.currentPlaylist.single())
                assertEquals(0, metadataRepositoryWrites())
            }
        } finally {
            manager.playbackRequestToken = previousToken
        }
    }

    @Test
    fun `same value explicit file write invalidates an older local playback hydration`() = runTest {
        val song = songAwaitingPlaybackHydration("same-value")
        withHeldLocalPlaybackHydration(song) { main, hydration ->
            withSuccessfulAssetWrite {
                assertTrue(manager.updateSongCustomInfoImpl(song, null, null, null, writeLocalMetadata = true))
            }
            assertEquals(song, manager.currentPlaylist.single())
            assertTrue(song !== manager.currentPlaylist.single())
            main.release()
            hydration.join()

            assertEquals(song, manager._currentSongFlow.value)
            assertEquals(song, manager.currentPlaylist.single())
            assertEquals(1, metadataRepositoryWrites())
        }
    }

    @Test
    fun `queued external metadata hydration cannot overwrite a later restored playback song`() = runTest {
        val audio = temporaryFolder.newFile("external-stale.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            customName = "Edited title", originalName = "song", originalArtist = "artist",
            originalCoverUrl = "", originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        withHeldExternalHydration(song, song.copy(name = "Stale title", customName = "Edited title")) { pending, hydration ->
            withRegularFileDescriptors {
                assertTrue(manager.updateSongCustomInfoImpl(
                    song, null, "song", "artist", restoreBaseName = true, restoreBaseArtist = true
                ))
            }
            val restored = manager.currentPlaylist.single()
            assertEquals("song", restored.displayName())
            pending.release()
            hydration.join()

            assertEquals(restored, manager._currentSongFlow.value)
            assertEquals(restored, manager.currentPlaylist.single())
            assertEquals(1, metadataRepositoryWrites())
        }
    }

    @Test
    fun `queued external metadata hydration accepts an unchanged equal playback snapshot`() = runTest {
        val song = localSong("/fixture/external.mp3")
        val hydrated = song.copy(name = "Hydrated external")
        withHeldExternalHydration(song, hydrated, requestSong = song.copy()) { pending, hydration ->
            pending.release()
            hydration.join()

            assertEquals(hydrated, manager._currentSongFlow.value)
            assertEquals(hydrated, manager.currentPlaylist.single())
            assertEquals(1, metadataRepositoryWrites())
        }
    }

    @Test
    fun `external metadata hydration still persists a song outside the playback queue`() = runTest {
        val song = localSong("/fixture/off-queue.mp3")
        val hydrated = song.copy(name = "Hydrated catalog title")
        withHeldExternalHydration(song, hydrated, inPlayback = false) { pending, hydration ->
            pending.release()
            hydration.join()

            assertTrue(manager.currentPlaylist.isEmpty())
            assertNull(manager._currentSongFlow.value)
            val update = mockingDetails(repositoryField.get(null)).invocations
                .single { it.method.name == "updateSongMetadata" }
            assertEquals(listOf(song, hydrated), update.arguments.take(2))
        }
    }

    private fun songAwaitingPlaybackHydration(fileName: String): SongItem {
        val audio = temporaryFolder.newFile("$fileName.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            originalName = "song", originalArtist = "artist", originalCoverUrl = "",
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        File(audio.path + ".npmeta.json").writeText(JSONObject()
            .put("name", "Hydrated title").put("artist", "Hydrated artist")
            .put("originalName", "song").put("originalArtist", "artist")
            .put("originalCoverUrl", "").put("originalLyric", "")
            .put("originalTranslatedLyric", "").put("originalRomanizedLyric", "")
            .put("sourceModifiedAtMs", 123L).toString())
        return song
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun withHeldLocalPlaybackHydration(
        song: SongItem,
        block: suspend (HeldPlaybackMainDispatcher, Job) -> Unit
    ) {
        configurePlaybackStats()
        manager.publishCurrentQueue(listOf(song), 0)
        manager._currentSongFlow.value = song
        managedDownloadCached = true
        val main = HeldPlaybackMainDispatcher()
        val lifetime = SupervisorJob()
        val previousScope = manager.ioScope
        manager.ioScope = CoroutineScope(lifetime + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            main.publicationQueued.completeExceptionally(failure)
        })
        Dispatchers.setMain(main)
        try {
            Class.forName("moe.ouom.neriplayer.core.player.playback.PlayerManagerPlaybackExtensionsKt")
                .getDeclaredMethod("maybeHydrateSongForPlayback", PlayerManager::class.java,
                    Int::class.javaPrimitiveType, SongItem::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(null, manager, 0, song, manager.playbackRequestToken)
            main.publicationQueued.await()
            block(main, lifetime.children.single())
        } finally {
            lifetime.cancel()
            main.release()
            lifetime.join()
            manager.ioScope = previousScope
            Dispatchers.resetMain()
        }
    }

    private suspend fun withHeldExternalHydration(
        song: SongItem,
        hydrated: SongItem,
        requestSong: SongItem = song,
        inPlayback: Boolean = true,
        block: suspend (HeldPlaybackMainDispatcher, Job) -> Unit
    ) {
        configurePlaybackStats()
        manager.publishCurrentQueue(if (inPlayback) listOf(song) else emptyList(), if (inPlayback) 0 else -1)
        manager._currentSongFlow.value = song.takeIf { inPlayback }
        val dispatcher = HeldPlaybackMainDispatcher()
        val lifetime = SupervisorJob()
        val previousScope = manager.ioScope
        manager.ioScope = CoroutineScope(lifetime + dispatcher)
        try {
            manager.hydrateSongMetadataImpl(requestSong, hydrated)
            block(dispatcher, lifetime.children.single())
        } finally {
            lifetime.cancel()
            dispatcher.release()
            lifetime.join()
            manager.ioScope = previousScope
        }
    }

    private fun configurePlaybackStats() {
        val stats = mock(PlaybackStatsRepository::class.java)
        `when`(stats.statsClearedAtFlow).thenReturn(MutableStateFlow(0L))
        `when`(repositories.playbackStatsRepo).thenReturn(stats)
    }

    private fun metadataRepositoryWrites(): Int = mockingDetails(repositoryField.get(null)).invocations
        .count { it.method.name == "updateSongMetadata" }

    @Test
    fun `unchanged app-only cover is included in a later explicit file write without replacing its baseline`() = runTest {
        val audio = temporaryFolder.newFile("retry-cover.mp3").apply { writeText("audio") }
        val selectedCover = cover("retry-selected.png")
        val reference = selectedCover.toURI().toString()
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)
        withConfirmedCoverAbsence(true) {
            assertTrue(manager.updateSongCustomInfoImpl(song, reference, null, null))
        }
        val edited = manager.currentPlaylist.single()
        assertEquals("", edited.originalCoverUrl)
        val coverWrites = mutableListOf<Pair<String?, Boolean>>()

        withSuccessfulAssetWrite(onWrite = { _, coverReference, writeCover, _ ->
            coverWrites += coverReference to writeCover
        }) { writes ->
            assertTrue(manager.updateSongCustomInfoImpl(
                edited, reference, "Another title", null, writeLocalMetadata = true
            ))

            assertEquals(listOf(reference to true), coverWrites)
            assertEquals("", writes.single().originalCoverUrl)
            assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
        }
    }

    @Test
    fun `repeated explicit cover restoration writes the absent original cover again`() = runTest {
        val audio = temporaryFolder.newFile("retry-cover-restore.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            originalName = "song", originalArtist = "artist", originalCoverUrl = "",
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)
        val coverWrites = mutableListOf<Pair<String?, Boolean>>()

        withSuccessfulAssetWrite(onWrite = { _, coverReference, writeCover, _ ->
            coverWrites += coverReference to writeCover
        }) { writes ->
            repeat(2) {
                assertTrue(manager.updateSongCustomInfoImpl(
                    song, null, null, null, restoreBaseCover = true, writeLocalMetadata = true
                ))
            }

            assertEquals(2, writes.size)
            assertEquals(listOf(null to true, null to true), coverWrites)
            assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
        }
    }

    @Test
    fun `ordinary write after reopening an app-only no-artwork restoration clears the embedded cover`() = runTest {
        val audio = temporaryFolder.newFile("reopened-empty-cover.mp3").apply { writeText("audio") }
        val selectedCover = cover("reopened-selected.png").toURI().toString()
        val song = localSong(audio.path).copy(
            originalName = "song", originalArtist = "artist", originalCoverUrl = "",
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)
        withSuccessfulAssetWrite {
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.embedLocalSongAssetsImpl(
                song, LocalSongAssetEmbedding(coverReference = selectedCover)
            ))
        }
        withRegularFileDescriptors {
            assertTrue(manager.updateSongCustomInfoImpl(
                manager.currentPlaylist.single(), null, null, null, restoreBaseCover = true
            ))
        }
        val reopened = LocalAudioImportManager.hydrateLocalSongCoverMetadata(application, song)
        assertEquals("", reopened.originalCoverUrl)
        assertNull(reopened.customCoverUrl)
        assertNull(reopened.coverUrl)
        manager.publishCurrentQueue(listOf(reopened), 0)
        val coverWrites = mutableListOf<Pair<String?, Boolean>>()

        withSuccessfulAssetWrite(onWrite = { _, reference, writesCover, _ ->
            coverWrites += reference to writesCover
        }) { writes ->
            assertTrue(manager.updateSongCustomInfoImpl(
                reopened, null, null, null, writeLocalMetadata = true
            ))

            assertEquals(1, writes.size)
            assertEquals(listOf(null to true), coverWrites)
            assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
        }
    }

    @Test
    fun `ordinary explicit writes do not clear unknown artwork or an existing displayed base cover`() = runTest {
        val audio = temporaryFolder.newFile("unknown-or-visible-cover.mp3").apply { writeText("audio") }
        val existingCover = cover("existing-base.png").toURI().toString()
        for (song in listOf(
            localSong(audio.path),
            localSong(audio.path).copy(originalCoverUrl = "", coverUrl = existingCover)
        )) {
            manager.publishCurrentQueue(listOf(song), 0)
            val coverWrites = mutableListOf<Pair<String?, Boolean>>()
            withSuccessfulAssetWrite(onWrite = { _, reference, writesCover, _ ->
                coverWrites += reference to writesCover
            }) {
                assertTrue(manager.updateSongCustomInfoImpl(song, null, null, null, writeLocalMetadata = true))

                assertEquals(listOf(null to false), coverWrites)
            }
        }
    }

    @Test
    fun `explicit metadata writes recover unread local lyric baselines without inventing missing tracks`() = runTest {
        val audio = temporaryFolder.newFile("write-existing-lyrics.mp3").apply { writeText("audio") }
        val originalLyric = "[00:01.00]existing local lyric"
        File(audio.parentFile, "write-existing-lyrics.lrc").writeText(originalLyric)
        val song = localSong(audio.path)
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite { writes ->
            assertTrue(manager.updateSongCustomInfoImpl(
                song, null, "New title", null, writeLocalMetadata = true, writeLyrics = true
            ))

            assertEquals(originalLyric, writes.single().originalLyric)
            assertNull(writes.single().matchedLyric)
            assertNull(writes.single().originalTranslatedLyric)
            assertNull(writes.single().originalRomanizedLyric)
        }
    }

    @Test
    fun `local write wrapper preserves unknown physical lyric tracks without promoting them to originals`() = runTest {
        val audio = temporaryFolder.newFile("preserve-unknown-physical-lyrics.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            matchedLyric = "edited original", originalLyric = "first original", lyricSyncEdited = true
        )
        val inspected = LocalLyricsScanMetadata(
            lyric = "physical original", translatedLyric = "physical translation", romanizedLyric = null,
            embeddedSourceResolved = true
        )

        withCapturedPlatformWrite(inspected) { writes, inspectionCount ->
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.writeLocalEditableMetadata(
                song = song, writeCover = false, writeLyrics = true
            ))

            assertEquals(1, inspectionCount())
            assertEquals("edited original", writes.single().matchedLyric)
            assertEquals("physical translation", writes.single().matchedTranslatedLyric)
            assertEquals("", writes.single().matchedRomanizedLyric)
            assertEquals("first original", writes.single().originalLyric)
            assertNull(writes.single().originalTranslatedLyric)
            assertNull(writes.single().originalRomanizedLyric)
        }
    }

    @Test
    fun `local write wrapper refuses to clear unknown lyric tracks when their physical read fails`() = runTest {
        val audio = temporaryFolder.newFile("unreadable-physical-lyrics.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(matchedLyric = "edited original", lyricSyncEdited = true)

        for (inspection in listOf(null, LocalLyricsScanMetadata(null, null, null))) {
            withCapturedPlatformWrite(inspection) { writes, inspectionCount ->
                assertEquals(LocalMediaMetadataWriteOutcome.FAILED, manager.writeLocalEditableMetadata(
                    song = song, writeCover = false, writeLyrics = true
                ))

                assertEquals(1, inspectionCount())
                assertTrue(writes.isEmpty())
                assertEquals("audio", audio.readText())
            }
        }
    }

    @Test
    fun `local write wrapper does not inspect fully known lyrics or alter explicit empty tracks`() = runTest {
        val audio = temporaryFolder.newFile("known-physical-lyrics.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            matchedLyric = "edited original", matchedTranslatedLyric = "", originalRomanizedLyric = ""
        )

        withCapturedPlatformWrite(null) { writes, inspectionCount ->
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.writeLocalEditableMetadata(
                song = song, writeCover = false, writeLyrics = true
            ))

            assertEquals(0, inspectionCount())
            assertEquals("", writes.single().matchedTranslatedLyric)
            assertEquals("", writes.single().originalRomanizedLyric)
        }
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

        withRegularFileDescriptors {
            assertTrue(manager.updateSongCustomInfoImpl(
                originalSong = song,
                customCoverUrl = newCover.toURI().toString(),
                customName = null,
                customArtist = null
            ))
        }

        assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
        assertEquals("", JSONObject(File(audio.path + ".npmeta.json").readText()).getString("originalCoverUrl"))
    }

    @Test
    fun `manual acquisition writes selected lyrics and metadata in one file transaction`() = runTest {
        val audio = temporaryFolder.newFile("manual-one-write.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            durationMs = 120_000L,
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        val selected = configureManualSelection()
        manager.publishCurrentQueue(listOf(song), 0)
        val stages = mutableListOf<SongMetadataApplyStage>()
        val lyricWrites = mutableListOf<Boolean>()

        withSuccessfulAssetWrite(onWrite = { _, _, _, writeLyrics -> lyricWrites += writeLyrics }) { writes ->
            assertTrue(manager.applyManualMetadataSelection(
                song, selected,
                SongMetadataApplyOptions(
                    lyricSources = setOf(EditableLyricMatchSource.KUGOU),
                    romanizedLyrics = false, writeLocalMetadata = true
                ),
                isLatestRequest = { true }, onProgress = { stages += it }
            ))

            assertEquals(1, writes.size)
            assertEquals(listOf(true), lyricWrites)
            assertEquals("Selected", writes.single().customName)
            assertEquals("[00:01.00]selected lyric", writes.single().matchedLyric)
            assertEquals("[00:01.00]selected translation", writes.single().matchedTranslatedLyric)
            assertEquals(SongMetadataApplyStage.entries.toList(), stages)
            assertEquals(writes.single(), manager.currentPlaylist.single())
        }
    }

    @Test
    fun `manual acquisition failure does not publish any fetched metadata`() = runTest {
        val audio = temporaryFolder.newFile("manual-failed-write.mp3").apply { writeText("audio") }
        val song = localSong(audio.path)
        val selected = configureManualSelection()
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite(writeOutcome = LocalMediaMetadataWriteOutcome.FAILED) { writes ->
            val applied = manager.applyManualMetadataSelection(
                song, selected, SongMetadataApplyOptions(lyricSources = emptySet(), writeLocalMetadata = true),
                isLatestRequest = { true }, onProgress = {}
            )

            assertEquals(false, applied)
            assertEquals(1, writes.size)
            assertEquals(song, manager.currentPlaylist.single())
        }
    }

    @Test
    fun `app-only manual acquisition survives reopening without changing the audio file`() = runTest {
        val audio = temporaryFolder.newFile("manual-app-only.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            durationMs = 120_000L,
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        val selected = configureManualSelection()
        manager.publishCurrentQueue(listOf(song), 0)

        withRegularFileDescriptors {
            assertTrue(manager.applyManualMetadataSelection(
                song, selected,
                SongMetadataApplyOptions(lyricSources = setOf(EditableLyricMatchSource.KUGOU), romanizedLyrics = false),
                isLatestRequest = { true }, onProgress = {}
            ))
        }
        val reopened = LocalAudioImportManager.hydrateLocalSongTextMetadata(
            context = application,
            song = song,
            resolveCoverFallback = false,
            includeEmbeddedFallback = false,
            includeEmbeddedCoverFallback = false,
            resolveDurationFallback = false
        )

        assertEquals("audio", audio.readText())
        assertTrue(File(audio.path + ".npmeta.json").isFile)
        assertEquals("Selected", reopened.customName)
        assertEquals("Singer", reopened.customArtist)
        assertEquals("[00:01.00]selected lyric", reopened.matchedLyric)
        assertEquals("[00:01.00]selected translation", reopened.matchedTranslatedLyric)
        assertEquals("", reopened.originalLyric)
    }

    @Test
    fun `superseded manual acquisition does not commit its prepared metadata`() = runTest {
        val audio = temporaryFolder.newFile("manual-stale.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        val selected = configureManualSelection()
        manager.publishCurrentQueue(listOf(song), 0)
        var latestRequest = true
        val stages = mutableListOf<SongMetadataApplyStage>()

        withSuccessfulAssetWrite { writes ->
            val applied = manager.applyManualMetadataSelection(
                song, selected, SongMetadataApplyOptions(lyricSources = emptySet(), writeLocalMetadata = true),
                isLatestRequest = { latestRequest },
                onProgress = {
                    stages += it
                    if (it == SongMetadataApplyStage.PREPARING_METADATA) latestRequest = false
                }
            )

            assertEquals(false, applied)
            assertTrue(writes.isEmpty())
            assertEquals(song, manager.currentPlaylist.single())
            assertEquals(false, SongMetadataApplyStage.APPLYING in stages)
        }
    }

    @Test
    fun `manual acquisition cancellation at every preparation stage leaves the song and file unchanged`() = runTest {
        val audio = temporaryFolder.newFile("manual-cancel.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(durationMs = 120_000L)
        val selected = configureManualSelection()
        for (cancelAt in SongMetadataApplyStage.entries.filter { it != SongMetadataApplyStage.APPLYING }) {
            manager.publishCurrentQueue(listOf(song), 0)
            withSuccessfulAssetWrite { writes ->
                val failure = runCatching {
                    manager.applyManualMetadataSelection(
                        song, selected,
                        SongMetadataApplyOptions(lyricSources = setOf(EditableLyricMatchSource.KUGOU), romanizedLyrics = false),
                        isLatestRequest = { true },
                        onProgress = { if (it == cancelAt) throw CancellationException("cancelled at $it") }
                    )
                }.exceptionOrNull()

                assertTrue("$cancelAt", failure is CancellationException)
                assertTrue(writes.isEmpty())
                assertEquals(song, manager.currentPlaylist.single())
                assertEquals("audio", audio.readText())
                assertEquals(false, File(audio.path + ".npmeta.json").exists())
            }
        }
    }

    @Test
    fun `manual acquisition finishes publication when cancelled after applying begins`() = runTest {
        val audio = temporaryFolder.newFile("manual-commit-cancel.mp3").apply { writeText("audio") }
        val song = localSong(audio.path)
        val selected = configureManualSelection()
        manager.publishCurrentQueue(listOf(song), 0)

        withSuccessfulAssetWrite { writes ->
            lateinit var request: Deferred<Boolean>
            request = async(start = CoroutineStart.LAZY) {
                manager.applyManualMetadataSelection(
                    song, selected, SongMetadataApplyOptions(lyricSources = emptySet(), writeLocalMetadata = true),
                    isLatestRequest = { true },
                    onProgress = { if (it == SongMetadataApplyStage.APPLYING) request.cancel() }
                )
            }
            request.start()
            request.join()

            assertTrue(request.isCancelled)
            assertEquals(1, writes.size)
            assertEquals("Selected", manager.currentPlaylist.single().customName)
        }
    }

    @Test
    fun `first cover edit survives reopening and another edit before restoring no artwork`() = runTest {
        val audio = temporaryFolder.newFile("first-cover.mp3").apply { writeText("audio") }
        val firstCover = cover("first-selected.png", Color.BLUE)
        val secondCover = cover("second-selected.png", Color.GREEN)
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)

        withConfirmedCoverAbsence(true) {
            assertTrue(manager.updateSongCustomInfoImpl(song, firstCover.toURI().toString(), null, null))
            val firstBaseline = manager.currentPlaylist.single().originalCoverUrl
            val firstStored = JSONObject(File(audio.path + ".npmeta.json").readText())
            val reopened = LocalAudioImportManager.hydrateLocalSongCoverMetadata(application, song)
            manager.publishCurrentQueue(listOf(reopened), 0)
            assertTrue(manager.updateSongCustomInfoImpl(reopened, secondCover.toURI().toString(), null, null))
            val secondBaseline = manager.currentPlaylist.single().originalCoverUrl

            assertTrue(manager.updateSongCustomInfoImpl(
                manager.currentPlaylist.single(), null, null, null, restoreBaseCover = true
            ))

            assertEquals("", firstBaseline)
            assertEquals("", firstStored.opt("originalCoverUrl"))
            assertEquals("", reopened.originalCoverUrl)
            assertEquals("", secondBaseline)
            assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
            assertNull(manager.currentPlaylist.single().customCoverUrl)
            assertNull(manager.currentPlaylist.single().coverUrl)
            assertEquals("", JSONObject(File(audio.path + ".npmeta.json").readText()).getString("originalCoverUrl"))
        }
    }

    @Test
    fun `bulk first cover edit preserves confirmed absence across reopening and restoration`() = runTest {
        val audio = temporaryFolder.newFile("first-bulk-cover.mp3").apply { writeText("audio") }
        val firstCover = cover("bulk-first-selected.png", Color.BLUE)
        val secondCover = cover("bulk-second-selected.png", Color.GREEN)
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )
        manager.publishCurrentQueue(listOf(song), 0)
        val persistWrittenCover: (SongItem) -> Unit = { written ->
            File(audio.path + ".npmeta.json").writeText(JSONObject()
                .put("originalCoverUrl", written.originalCoverUrl ?: JSONObject.NULL)
                .put("coverPath", written.customCoverUrl)
                .toString())
        }

        withConfirmedCoverAbsence(true) {
            withSuccessfulAssetWrite(afterFirstWrite = persistWrittenCover) {
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.embedLocalSongAssetsImpl(
                    song, LocalSongAssetEmbedding(coverReference = firstCover.toURI().toString())
                ))
            }
            val firstBaseline = manager.currentPlaylist.single().originalCoverUrl
            val reopened = LocalAudioImportManager.hydrateLocalSongCoverMetadata(application, song)
            manager.publishCurrentQueue(listOf(reopened), 0)
            withSuccessfulAssetWrite(afterFirstWrite = persistWrittenCover) {
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.embedLocalSongAssetsImpl(
                    reopened, LocalSongAssetEmbedding(coverReference = secondCover.toURI().toString())
                ))
            }
            val secondBaseline = manager.currentPlaylist.single().originalCoverUrl
            assertTrue(manager.updateSongCustomInfoImpl(
                manager.currentPlaylist.single(), null, null, null, restoreBaseCover = true
            ))

            assertEquals("", firstBaseline)
            assertEquals("", reopened.originalCoverUrl)
            assertEquals("", secondBaseline)
            assertEquals("", manager.currentPlaylist.single().originalCoverUrl)
            assertNull(manager.currentPlaylist.single().coverUrl)
            assertNull(manager.currentPlaylist.single().customCoverUrl)
        }
    }

    @Test
    fun `uncertain original artwork stays unknown for first single and bulk replacements`() = runTest {
        val audio = temporaryFolder.newFile("unknown-cover.mp3").apply { writeText("audio") }
        val selected = cover("unknown-selected.png")
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )

        withConfirmedCoverAbsence(false) {
            manager.publishCurrentQueue(listOf(song), 0)
            withSuccessfulAssetWrite { writes ->
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, manager.embedLocalSongAssetsImpl(
                    song, LocalSongAssetEmbedding(coverReference = selected.toURI().toString())
                ))
                assertNull(writes.single().originalCoverUrl)
                assertNull(manager.currentPlaylist.single().originalCoverUrl)
            }
            manager.publishCurrentQueue(listOf(song), 0)
            assertTrue(manager.updateSongCustomInfoImpl(song, selected.toURI().toString(), null, null))
            assertNull(manager.currentPlaylist.single().originalCoverUrl)
            assertTrue(JSONObject(File(audio.path + ".npmeta.json").readText()).isNull("originalCoverUrl"))
        }
    }

    @Test
    fun `manual match preparation records only confirmed no-artwork baselines`() = runTest {
        val audio = temporaryFolder.newFile("manual-empty-cover.mp3").apply { writeText("audio") }
        val song = localSong(audio.path).copy(
            originalLyric = "", originalTranslatedLyric = "", originalRomanizedLyric = ""
        )

        for (confirmed in listOf(false, true)) {
            withConfirmedCoverAbsence(confirmed) {
                val prepared = manager.preserveLocalSongMetadataOriginals(song, preserveCover = true)
                assertEquals(if (confirmed) "" else null, prepared.originalCoverUrl)
            }
        }
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

    private suspend fun configureManualSelection(): SongSearchInfo {
        val selected = SongSearchInfo("42", "Selected", "Singer", "02:00", MusicPlatform.CLOUD_MUSIC, null, null)
        val api = mock(CloudMusicSearchApi::class.java)
        `when`(repositories.cloudMusicSearchApi).thenReturn(api)
        `when`(api.getSongMetadataOnly("42")).thenReturn(SongDetails("42", "Selected", "Singer", "Album", null, null))
        val matches = listOf(RankedEditableLyricMatch(
            candidate = EditableLyricMatchCandidate(
                id = "kugou-42", source = EditableLyricMatchSource.KUGOU,
                title = "Selected", artist = "Singer", durationMs = 120_000L,
                lyrics = "[00:01.00]selected lyric", translatedLyrics = "[00:01.00]selected translation"
            ),
            score = 100, durationDeltaMs = 0L, confidence = EditableLyricMatchConfidence.HIGH
        ))
        val matcher = mock(EditableLyricsMatcher::class.java, Answer { invocation ->
            if (invocation.method.name == "matchLyrics") matches else Answers.RETURNS_DEFAULTS.answer(invocation)
        })
        `when`(repositories.editableLyricsMatcher).thenReturn(matcher)
        return selected
    }

    private suspend fun withConfirmedCoverAbsence(confirmed: Boolean, block: suspend () -> Unit) =
        withRegularFileDescriptors {
            mockStatic(LocalCoverBaselineInspector::class.java, Answer { invocation ->
                if (invocation.method.name == "hasConfirmedNoOriginalCover") confirmed
                else invocation.callRealMethod()
            }).use { block() }
        }

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
        onWrite: ((SongItem, String?, Boolean, Boolean) -> Unit)? = null,
        writeOutcome: LocalMediaMetadataWriteOutcome = LocalMediaMetadataWriteOutcome.SUCCESS,
        block: suspend (List<SongItem>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val writes = mutableListOf<SongItem>()
        val facade = Class.forName("moe.ouom.neriplayer.core.player.persistence.PlayerManagerPersistenceExtensionsKt")
        mockStatic(facade, Answer { invocation ->
            if (invocation.method.name == "writeLocalEditableMetadata") {
                val target = invocation.getArgument<SongItem>(1)
                writes += target
                onWrite?.invoke(target, invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4))
                if (writes.size == 1) afterFirstWrite?.invoke(target)
                writeOutcome
            } else {
                invocation.callRealMethod()
            }
        }).use { block(writes) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun withCapturedPlatformWrite(
        inspection: LocalLyricsScanMetadata?,
        block: suspend (List<SongItem>, () -> Int) -> Unit
    ) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            withContext(Dispatchers.IO) {
                val writes = mutableListOf<SongItem>()
                var inspectionCount = 0
                val facade = Class.forName("moe.ouom.neriplayer.data.local.media.LocalMediaSupportFacadeInspectKt")
                mockStatic(facade, Answer { invocation ->
                    when (invocation.method.name) {
                        "inspectEmbeddedLyricsImpl" -> {
                            inspectionCount++
                            inspection
                        }
                        "writeEditableMetadataImpl" -> {
                            writes += invocation.getArgument<SongItem>(2)
                            LocalMediaMetadataWriteOutcome.SUCCESS
                        }
                        else -> invocation.callRealMethod()
                    }
                }).use { block(writes) { inspectionCount } }
            }
        } finally {
            Dispatchers.resetMain()
        }
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
