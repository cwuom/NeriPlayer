package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        val repository = mock(LocalPlaylistRepository::class.java)
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

    private fun cover(name: String): File = temporaryFolder.newFile(name).also { file ->
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
