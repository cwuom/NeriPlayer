package moe.ouom.neriplayer.core.player.download.playback

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaCovers
import moe.ouom.neriplayer.core.download.host.media.AndroidLocalMediaDownloads
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.core.player.download.ownership.AudioDownloadReferenceRegistry
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.isUsableCoverReference
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AudioDownloadPlaybackCoverTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access")
        .apply { isAccessible = true }
    private var previousMediaAccess: Any? = null
    private var previousDirectoryUri: String? = null
    private lateinit var context: Context
    private lateinit var audio: File
    private lateinit var nearbyCover: File
    private lateinit var staleAudio: File
    private val song = SongItem(
        id = 618L,
        name = "Downloaded song",
        artist = "Artist",
        album = "Netease",
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = "https://example.com/cover.png",
        channelId = "netease",
        audioId = "618"
    )
    private val coordinator = AudioDownloadPlaybackCoordinator(
        AudioDownloadReferenceRegistry(30_000L, 16),
        isSongDownloadActive = { false },
        directoryMutationWaitMs = 1L
    )

    @Before
    fun setUp() {
        previousMediaAccess = mediaAccessField.get(null)
        previousDirectoryUri = ManagedDownloadStorage.configuredDirectoryUri()
        val files = directory.newFolder("files")
        val cache = directory.newFolder("cache")
        val music = directory.newFolder("music")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getExternalFilesDir(type: String?): File = music
        }
        val currentFolder = directory.newFolder("current")
        audio = File(currentFolder, "Song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        nearbyCover = writeCover(currentFolder, "Song.png")
        val staleFolder = directory.newFolder("stale")
        staleAudio = File(staleFolder, "Song.mp3").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        writeCover(staleFolder, "Song.png")
        ManagedDownloadStorage.settings.updateDirectoryUri(null)
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        LocalMediaHostAccess.bind(AndroidLocalMediaDownloads, AndroidLocalMediaCovers, CrashLogCleanup { false })
        publishSnapshot()
    }

    @After
    fun tearDown() {
        listOf(audio, staleAudio).forEach { file ->
            LocalMediaSupport.invalidateSongAssetCaches(
                song.copy(mediaUri = file.toURI().toString(), localFilePath = file.absolutePath)
            )
        }
        ManagedDownloadStorage.snapshotCacheStore.invalidate()
        ManagedDownloadStorage.settings.updateDirectoryUri(previousDirectoryUri)
        mediaAccessField.set(null, previousMediaAccess)
    }

    @Test
    fun `finalized online song resolves its local audio cover without an indexed sidecar`() = runBlocking {
        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertEquals(nearbyCover.toURI().toString(), cover)
    }

    @Test
    fun `finalized audio cover ignores stale local references on the queued song`() = runBlocking {
        val queuedSong = song.copy(
            mediaUri = staleAudio.toURI().toString(),
            localFilePath = staleAudio.absolutePath,
            localFileName = staleAudio.name
        )

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, queuedSong) }

        assertEquals(nearbyCover.toURI().toString(), cover)
    }

    @Test
    fun `explicit scanned source keeps its cover instead of same identity downloaded audio`() = runBlocking {
        val scannedSong = song.copy(
            mediaUri = staleAudio.toURI().toString(),
            localFilePath = staleAudio.absolutePath,
            localFileName = staleAudio.name,
            sourceStableKey = song.stableKey(),
            explicitLocalSourceReference = staleAudio.absolutePath
        )

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, scannedSong) }

        assertEquals(File(staleAudio.parentFile, "Song.png").toURI().toString(), cover)
    }

    @Test
    fun `configured SAF root does not reuse cover from an old private audio path`() = runBlocking {
        val currentRoot = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Music/Current"
        )
        val currentAudio = DocumentsContract.buildDocumentUriUsingTree(
            currentRoot,
            "primary:Music/Current/Song.mp3"
        )
        ManagedDownloadStorage.settings.updateDirectoryUri(currentRoot.toString())
        publishSnapshot(entry = storedEntry(staleAudio).copy(reference = currentAudio.toString()))

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertNull(cover)
    }

    @Test
    fun `indexed sidecar stays ahead of local audio inspection`() = runBlocking {
        val indexedCover = writeCover(directory.newFolder("Covers"), "indexed.png")
        publishSnapshot(cover = indexedCover)

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertEquals(ManagedDownloadStorage.toPlayableUri(indexedCover.absolutePath), cover)
    }

    @Test
    fun `deleted indexed sidecar does not hide the local audio cover`() = runBlocking {
        val indexedCover = writeCover(directory.newFolder("Covers"), "indexed.png")
        publishSnapshot(cover = indexedCover)
        assertTrue(indexedCover.delete())

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertEquals(nearbyCover.toURI().toString(), cover)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `malformed indexed sidecar does not hide the local audio cover`() = runBlocking {
        val indexedCover = writeCover(directory.newFolder("Covers"), "indexed.png")
        publishSnapshot(cover = indexedCover)
        indexedCover.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(isUsableCoverReference(context, indexedCover.toURI().toString()))

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertEquals(nearbyCover.toURI().toString(), cover)
    }

    @Test
    fun `disabled local fallback does not inspect finalized audio`() = runBlocking {
        val cover = withContext(Dispatchers.IO) {
            coordinator.getLocalCoverUri(context, song, resolveLocalMediaFallback = false)
        }

        assertNull(cover)
    }

    @Test
    fun `main thread does not inspect finalized audio`() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())

        assertNull(coordinator.getLocalCoverUri(context, song))
    }

    @Test
    fun `unfinalized audio cannot provide a local cover`() = runBlocking {
        publishSnapshot(finalized = false)

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertNull(cover)
    }

    @Test
    fun `incomplete download root cannot provide a local audio cover`() = runBlocking {
        publishSnapshot(rootComplete = false)

        val cover = withContext(Dispatchers.IO) { coordinator.getLocalCoverUri(context, song) }

        assertNull(cover)
    }

    private fun publishSnapshot(
        finalized: Boolean = true,
        rootComplete: Boolean = true,
        cover: File? = null,
        entry: ManagedDownloadStorage.StoredEntry = storedEntry(audio)
    ) {
        val metadata = DownloadedAudioMetadata(
            stableKey = song.stableKey(),
            songId = song.id,
            channelId = song.channelId,
            audioId = song.audioId,
            coverPath = cover?.absolutePath,
            downloadFinalized = finalized,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(entry),
            metadataEntries = emptyList(),
            metadataByAudioName = mapOf(entry.name to metadata),
            coverEntries = listOfNotNull(cover?.let(::storedEntry)),
            lyricEntries = emptyList(),
            rootEntriesComplete = rootComplete
        )
        ManagedDownloadStorage.snapshotCacheStore.putSnapshot(
            context,
            ManagedDownloadStorage.currentSnapshotCacheKey(context),
            snapshot
        )
    }

    private fun storedEntry(file: File) = ManagedDownloadStorage.StoredEntry(
        name = file.name,
        reference = file.absolutePath,
        mediaUri = file.toURI().toString(),
        localFilePath = file.absolutePath,
        sizeBytes = file.length(),
        lastModifiedMs = file.lastModified()
    )

    private fun writeCover(folder: File, name: String): File = File(folder, name).apply {
        writeBytes(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg=="
        ))
    }
}
