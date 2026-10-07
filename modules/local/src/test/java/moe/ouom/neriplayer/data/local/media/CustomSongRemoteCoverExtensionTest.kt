package moe.ouom.neriplayer.data.local.media

import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class CustomSongRemoteCoverExtensionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val previousClient = CustomSongCoverStorage.remoteCoverHttpClientProvider
    private val previousValidator = CustomSongCoverStorage.remoteCoverImageValidator
    private val previousSink = CustomSongCoverStorage.remoteCoverMappingSink
    private val requested = CopyOnWriteArrayList<String>()
    private val mappings = CopyOnWriteArrayList<Pair<String, String>>()
    private val context = mock(Context::class.java)

    @Before
    fun installRemoteCoverFakes() {
        `when`(context.filesDir).thenReturn(tempFolder.root)
        CustomSongCoverStorage.remoteCoverHttpClientProvider = {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val url = chain.request().url.toString()
                    requested += url
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/png; charset=binary")
                        .body(url.toByteArray().toResponseBody("image/png".toMediaType()))
                        .build()
                }
                .build()
        }
        CustomSongCoverStorage.remoteCoverImageValidator = { true }
        CustomSongCoverStorage.remoteCoverMappingSink = { local, remote -> mappings += local to remote }
    }

    @After
    fun restoreRemoteCoverHooks() {
        CustomSongCoverStorage.remoteCoverHttpClientProvider = previousClient
        CustomSongCoverStorage.remoteCoverImageValidator = previousValidator
        CustomSongCoverStorage.remoteCoverMappingSink = previousSink
    }

    @Test
    fun `stored remote covers keep the url extension in lower case`() = runTest {
        val source = "https://img.example/art/cover.PNG?size=large"

        val stored = persist(source)

        assertEquals("png", stored.extension)
        assertEquals(sha256(source.toByteArray()), stored.nameWithoutExtension)
        assertEquals(source, stored.readText())
        assertEquals(listOf(stored.toURI().toString() to source), mappings)
    }

    @Test
    fun `extensionless remote covers are stored as jpg`() = runTest {
        val source = "http://localhost/cover"

        val stored = persist(source)

        assertEquals("jpg", stored.extension)
        assertEquals(listOf(source), requested)
        assertEquals(source, stored.readText())
    }

    @Test
    fun `dot segment urls are fetched normalized but mapped under the original url`() = runTest {
        val source = "http://localhost/covers/./front"

        val stored = persist(source)

        assertEquals(listOf("http://localhost/covers/front"), requested)
        assertEquals(sha256("http://localhost/covers/front".toByteArray()), stored.nameWithoutExtension)
        assertEquals("http://localhost/covers/front", stored.readText())
        assertEquals(listOf(stored.toURI().toString() to source), mappings)
    }

    @Test
    fun `blank and non remote references are never downloaded`() = runTest {
        assertNull(CustomSongCoverStorage.persistManuallySelectedRemoteCover(context, null))
        assertNull(CustomSongCoverStorage.persistManuallySelectedRemoteCover(context, "   "))
        assertEquals(
            "content://media/external/images/7",
            CustomSongCoverStorage.persistManuallySelectedRemoteCover(context, "  content://media/external/images/7 ")
        )
        assertTrue(requested.isEmpty())
        assertTrue(mappings.isEmpty())
        assertTrue(!File(tempFolder.root, "custom_song_covers").exists())
    }

    private suspend fun persist(source: String): File {
        val persisted = requireNotNull(CustomSongCoverStorage.persistManuallySelectedRemoteCover(context, source))
        val stored = File(URI(persisted))
        assertEquals(File(tempFolder.root, "custom_song_covers").canonicalFile, stored.parentFile?.canonicalFile)
        return stored
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
