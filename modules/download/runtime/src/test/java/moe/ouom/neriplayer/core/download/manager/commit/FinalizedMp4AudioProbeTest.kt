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
import java.io.IOException
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
    fun `bounded FLAC fallback rejects mixed FLAC and AVC sample entries`() = runTest {
        val file = temporaryFolder.newFile("mixed-flac-avc.mp4").apply {
            writeBytes(fixtureBytes("flac-with-avc.mp4"))
        }

        try {
            readFinalizedMp4AudioDuration(context, Uri.fromFile(file))
            org.junit.Assert.fail("mixed sample entries must be rejected before codec configuration parsing")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("does not support this sample entry"))
        }
    }

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
    fun `provider declared length cannot expose metadata after the asset slice`() = runTest {
        val fixture = fixtureBytes()
        val file = temporaryFolder.newFile("truncated-asset.bin").apply { writeBytes(fixture) }
        val provider = AudioAssetProvider(file, 0L, boxOffset(fixture, "moov").toLong() + 8L)
        registerProvider(provider)

        assertRejected(Uri.parse("content://audio-probe/song"))
        assertTrue(provider.lastDescriptor?.fileDescriptor?.valid() == false)
    }

    @Test
    fun `metadata cannot validate a sample whose chunk offset is past the file`() = runTest {
        val fixture = fixtureBytes()
        ByteBuffer.wrap(fixture, typeOffset(fixture, "stco") + 12, 4).putInt(fixture.size + 1_024)
        val file = temporaryFolder.newFile("invalid-chunk.m4a").apply { writeBytes(fixture) }

        assertRejected(Uri.fromFile(file))
    }

    @Test
    fun `untrusted sample counts cannot request unbounded sample table arrays`() = runTest {
        val fixture = fixtureBytes()
        ByteBuffer.wrap(fixture, typeOffset(fixture, "stsz") + 12, 4).putInt(1_000_001)
        val file = temporaryFolder.newFile("oversized-sample-table.m4a").apply { writeBytes(fixture) }

        assertRejected(Uri.fromFile(file))
    }

    @Test
    fun `codec initialization boxes cannot allocate beyond their sample entry`() = runTest {
        val fixture = fixtureBytes()
        ByteBuffer.wrap(fixture, typeOffset(fixture, "dfLa") - 4, 4).putInt(8 * 1_024 * 1_024)
        val file = temporaryFolder.newFile("invalid-codec-box.m4a").apply { writeBytes(fixture) }

        assertRejected(Uri.fromFile(file))
    }

    @Test
    fun `media bytes nested in movie metadata are not treated as a media data box`() = runTest {
        val fixture = fixtureBytes()
        val mdatOffset = boxOffset(fixture, "mdat")
        val moovOffset = boxOffset(fixture, "moov")
        val mdatSize = ByteBuffer.wrap(fixture, mdatOffset, 4).int
        val payload = fixture.copyOfRange(mdatOffset + 8, mdatOffset + mdatSize)
        ByteBuffer.wrap(fixture, mdatOffset + 4, 4).put("free".toByteArray())
        ByteBuffer.wrap(fixture, moovOffset, 4).putInt(fixture.size - moovOffset + mdatSize)
        ByteBuffer.wrap(fixture, typeOffset(fixture, "stco") + 12, 4).putInt(fixture.size + 8)
        val file = temporaryFolder.newFile("nested-media.m4a")
        file.outputStream().use { output ->
            output.write(fixture)
            output.write(ByteBuffer.allocate(8).putInt(mdatSize).put("mdat".toByteArray()).array())
            output.write(payload)
        }

        assertRejected(Uri.fromFile(file))
    }

    @Test
    fun `an auxiliary container inside movie metadata cannot introduce a media payload`() = runTest {
        val fixture = fixtureBytes()
        val mdatOffset = boxOffset(fixture, "mdat")
        val moovOffset = boxOffset(fixture, "moov")
        val mdatSize = ByteBuffer.wrap(fixture, mdatOffset, 4).int
        val payload = fixture.copyOfRange(mdatOffset + 8, mdatOffset + mdatSize)
        ByteBuffer.wrap(fixture, mdatOffset + 4, 4).put("free".toByteArray())
        ByteBuffer.wrap(fixture, moovOffset, 4).putInt(fixture.size - moovOffset + mdatSize + 8)
        ByteBuffer.wrap(fixture, typeOffset(fixture, "stco") + 12, 4).putInt(fixture.size + 16)
        val file = temporaryFolder.newFile("nested-auxiliary-media.m4a")
        file.outputStream().use { output ->
            output.write(fixture)
            output.write(ByteBuffer.allocate(8).putInt(mdatSize + 8).put("axte".toByteArray()).array())
            output.write(ByteBuffer.allocate(8).putInt(mdatSize).put("mdat".toByteArray()).array())
            output.write(payload)
        }

        assertRejected(Uri.fromFile(file))
    }

    @Test
    fun `auxiliary MP4 boxes cannot hide untrusted sample table counts`() = runTest {
        val fixture = fixtureBytes()
        ByteBuffer.wrap(fixture, typeOffset(fixture, "stsz") + 12, 4).putInt(1_000_001)
        val moovOffset = boxOffset(fixture, "moov")
        val moovSize = fixture.size - moovOffset
        val file = temporaryFolder.newFile("auxiliary-table.m4a")
        file.outputStream().use { output ->
            output.write(fixture, 0, moovOffset)
            output.write(ByteBuffer.allocate(8).putInt(moovSize + 8).put("axte".toByteArray()).array())
            output.write(fixture, moovOffset, moovSize)
        }

        try {
            readFinalizedMp4AudioDuration(context, Uri.fromFile(file))
            org.junit.Assert.fail("untrusted auxiliary sample counts must be rejected before parsing")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("budget"))
        }
    }

    @Test
    fun `oversized user metadata is rejected before parser payload allocation`() = runTest {
        val fixture = fixtureBytes()
        val boxSize = 8 * 1_024 * 1_024
        val file = temporaryFolder.newFile("oversized-user-metadata.m4a")
        RandomAccessFile(file, "rw").use { output ->
            output.write(fixture)
            output.writeInt(boxSize)
            output.writeBytes("udta")
            output.setLength(fixture.size.toLong() + boxSize)
        }

        try {
            readFinalizedMp4AudioDuration(context, Uri.fromFile(file))
            org.junit.Assert.fail("oversized user metadata must be rejected")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("allocation budget"))
        }
    }

    @Test
    fun `sample offsets cannot read movie metadata in place of a missing media payload`() = runTest {
        val fixture = fixtureBytes()
        val mdatOffset = boxOffset(fixture, "mdat")
        val moovOffset = boxOffset(fixture, "moov")
        val file = temporaryFolder.newFile("missing-media.m4a")
        file.outputStream().use { output ->
            output.write(fixture, 0, mdatOffset)
            output.write(ByteBuffer.allocate(8).putInt(8).put("mdat".toByteArray()).array())
            output.write(fixture, moovOffset, fixture.size - moovOffset)
        }

        assertRejected(Uri.fromFile(file))
    }

    @Test
    fun `metadata reads cannot exceed the probe budget`() = runTest {
        val fixture = fixtureBytes()
        val moovOffset = boxOffset(fixture, "moov")
        val metadataBoxSize = 1_024 * 1_024
        val extraMetadataSize = 5 * metadataBoxSize
        val file = temporaryFolder.newFile("oversized-metadata.m4a")
        RandomAccessFile(file, "rw").use { output ->
            output.write(fixture)
            output.seek(moovOffset.toLong())
            output.writeInt(fixture.size - moovOffset + extraMetadataSize)
            output.seek(fixture.size.toLong())
            repeat(5) {
                output.writeInt(metadataBoxSize)
                output.writeBytes("udta")
                output.seek(output.filePointer + metadataBoxSize - 8L)
            }
            output.setLength(output.filePointer)
        }

        try {
            readFinalizedMp4AudioDuration(context, Uri.fromFile(file))
            org.junit.Assert.fail("metadata larger than the probe budget must be rejected")
        } catch (_: IOException) {
            // 超预算属于可恢复的探测失败，不能变成内存分配失败
        }
    }

    @Test
    fun `large media payload is skipped while finding metadata at the end`() = runTest {
        val fixture = fixtureBytes()
        val mdatOffset = boxOffset(fixture, "mdat")
        val extraMediaBytes = 32 * 1024 * 1024
        val file = temporaryFolder.newFile("large-media.m4a")
        RandomAccessFile(file, "rw").use { output ->
            val sampleOffset = ByteBuffer.wrap(fixture, typeOffset(fixture, "stco") + 12, 4).int
            ByteBuffer.wrap(fixture, typeOffset(fixture, "stco") + 12, 4)
                .putInt(sampleOffset + extraMediaBytes)
            output.write(fixture, 0, mdatOffset + 8)
            output.seek(mdatOffset.toLong())
            output.writeInt(ByteBuffer.wrap(fixture, mdatOffset, 4).int + extraMediaBytes)
            output.seek(mdatOffset + 8L + extraMediaBytes)
            output.write(fixture, mdatOffset + 8, fixture.size - mdatOffset - 8)
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

    private fun fixtureBytes(name: String = "flac-in-mp4.m4a"): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/download/finalized-audio/$name")
    ).use { it.readBytes() }

    private suspend fun assertRejected(uri: Uri) {
        try {
            assertEquals(null, readFinalizedMp4AudioDuration(context, uri))
        } catch (_: IOException) {
            // 解析器拒绝结构或样本不完整的容器时，调用方会保留恢复凭据
        }
    }

    private fun registerProvider(provider: ContentProvider) {
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "audio-probe"
            packageName = context.packageName
            applicationInfo = context.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal("audio-probe", provider)
    }

    private fun typeOffset(bytes: ByteArray, type: String): Int = bytes.indices.first { offset ->
        offset + 4 <= bytes.size && String(bytes, offset, 4, Charsets.US_ASCII) == type
    }

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
