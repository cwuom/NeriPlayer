package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.media.curation.LocalCoverBaselineInspector
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalCoverBaselineInspectorInstrumentedTest {
    @Test
    fun readablePrivateAudioWithoutPicturesConfirmsAnEmptyBaseline() = fixture { context, audio ->
        val before = audio.readBytes()
        assertTrue(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song(audio)))
        assertArrayEquals(before, audio.readBytes())
        assertEquals(listOf(audio.name), requireNotNull(audio.parentFile?.list()).toList())
    }

    @Test
    fun embeddedPicturesPreventAnEmptyBaselineWithoutAnAdjacentCover() = fixture { context, audio ->
        val cover = File(audio.parentFile, "replacement.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, runBlocking {
            LocalMediaSupport.writeEditableMetadata(context, song(audio), cover.toURI().toString(),
                writeCover = true, writeLyrics = false)
        })
        requireNotNull(audio.parentFile?.listFiles()).filter { it != audio }.forEach {
            assertTrue(it.deleteRecursively())
        }
        LocalMediaSupport.invalidateSongAssetCaches(song(audio))
        ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertTrue(TagLib.getPictures(it.dup().detachFd()).isNotEmpty())
        }

        assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song(audio)))
    }

    @Test
    fun aBrokenCoverCandidateOrUnreadableContainerRemainsUnknown() = fixture { context, audio ->
        val brokenCover = File(audio.parentFile, "probe.jpg").apply { writeText("invalid image") }
        assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song(audio)))
        assertTrue(brokenCover.delete())
        audio.writeText("invalid audio")
        LocalMediaSupport.invalidateSongAssetCaches(song(audio))

        assertFalse(LocalCoverBaselineInspector.hasConfirmedNoOriginalCover(context, song(audio)))
    }

    private fun fixture(block: (Context, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "cover-baseline-${UUID.randomUUID()}")
        check(directory.mkdir())
        val audio = File(directory, "probe.m4a")
        try {
            instrumentation.context.assets.open("metadata/synthetic-free-first-8.m4a").use { input ->
                audio.outputStream().use(input::copyTo)
            }
            block(context, audio)
        } finally {
            LocalMediaSupport.invalidateSongAssetCaches(song(audio))
            check(directory.deleteRecursively())
        }
    }

    private fun song(audio: File) = SongItem(
        id = 545L, name = "Original title", artist = "Original artist", album = "Album",
        albumId = 0L, durationMs = 1_000L, coverUrl = null,
        mediaUri = audio.toURI().toString(), localFilePath = audio.absolutePath,
        localFileName = audio.name, channelId = "local"
    )
}
