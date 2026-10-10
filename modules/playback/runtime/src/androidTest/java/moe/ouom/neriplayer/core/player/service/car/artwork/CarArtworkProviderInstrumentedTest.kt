package moe.ouom.neriplayer.core.player.service.car.artwork

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class CarArtworkProviderInstrumentedTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var provider: CarArtworkProvider

    @Before
    fun createProvider() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(application.cacheDir, "car-artwork-test-${System.nanoTime()}")
        context = object : ContextWrapper(application) {
            override fun getCacheDir(): File = directory
        }
        provider = CarArtworkProvider().apply {
            val applicationContext = this@CarArtworkProviderInstrumentedTest.context
            attachInfo(applicationContext, ProviderInfo().apply { authority = "${applicationContext.packageName}.car-artwork" })
        }
    }

    @After
    fun removeTestCache() {
        directory.deleteRecursively()
    }

    @Test
    fun publishedBitmapIsSmallJpegWithStableUriAndRetainsCallerBitmap() = runBlocking {
        val song = song()
        val bitmap = Bitmap.createBitmap(1_024, 512, Bitmap.Config.ARGB_8888)
        val uri = checkNotNull(CarArtworkProvider.publish(context, song, bitmap, song.coverUrl))
        assertEquals(CarArtworkProvider.uriFor(context, song), uri)
        assertEquals("image/jpeg", provider.getType(uri))
        provider.openFile(uri, "r").use { descriptor ->
            val decoded = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            assertNotNull(decoded)
            assertEquals(512, decoded.width)
            assertEquals(256, decoded.height)
            decoded.recycle()
        }
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun recoveredSourceGetsNewUriWithoutOverwritingTheOriginalCache() = runBlocking {
        val song = song()
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val original = CarArtworkProvider.publish(context, song, bitmap, song.coverUrl)
        val recovered = CarArtworkProvider.publish(context, song, bitmap, "content://managed/new")
        assertNotEquals(original, recovered)
        provider.openFile(checkNotNull(original), "r").close()
        provider.openFile(checkNotNull(recovered), "r").close()
        bitmap.recycle()
    }

    @Test
    fun providerRejectsWriteModesArbitraryPathsQueriesAndAuthorities() {
        val uri = CarArtworkProvider.uriFor(context, song())
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "rw") }
        val invalid = listOf(
            uri.buildUpon().appendPath("extra").build(),
            uri.buildUpon().encodedPath("/v1/%2e%2e").build(),
            uri.buildUpon().appendQueryParameter("source", "https://untrusted").build(),
            uri.buildUpon().fragment("extra").build(),
            uri.buildUpon().authority("other").build(),
            Uri.parse("file:///private/data"),
        )
        for (candidate in invalid) {
            assertThrows(FileNotFoundException::class.java) { provider.openFile(candidate, "r") }
        }
    }

    private fun song() = SongItem(
        id = 42, name = "Song", artist = "Artist", album = "Album",
        albumId = 1, durationMs = 1_000, coverUrl = "https://covers/song.jpg",
    )
}
