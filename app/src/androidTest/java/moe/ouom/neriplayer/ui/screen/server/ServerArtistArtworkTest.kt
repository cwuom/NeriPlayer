package moe.ouom.neriplayer.ui.screen.server

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.artist.CreatorDetailProfileCard
import moe.ouom.neriplayer.ui.screen.server.artist.ServerArtistArtwork
import moe.ouom.neriplayer.ui.theme.NeriTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerArtistArtworkTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireHost() = assumeComposeHostAvailable()

    @Test fun serverPortraitWinsWithoutFetchingSongFallback() = verify(serverPresent = true, serverFails = false)
    @Test fun missingPortraitUsesSongArtworkInListAndProfile() = verify(serverPresent = false, serverFails = false)
    @Test fun failedPortraitUsesSongArtworkInListAndProfile() = verify(serverPresent = true, serverFails = true)

    private fun verify(serverPresent: Boolean, serverFails: Boolean) {
        val requests = CopyOnWriteArrayList<String>()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                ByteArrayOutputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    stream.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        val portrait = png(android.graphics.Color.BLUE)
        val song = png(android.graphics.Color.MAGENTA)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "artist-artwork.test")
            requests += request.url.encodedPath
            val fails = request.url.encodedPath == "/portrait" && serverFails
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (fails) 404 else 200).message(if (fails) "Not Found" else "OK")
                .body((if (request.url.encodedPath == "/portrait") portrait else song)
                    .toResponseBody("image/png".toMediaType())).build()
        }.build()
        val originalLoader = Coil.imageLoader(context)
        val loader = originalLoader.newBuilder().okHttpClient(client).memoryCache(null).diskCache(null).build()
        val artist = ServerArtist("fixture-profile", "artist / 中文", "Artist",
            if (serverPresent) "https://artist-artwork.test/portrait" else null, 2,
            "https://artist-artwork.test/song")
        try {
            Coil.setImageLoader(loader)
            composeRule.setContent {
                NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                    seedColorHex = "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(20.dp)) {
                            ServerArtistRow(artist, onClick = {})
                            CreatorDetailProfileCard(artist.name, artist.imageUrl, "2 albums", false,
                                artwork = { ServerArtistArtwork(artist, false, it) })
                        }
                    }
                }
            }
            val expectedColor = if (serverPresent && !serverFails) android.graphics.Color.BLUE else android.graphics.Color.MAGENTA
            val artworks = composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(
                "serverArtistArtwork:${artist.profileId}:${artist.id}"), useUnmergedTree = true)
            artworks.assertCountEquals(2)
            composeRule.waitUntil(10_000L) {
                (0..1).all { index ->
                    val pixels = artworks[index].captureToImage().toPixelMap()
                    pixels[pixels.width / 2, pixels.height / 2].toArgb() == expectedColor
                }
            }
            val expectedPaths = if (!serverPresent) setOf("/song") else if (serverFails) setOf("/portrait", "/song") else setOf("/portrait")
            assertEquals(expectedPaths, requests.toSet())
        } finally {
            composeRule.runOnIdle { Coil.setImageLoader(originalLoader) }
            loader.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
