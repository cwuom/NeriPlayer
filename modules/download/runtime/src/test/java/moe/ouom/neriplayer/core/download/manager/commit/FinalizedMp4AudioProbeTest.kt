package moe.ouom.neriplayer.core.download.manager.commit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinalizedMp4AudioProbeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `provider asset offsets and lengths constrain the parsed audio`() = runTest {
        val fixture = fixtureBytes()
        val file = temporaryFolder.newFile("provider.bin").apply {
            outputStream().use { output ->
                output.write(ByteArray(43) { 0x5a })
                output.write(fixture)
                output.write(ByteArray(27) { 0x7f })
            }
        }
        val provider = AudioAssetProvider(file, 43L, fixture.size.toLong())
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "audio-probe"
            packageName = context.packageName
            applicationInfo = context.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal("audio-probe", provider)

        val durationMs = readFinalizedMp4AudioDuration(context, Uri.parse("content://audio-probe/song"))

        assertEquals(2_000L, durationMs)
        assertTrue(provider.lastDescriptor?.fileDescriptor?.valid() == false)
    }

    @Test
    fun `large media payload is skipped while finding metadata at the end`() = runTest {
        val fixture = fixtureBytes()
        val mdatOffset = boxOffset(fixture, "mdat")
        val moovOffset = boxOffset(fixture, "moov")
        val extraMediaBytes = 32 * 1024 * 1024
        val file = temporaryFolder.newFile("large-media.m4a")
        RandomAccessFile(file, "rw").use { output ->
            output.write(fixture, 0, moovOffset)
            output.seek(mdatOffset.toLong())
            output.writeInt(ByteBuffer.wrap(fixture, mdatOffset, 4).int + extraMediaBytes)
            output.seek(moovOffset.toLong() + extraMediaBytes)
            output.write(fixture, moovOffset, fixture.size - moovOffset)
        }

        val durationMs = readFinalizedMp4AudioDuration(context, Uri.fromFile(file))

        assertEquals(2_000L, durationMs)
        assertTrue(file.length() > 32 * 1024 * 1024)
    }

    @Test
    fun `cancelled probe stops before returning audio metadata`() = runTest {
        val file = temporaryFolder.newFile("cancelled.m4a").apply { writeBytes(fixtureBytes()) }
        var returnedMetadata = false
        val probe = launch {
            currentCoroutineContext().cancel()
            readFinalizedMp4AudioDuration(context, Uri.fromFile(file))
            returnedMetadata = true
        }

        probe.join()

        assertTrue(probe.isCancelled)
        assertFalse(returnedMetadata)
    }

    private fun fixtureBytes(): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/download/finalized-audio/flac-in-mp4.m4a")
    ).use { it.readBytes() }

    private fun boxOffset(bytes: ByteArray, type: String): Int {
        var offset = 0
        while (offset < bytes.size) {
            if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == type) return offset
            offset += ByteBuffer.wrap(bytes, offset, 4).int
        }
        error("fixture box missing: $type")
    }

    private class AudioAssetProvider(
        private val file: File,
        private val offset: Long,
        private val length: Long
    ) : ContentProvider() {
        var lastDescriptor: ParcelFileDescriptor? = null
            private set

        override fun onCreate() = true
        override fun getType(uri: Uri) = "audio/mp4"
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            require(uri.path == "/song" && mode == "r")
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            lastDescriptor = descriptor
            return AssetFileDescriptor(descriptor, offset, length)
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?): Int = 0
    }
}
