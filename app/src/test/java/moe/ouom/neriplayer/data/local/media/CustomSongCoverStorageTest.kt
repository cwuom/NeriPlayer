package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContentResolver
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class CustomSongCoverStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `remote original cover references are localized and mapped`() = runBlocking {
        val reference = "HTTPS://example.com/cover.jpg"
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        var mappedLocal: String? = null
        var mappedRemote: String? = null
        val previousClient = CustomSongCoverStorage.remoteCoverHttpClientProvider
        val previousValidator = CustomSongCoverStorage.remoteCoverImageValidator
        val previousSink = CustomSongCoverStorage.remoteCoverMappingSink
        CustomSongCoverStorage.remoteCoverHttpClientProvider = {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/png")
                        .body(byteArrayOf(1, 2, 3).toResponseBody("image/png".toMediaType()))
                        .build()
                }
                .build()
        }
        CustomSongCoverStorage.remoteCoverImageValidator = { true }
        CustomSongCoverStorage.remoteCoverMappingSink = { local, remote ->
            mappedLocal = local
            mappedRemote = remote
        }
        try {
            val persisted = CustomSongCoverStorage.persistOriginalCover(
                context = context,
                song = testSong(),
                reference = reference
            )

            assertNotNull(persisted)
            assertTrue(persisted?.startsWith("file:") == true)
            assertTrue(persisted?.contains("RemoteCovers") == true)
            assertEquals(persisted, mappedLocal)
            assertEquals(reference, mappedRemote)
            assertEquals(
                byteArrayOf(1, 2, 3).toList(),
                File(tempFolder.root, "RemoteCovers").listFiles()?.single()?.readBytes()?.toList()
            )
        } finally {
            CustomSongCoverStorage.remoteCoverHttpClientProvider = previousClient
            CustomSongCoverStorage.remoteCoverImageValidator = previousValidator
            CustomSongCoverStorage.remoteCoverMappingSink = previousSink
        }
    }

    @Test
    fun `local original cover is copied to a stable private file`() = runBlocking {
        val source = tempFolder.newFile("source-cover.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        `when`(context.contentResolver).thenReturn(resolver)

        val first = CustomSongCoverStorage.persistOriginalCover(
            context = context,
            song = testSong(),
            reference = source.absolutePath
        )
        val storedFiles = File(tempFolder.root, "original_song_covers").listFiles()

        assertNotNull(first)
        assertTrue(first?.startsWith("file:") == true)
        assertEquals(1, storedFiles?.size)
        assertEquals(
            CustomSongCoverStorage.originalCoverFileName(testSong(), "png"),
            storedFiles?.single()?.name
        )
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), storedFiles?.single()?.readBytes()?.toList())

        val second = CustomSongCoverStorage.persistOriginalCover(
            context = context,
            song = testSong(),
            reference = first
        )
        assertEquals(first, second)
    }

    @Test
    fun `legacy original cover directory resolves the song cover file`() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        val directory = File(tempFolder.root, "original_song_covers").apply { mkdirs() }
        val stored = File(
            directory,
            CustomSongCoverStorage.originalCoverFileName(testSong(), "jpg")
        ).apply {
            writeBytes(byteArrayOf(8, 9, 10))
        }

        val resolved = CustomSongCoverStorage.persistOriginalCover(
            context = context,
            song = testSong(),
            reference = directory.toURI().toString()
        )

        assertEquals(stored.toURI().toString(), resolved)
    }

    @Test
    fun `legacy cover lookup skips unrelated directories and resolves the original file`() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        val unrelated = tempFolder.newFolder("custom_song_covers")
        val directory = File(tempFolder.root, "original_song_covers").apply { mkdirs() }
        val stored = File(
            directory,
            CustomSongCoverStorage.originalCoverFileName(testSong(), "jpg")
        ).apply {
            writeBytes(byteArrayOf(8, 9, 10))
        }

        val resolved = CustomSongCoverStorage.resolveLegacyOriginalCoverReference(
            context = context,
            song = testSong(),
            references = listOf(unrelated.toURI().toString(), directory.toURI().toString())
        )

        assertEquals(stored.toURI().toString(), resolved)
    }

    @Test
    fun `unmatched original cover directory is rejected`() = runBlocking {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        val directory = tempFolder.newFolder("invalid-cover-directory")

        val resolved = CustomSongCoverStorage.persistOriginalCover(
            context = context,
            song = testSong(),
            reference = directory.toURI().toString()
        )

        assertNull(resolved)
    }

    private fun testSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "/music/song.mp3",
            localFilePath = "/music/song.mp3",
            channelId = "local",
            audioId = "42"
        )
    }
}
