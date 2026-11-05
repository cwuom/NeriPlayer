package moe.ouom.neriplayer.data.local.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.testing.ManualInstrumentedTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ManualInstrumentedTest
class DownloadedFlacStagingRegressionTest {
    @Test
    fun rawFlacFormalWritePreservesTagsCoverAndAudio() = verifyWrite("raw.flac", false)

    @Test
    fun rawFlacPendingWritePreservesTagsCoverAndAudio() = verifyWrite("raw.flac", true)

    @Test
    fun mp4FlacFormalWritePreservesTagsCoverAndAudio() = verifyWrite("mp4.m4a", false)

    @Test
    fun mp4FlacPendingWritePreservesTagsCoverAndAudio() = verifyWrite("mp4.m4a", true)

    @Test
    fun fragmentedFlacFormalWritePreservesTagsCoverAndAudio() = verifyWrite("fragmented.m4a", false)

    @Test
    fun fragmentedFlacPendingWritePreservesTagsCoverAndAudio() = verifyWrite("fragmented.m4a", true)

    private fun verifyWrite(fixtureSuffix: String, pending: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "flac-staging-${System.nanoTime()}")
        assertTrue(directory.mkdir())
        try {
            val extension = fixtureSuffix.substringAfterLast('.')
            val logicalName = "synthetic.$extension"
            val audio = File(directory, if (pending) "$logicalName.npdl_pending.fixture.pending" else logicalName)
            instrumentation.context.assets.open("metadata/synthetic-hires-$fixtureSuffix").use { input ->
                audio.outputStream().use { output -> input.copyTo(output) }
            }
            val cover = File(directory, "cover.png")
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                cover.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
            val originalBytes = audio.readBytes()
            val rawFlac = extension == "flac"
            val before = audioPayload(audio, rawFlac)
            val audioUri = Uri.fromFile(audio).toString()
            val entry = ManagedDownloadStorage.StoredEntry(
                name = audio.name,
                reference = audioUri,
                mediaUri = audioUri,
                localFilePath = audio.absolutePath,
                sizeBytes = audio.length(),
                lastModifiedMs = audio.lastModified()
            )
            assertEquals(logicalName, entry.logicalName)
            val outcome = runBlocking {
                DownloadedAudioTagWriter.write(
                    context = context,
                    audio = entry,
                    song = SongItem(
                        id = 42_368_044_869L,
                        name = "合成 Hi-Res 标题",
                        artist = "合成歌手",
                        album = "Bilibili",
                        albumId = 0L,
                        durationMs = 1_000L,
                        coverUrl = null,
                        mediaUri = "https://example.invalid/synthetic-hires"
                    ),
                    sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(
                        coverReference = Uri.fromFile(cover).toString(),
                        expectedCover = true,
                        expectedLyric = true,
                        expectedTranslatedLyric = true,
                        expectedRomanizedLyric = true,
                        lyricContent = ORIGINAL,
                        translatedLyricContent = TRANSLATION,
                        romanizedLyricContent = ROMANIZATION
                    ),
                    standardizedLyricEmbeddingEnabled = true
                )
            }
            Log.i("FlacStagingProbe", "fixture=$fixtureSuffix pending=$pending outcome=$outcome before=$before")
            if (outcome != DownloadedAudioTagWriteOutcome.SUCCESS) {
                assertArrayEquals("failed write changed the original audio", originalBytes, audio.readBytes())
            }
            assertEquals("audio samples changed after metadata write", before, audioPayload(audio, rawFlac))
            assertEquals("fixture=$fixtureSuffix pending=$pending", DownloadedAudioTagWriteOutcome.SUCCESS, outcome)

            val readback = File(directory, "readback.$extension")
            audio.copyTo(readback)
            ParcelFileDescriptor.open(readback, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), false)
                assertNotNull("TagLib could not read the tagged fixture", metadata)
                val properties = requireNotNull(metadata).propertyMap
                assertEquals("合成 Hi-Res 标题", properties["TITLE"]?.firstOrNull())
                assertEquals("合成歌手", properties["ARTIST"]?.firstOrNull())
                assertEquals(ORIGINAL, properties["NERI_LYRICS_ORIGINAL"]?.firstOrNull())
                assertEquals(TRANSLATION, properties["NERI_LYRICS_TRANSLATED"]?.firstOrNull())
                assertEquals(ROMANIZATION, properties["NERI_LYRICS_ROMANIZED"]?.firstOrNull())
                assertTrue("标准 LYRICS 标签必须包含歌词", properties["LYRICS"]?.firstOrNull()?.isNotBlank() == true)
                assertTrue(
                    TagLib.getPictures(descriptor.dup().detachFd()).any { picture ->
                        picture.data.contentEquals(cover.readBytes())
                    }
                )
            }
        } finally {
            assertTrue("could not remove the isolated fixture directory", directory.deleteRecursively())
        }
    }

    private fun audioPayload(file: File, rawFlac: Boolean): AudioPayload {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            // raw FLAC 的系统 extractor 可直接输出 PCM，MP4 的 stsd 与 STREAMINFO 采样率字段不同
            assertTrue(if (rawFlac) mime == "audio/raw" || mime == "audio/flac" else mime == "audio/flac")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            assertTrue(if (rawFlac) sampleRate == 96_000 else sampleRate == 48_000 || sampleRate == 96_000)
            assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else null
            val codecConfiguration = (0..2).map { index ->
                format.getByteBuffer("csd-$index")?.duplicate()?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }.toList()
                }
            }
            extractor.selectTrack(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var frames = 0
            var bytes = 0L
            val timestamps = mutableListOf<Long>()
            while (true) {
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                buffer.position(0)
                buffer.limit(count)
                digest.update(buffer)
                timestamps += extractor.sampleTime
                bytes += count
                frames++
                assertTrue("fixture has an unexpected number of packets", frames < 10_000)
                extractor.advance()
            }
            assertTrue(frames > 0)
            assertTrue(bytes > 0L)
            return AudioPayload(mime, sampleRate, pcmEncoding, codecConfiguration, frames, bytes,
                timestamps, digest.digest().toList(), if (rawFlac) rawFlacEncodingDigest(file) else null)
        } finally {
            extractor.release()
        }
    }

    private fun rawFlacEncodingDigest(file: File): List<Byte> {
        val content = file.readBytes()
        assertEquals("fLaC", content.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val digest = MessageDigest.getInstance("SHA-256")
        var streamInfoFound = false
        var offset = 4
        do {
            assertTrue(offset + 4 <= content.size)
            val header = content[offset].toInt() and 0xff
            val size = content[offset + 1].toInt().and(0xff).shl(16) or
                content[offset + 2].toInt().and(0xff).shl(8) or content[offset + 3].toInt().and(0xff)
            assertTrue(offset + 4 + size <= content.size)
            if (header and 0x7f == 0) {
                assertTrue(!streamInfoFound)
                assertEquals(34, size)
                val info = content.copyOfRange(offset + 4, offset + 4 + size)
                val sampleRate = info[10].toInt().and(0xff).shl(12) or
                    info[11].toInt().and(0xff).shl(4) or info[12].toInt().and(0xf0).ushr(4)
                val channels = info[12].toInt().and(0x0e).ushr(1) + 1
                val bitDepth = (info[12].toInt().and(1).shl(4) or info[13].toInt().and(0xf0).ushr(4)) + 1
                assertEquals(96_000, sampleRate)
                assertEquals(2, channels)
                assertEquals(24, bitDepth)
                digest.update(info)
                streamInfoFound = true
            }
            offset += 4 + size
            assertTrue(offset <= content.size)
        } while (header and 0x80 == 0)
        assertTrue(streamInfoFound && offset < content.size)
        digest.update(content, offset, content.size - offset)
        return digest.digest().toList()
    }

    private data class AudioPayload(
        val mime: String?,
        val sampleRate: Int,
        val pcmEncoding: Int?,
        val codecConfiguration: List<List<Byte>?>,
        val frames: Int,
        val bytes: Long,
        val timestamps: List<Long>,
        val digest: List<Byte>,
        val rawFlacEncodingDigest: List<Byte>?
    )

    private companion object {
        const val ORIGINAL = "[00:00.00]合成原文"
        const val TRANSLATION = "[00:00.00]synthetic translation"
        const val ROMANIZATION = "[00:00.00]he cheng yuan wen"
    }
}
