package moe.ouom.neriplayer.data.local.media

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

class CustomSongManualRemoteCoverMappingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val previousClient = CustomSongCoverStorage.remoteCoverHttpClientProvider
    private val previousValidator = CustomSongCoverStorage.remoteCoverImageValidator
    private val previousSink = CustomSongCoverStorage.remoteCoverMappingSink
    private val context = mock(Context::class.java)
    private val requested = CopyOnWriteArrayList<String>()
    private var responseCode = 200

    @Before
    fun installRemoteCoverFakes() {
        doReturn(tempFolder.root).`when`(context).filesDir
        CustomSongCoverStorage.remoteCoverHttpClientProvider = {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requested += chain.request().url.toString()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(responseCode)
                        .message(if (responseCode == 200) "OK" else "Not Found")
                        .header("Content-Type", "image/jpeg")
                        .body(COVER_BYTES.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
                .build()
        }
        CustomSongCoverStorage.remoteCoverImageValidator = { it.contentEquals(COVER_BYTES) }
    }

    @After
    fun restoreRemoteCoverHooks() {
        CustomSongCoverStorage.remoteCoverHttpClientProvider = previousClient
        CustomSongCoverStorage.remoteCoverImageValidator = previousValidator
        CustomSongCoverStorage.remoteCoverMappingSink = previousSink
        CoverUrlMapper.installForTest(null)
    }

    @Test
    fun `selected remote covers are mapped through the shared cover mapper without a sink`() = runTest {
        val mapper = CoverUrlMapper.createForTest()
        CoverUrlMapper.installForTest(mapper)
        CustomSongCoverStorage.remoteCoverMappingSink = null

        val stored = requireNotNull(CustomSongCoverStorage.persistManuallySelectedRemoteCover(context, " $SOURCE "))

        assertEquals(listOf(SOURCE), requested)
        assertEquals(COVER_BYTES.toList(), File(URI(stored)).readBytes().toList())
        assertEquals(SOURCE, mapper.getNetworkUrl(stored))
        assertEquals(SOURCE, mapper.getSyncableNetworkUrl(stored))
    }

    @Test
    fun `selected remote covers that fail to download are neither stored nor mapped`() = runTest {
        val mappings = CopyOnWriteArrayList<Pair<String, String>>()
        CustomSongCoverStorage.remoteCoverMappingSink = { local, remote -> mappings += local to remote }
        responseCode = 404

        val stored = CustomSongCoverStorage.persistManuallySelectedRemoteCover(context, SOURCE)

        assertNull(stored)
        assertEquals(listOf(SOURCE), requested)
        assertEquals(emptyList<Pair<String, String>>(), mappings)
        assertEquals(emptyList<String>(), File(tempFolder.root, "custom_song_covers").list().orEmpty().toList())
    }

    private companion object {
        const val SOURCE = "https://img.example/albums/night-drive.jpg"
        val COVER_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    }
}
