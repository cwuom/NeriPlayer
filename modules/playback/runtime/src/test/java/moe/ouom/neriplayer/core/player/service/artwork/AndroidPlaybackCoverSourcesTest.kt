package moe.ouom.neriplayer.core.player.service.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlaybackCoverSourcesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val downloads = mock(PlayerDownloadAccess::class.java)
    private val localCovers = mock(LocalMediaCoverAccess::class.java)
    private val sources = AndroidPlaybackCoverSources(context)
    private val registry = PlayerDependencies::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val environmentField = registry.javaClass.getDeclaredField("environment")
        .apply { isAccessible = true }
    private val previousEnvironment = environmentField.get(registry)
    private val mediaAccessField = LocalMediaHostAccess::class.java.getDeclaredField("access")
        .apply { isAccessible = true }
    private val previousMediaAccess = mediaAccessField.get(null)

    @Before
    fun setUp() {
        environmentField.set(registry, null)
        PlayerTestEnvironment.install(downloads = downloads)
        LocalMediaHostAccess.bind(
            mock(LocalMediaDownloadAccess::class.java),
            localCovers,
            CrashLogCleanup { false },
        )
    }

    @After
    fun tearDown() {
        environmentField.set(registry, previousEnvironment)
        mediaAccessField.set(null, previousMediaAccess)
    }

    @Test
    fun `known downloaded remote song defers its platform artwork without opening audio immediately`() {
        val song = remoteSong()
        `when`(downloads.hasDownloadedSongCached(song)).thenReturn(true)
        doThrow(AssertionError("immediate artwork must not open audio")).`when`(downloads)
            .getLocalPlaybackUri(context, song)

        assertNull(sources.immediate(song))
    }

    @Test
    fun `stream only remote song retains immediate platform artwork`() {
        val song = remoteSong()

        assertEquals(song.coverUrl, sources.immediate(song))
    }

    @Test
    fun `downloaded remote song retains explicit custom remote artwork`() {
        val song = remoteSong().copy(customCoverUrl = "https://custom.example/artwork.jpg")
        `when`(downloads.hasDownloadedSongCached(song)).thenReturn(true)

        assertEquals(song.customCoverUrl, sources.immediate(song))
    }

    @Test
    fun `downloaded remote song retains immediate indexed sidecar artwork`() {
        val song = remoteSong()
        val sidecar = temporaryFolder.newFile("sidecar.png").apply { writeBytes(imageBytes(Color.BLUE)) }
        val sidecarUri = Uri.fromFile(sidecar).toString()
        `when`(downloads.hasDownloadedSongCached(song)).thenReturn(true)
        `when`(localCovers.getLocalCoverUri(context, song, false)).thenReturn(sidecarUri)

        assertEquals(sidecarUri, sources.immediate(song))
    }

    @Test
    fun `owner resolves downloaded remote song embedded artwork before requesting platform artwork`() = runTest {
        val song = remoteSong()
        val audio = temporaryFolder.newFile("owner-download.mp3").apply { writeText("audio") }
        val audioUri = Uri.fromFile(audio)
        val embeddedArtwork = imageBytes(Color.RED)
        `when`(downloads.hasDownloadedSongCached(song)).thenReturn(true)
        `when`(downloads.getLocalPlaybackUri(context, song)).thenReturn(audioUri.toString())
        val owner = PlaybackArtworkOwner(
            resolver = PlaybackCoverSourceResolver(sources),
            loader = PlaybackArtworkBitmapLoader { reference ->
                val uri = Uri.parse(reference)
                uri.path?.takeIf { uri.scheme == "file" }?.let(BitmapFactory::decodeFile)
            },
            clock = PlaybackArtworkClock { testScheduler.currentTime + 1_000L },
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onChange = {},
        )

        try {
            withEmbeddedPictures(mapOf(audioUri.toString() to embeddedArtwork)) {
                assertNull(owner.observe(song).coverSource)
                runCurrent()
                val resolved = owner.snapshot(song.playbackVisualKey()).coverSource
                assertNotNull(resolved)
                assertTrue(checkNotNull(resolved).startsWith("file:"))
                assertArrayEquals(embeddedArtwork, File(checkNotNull(Uri.parse(resolved).path)).readBytes())

                owner.observe(song)
                runCurrent()

                assertTrue(owner.snapshot(song.playbackVisualKey()).mediaReady)
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun `remote song without a sidecar resolves embedded artwork from verified downloaded audio`() {
        val song = remoteSong()
        val audio = temporaryFolder.newFile("downloaded.mp3").apply { writeText("audio") }
        val audioUri = Uri.fromFile(audio)
        val embeddedArtwork = imageBytes(Color.RED)
        `when`(downloads.getLocalPlaybackUri(context, song)).thenReturn(audioUri.toString())

        withEmbeddedPictures(mapOf(audioUri.toString() to embeddedArtwork)) {
            val resolved = sources.resolveLocal(song)

            assertNotNull(resolved)
            assertArrayEquals(embeddedArtwork, File(checkNotNull(Uri.parse(resolved).path)).readBytes())
        }
    }

    @Test
    fun `existing song source artwork takes priority over the same identity download`() {
        val scannedAudio = temporaryFolder.newFile("scanned.mp3").apply { writeText("audio") }
        val scannedUri = Uri.fromFile(scannedAudio)
        val downloadedAudio = temporaryFolder.newFile("downloaded.mp3").apply { writeText("audio") }
        val downloadedUri = Uri.fromFile(downloadedAudio)
        val song = remoteSong().copy(localFilePath = scannedAudio.path)
        val scannedArtwork = imageBytes(Color.RED)
        val downloadedArtwork = imageBytes(Color.BLUE)
        `when`(downloads.getLocalPlaybackUri(context, song)).thenReturn(downloadedUri.toString())

        withEmbeddedPictures(mapOf(
            scannedUri.toString() to scannedArtwork,
            downloadedUri.toString() to downloadedArtwork,
        )) {
            val resolved = sources.resolveLocal(song)

            assertNotNull(resolved)
            assertArrayEquals(scannedArtwork, File(checkNotNull(Uri.parse(resolved).path)).readBytes())
        }
    }

    @Test
    fun `explicit scanned source without artwork does not borrow the same identity download artwork`() {
        val scannedAudio = File(temporaryFolder.root, "missing-scanned.mp3")
        val downloadedAudio = temporaryFolder.newFile("downloaded.mp3").apply { writeText("audio") }
        val downloadedUri = Uri.fromFile(downloadedAudio)
        val song = remoteSong().copy(
            localFilePath = scannedAudio.path,
            explicitLocalSourceReference = scannedAudio.path,
        )
        `when`(downloads.getLocalPlaybackUri(context, song)).thenReturn(downloadedUri.toString())

        withEmbeddedPictures(mapOf(downloadedUri.toString() to imageBytes(Color.BLUE))) {
            assertNull(sources.resolveLocal(song))
        }
    }

    @Test
    fun `unavailable downloaded audio leaves embedded artwork unresolved`() {
        assertNull(sources.resolveLocal(remoteSong()))
    }

    @Test
    fun `downloaded reference lookup preserves cancellation`() {
        val song = remoteSong()
        val cancellation = CancellationException("cancelled artwork resolution")
        `when`(downloads.getLocalPlaybackUri(context, song)).thenThrow(cancellation)

        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            sources.resolveLocal(song)
        })
    }

    private fun withEmbeddedPictures(pictures: Map<String, ByteArray>, block: () -> Unit) {
        mockConstruction(MediaMetadataRetriever::class.java) { retriever, _ ->
            var source: String? = null
            doAnswer { invocation ->
                source = invocation.getArgument<Uri>(1).toString()
                null
            }.`when`(retriever).setDataSource(eq(context), any(Uri::class.java))
            `when`(retriever.embeddedPicture).thenAnswer { pictures[source] }
        }.use { block() }
    }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun remoteSong(): SongItem = SongItem(
        id = 618L,
        name = "Downloaded song",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 180_000L,
        coverUrl = "https://covers.example/song.jpg",
        channelId = "netease",
        audioId = "618",
    )
}
