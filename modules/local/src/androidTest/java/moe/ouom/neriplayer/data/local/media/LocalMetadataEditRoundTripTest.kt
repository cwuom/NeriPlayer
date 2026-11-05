package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.metadata.codec.ManagedDownloadedAudioMetadataDecoder
import moe.ouom.neriplayer.data.model.SongItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalMetadataEditRoundTripTest {
    @Test
    fun appOnlyWritebackAndRestoreRepeatWithoutChangingAudioFrames() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "metadata-roundtrip-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        try {
            val audio = File(directory, "song.flac")
            instrumentation.context.assets.open("metadata/synthetic-edit.flac").use { input ->
                audio.outputStream().use { input.copyTo(it) }
            }
            val originalAudio = audioFrames(audio)
            val cover = File(directory, "selected.png")
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val original = SongItem(
                741L, "Original", "Artist", "Fixture", 0L, 250L, null,
                mediaUri = audio.toURI().toString(), localFilePath = audio.absolutePath,
                localFileName = audio.name, originalName = "Original", originalArtist = "Artist",
                originalCoverUrl = "", originalLyric = "[00:00.00]original",
                originalTranslatedLyric = "", originalRomanizedLyric = "",
                matchedLyric = "[00:00.00]original"
            )
            val sidecar = File(directory, "song.flac.npmeta.json")
            sidecar.writeText("""{"restorableMetadata":{"baseline":{"title":"Original","coverReference":""},"overrides":{}}}""")
            repeat(3) { round ->
                val edited = original.copy(
                    customName = "Edited $round", customArtist = "Artist $round",
                    customCoverUrl = cover.toURI().toString(),
                    matchedLyric = "[00:00.00]edited $round", matchedTranslatedLyric = "[00:00.00]translation $round",
                    matchedRomanizedLyric = "[00:00.00]romanization $round", lyricSyncEdited = true
                )
                val beforeAppOnly = audio.readBytes()
                assertTrue(LocalMediaSupport.writeLocalMetadataSidecar(
                    context, edited, writeLyrics = true, coverReference = edited.customCoverUrl
                ))
                assertArrayEquals(beforeAppOnly, audio.readBytes())
                assertEmbedded(audio, title = "Original", hasCover = false)
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, LocalMediaSupport.writeEditableMetadata(
                    context, edited, coverReference = edited.customCoverUrl, writeCover = true, writeLyrics = true
                ))
                assertEmbedded(audio, title = "Edited $round", hasCover = true)
                assertEquals(originalAudio, audioFrames(audio))
                assertEquals("[00:00.00]edited $round", decode(sidecar).matchedLyric)
                assertTrue(LocalMediaSupport.writeLocalMetadataSidecar(
                    context, original, writeLyrics = true, clearCoverReference = true
                ))
                assertEmbedded(audio, title = "Edited $round", hasCover = true)
                assertNull(decode(sidecar).customName)
                assertEquals("", decode(sidecar).matchedTranslatedLyric)
                repeat(2) {
                    assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, LocalMediaSupport.writeEditableMetadata(
                        context, original, coverReference = null, writeCover = true, writeLyrics = true
                    ))
                    assertEmbedded(audio, title = "Original", hasCover = false)
                    assertEquals(originalAudio, audioFrames(audio))
                    val restored = decode(sidecar)
                    assertNull(restored.customName)
                    assertNull(restored.customArtist)
                    assertEquals("[00:00.00]original", restored.matchedLyric)
                    assertEquals("", restored.matchedTranslatedLyric)
                    assertEquals("", restored.matchedRomanizedLyric)
                }
            }
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun decode(sidecar: File) = ManagedDownloadedAudioMetadataDecoder(JSONObject(sidecar.readText())).decode()

    private fun assertEmbedded(audio: File, title: String, hasCover: Boolean) {
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val metadata = requireNotNull(TagLib.getMetadata(descriptor.dup().detachFd(), false))
            assertEquals(title, metadata.propertyMap["TITLE"]?.single())
            assertEquals(hasCover, TagLib.getPictures(descriptor.dup().detachFd()).isNotEmpty())
        }
    }

    private fun audioFrames(audio: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audio.absolutePath)
            assertTrue(extractor.trackCount > 0)
            extractor.selectTrack(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var samples = 0
            while (true) {
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                buffer.position(0)
                buffer.limit(count)
                digest.update(buffer)
                samples++
                if (!extractor.advance()) break
            }
            assertTrue(samples > 0)
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            extractor.release()
        }
    }
}
