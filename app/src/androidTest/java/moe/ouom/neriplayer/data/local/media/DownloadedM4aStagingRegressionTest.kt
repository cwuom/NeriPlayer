package moe.ouom.neriplayer.data.local.media

import android.graphics.Bitmap
import android.graphics.Color
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.ui.viewmodel.buildLocalOriginalSongInfo
import moe.ouom.neriplayer.ui.screen.resolveEditSongLyricsForSave
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.taglib.TagLib
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.*
import org.junit.Test

class DownloadedM4aStagingRegressionTest {
    @Test fun fixtureIsAndroidParseable() = fixture(false) { file ->
        val payload = audioPayload(file)
        assertTrue(payload.first in 900_000L..1_200_000L)
        assertTrue(payload.second.isNotEmpty())
    }

    @Test fun pendingNameUsesStagingBeforeTaglibRead() = verifyDownload(true)
    @Test fun formalNamePreservesAudioPayload() = verifyDownload(false)

    @Test fun pipeProviderPreservesTagsCoverAndAudio() = verifyDownload(false, pipe = true, seedTags = true)
    @Test fun pendingNamePreservesUnknownTagsAndCover() = verifyDownload(true, seedTags = true)

    @Test fun missingCoverFailsWithoutChangingAudio() = verifyCoverFailure(false)
    @Test fun expectedCoverWithoutReferenceFailsWithoutChangingAudio() = verifyCoverFailure(true)

    private fun verifyCoverFailure(missingReference: Boolean) = fixture(true) { file ->
        val original = file.readBytes()
        val uri = file.toURI().toString()
        val outcome = runBlocking {
            DownloadedAudioTagWriter.write(
                InstrumentationRegistry.getInstrumentation().targetContext,
                ManagedDownloadStorage.StoredEntry(file.name, uri, uri, file.absolutePath, file.length(), file.lastModified()),
                SongItem(id = 5L, name = "failed title", artist = "artist", album = "album", albumId = 0L,
                    durationMs = 1000L, coverUrl = null, mediaUri = uri),
                AudioDownloadManager.DownloadedSidecarReferences(
                    expectedCover = true, coverReference = if (missingReference) null else File(file.parentFile, "missing.png").toURI().toString()
                ), true
            )
        }
        assertEquals(DownloadedAudioTagWriteOutcome.FAILED, outcome)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun pipePublicationFailureRestoresExactOriginalAudio() = fixture(false) { file ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = StagedMetadataTestProvider.CONTENT_URI.buildUpon().appendQueryParameter("pipe", "true").build()
        val original = file.readBytes()
        context.contentResolver.openOutputStream(provider, "wt")!!.use { it.write(original) }
        try {
            context.contentResolver.call(provider, "failNextWrite", null, null)
            val outcome = runBlocking {
                DownloadedAudioTagWriter.write(context,
                    ManagedDownloadStorage.StoredEntry(file.name, provider.toString(), provider.toString(), null, file.length(), file.lastModified()),
                    SongItem(id = 5L, name = "failed title", artist = "artist", album = "album", albumId = 0L,
                        durationMs = 1000L, coverUrl = null, mediaUri = provider.toString()), null, true)
            }
            assertEquals(DownloadedAudioTagWriteOutcome.FAILED, outcome)
            assertArrayEquals(original, context.contentResolver.openInputStream(provider)!!.use { it.readBytes() })
        } finally { context.contentResolver.delete(provider, null, null) }
    }

    @Test fun editRestoreSavePreservesAllLyricVariantsCoverAndAudio() = fixture(false) { file ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = audioPayload(file)
        val covers = listOf(Color.RED, Color.GREEN).mapIndexed { index, color ->
            File(file.parentFile, "lifecycle-$index.png").also { cover ->
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(color)
                cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            }
        }
        val original = SongItem(id = 6L, name = "original title", artist = "original artist", album = "album", albumId = 0L,
            durationMs = 1000L, coverUrl = covers[0].toURI().toString(), mediaUri = file.toURI().toString(),
            localFilePath = file.absolutePath, localFileName = file.name,
            originalName = "original title", originalArtist = "original artist", originalCoverUrl = covers[0].toURI().toString(),
            originalLyric = "[00:00.00]original", originalTranslatedLyric = "[00:00.00]translated",
            originalRomanizedLyric = "[00:00.00]romanized")
        val edited = original.copy(customName = "edited title", customArtist = "edited artist", customCoverUrl = covers[1].toURI().toString(),
            matchedLyric = "[00:00.00]edited", matchedTranslatedLyric = "[00:00.00]edited translated", matchedRomanizedLyric = "[00:00.00]edited romanized")
        fun save(song: SongItem, cover: String?) {
            assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, runBlocking {
                LocalMediaSupport.writeEditableMetadata(context, song, cover, writeCover = true, writeLyrics = true)
            })
        }
        save(edited, edited.customCoverUrl)
        val baseline = buildLocalOriginalSongInfo(edited)
        val lyrics = requireNotNull(resolveEditSongLyricsForSave(null, false, true, baseline.lyric, baseline.translatedLyric, baseline.romanizedLyric))
        val restored = edited.copy(customName = baseline.name, customArtist = baseline.artist, customCoverUrl = baseline.coverUrl,
            matchedLyric = lyrics.lyric, matchedTranslatedLyric = lyrics.translatedLyric, matchedRomanizedLyric = lyrics.romanizedLyric)
        save(restored, baseline.coverUrl)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val properties = requireNotNull(TagLib.getMetadata(fd.dup().detachFd(), false)).propertyMap
            assertEquals("original title", properties["TITLE"]?.firstOrNull())
            assertEquals("original artist", properties["ARTIST"]?.firstOrNull())
            assertTrue(properties.values.any { it.contains("[00:00.00]original") })
            assertTrue(properties.values.any { it.contains("[00:00.00]translated") })
            assertTrue(properties.values.any { it.contains("[00:00.00]romanized") })
            assertTrue(TagLib.getPictures(fd.dup().detachFd()).any { it.data.contentEquals(covers[0].readBytes()) })
        }
        assertEquals(payload, audioPayload(file))
    }

    private fun verifyDownload(pending: Boolean, pipe: Boolean = false, seedTags: Boolean = false) = fixture(pending) { file ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (seedTags) {
            val seed = File(file.parentFile, "seed.m4a")
            file.copyTo(seed)
            ParcelFileDescriptor.open(seed, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                val props = requireNotNull(TagLib.getMetadata(fd.dup().detachFd(), false)).propertyMap
                props["TRACKNUMBER"] = arrayOf("7")
                props["COMMENT"] = arrayOf("keep comment")
                props["NERI_TEST_UNKNOWN"] = arrayOf("keep unknown")
                assertTrue(TagLib.savePropertyMap(fd.dup().detachFd(), props))
            }
            seed.copyTo(file, overwrite = true)
        }
        val cover = File(file.parentFile, "cover.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val before = audioPayload(file)
        val provider = StagedMetadataTestProvider.CONTENT_URI.buildUpon().appendQueryParameter("pipe", "true").build()
        if (pipe) context.contentResolver.openOutputStream(provider, "wt")!!.use { it.write(file.readBytes()) }
        val uri = if (pipe) provider.toString() else file.toURI().toString()
        val outcome = runBlocking {
            DownloadedAudioTagWriter.write(
                InstrumentationRegistry.getInstrumentation().targetContext,
                ManagedDownloadStorage.StoredEntry(file.name, uri, uri, if (pipe) null else file.absolutePath, file.length(), file.lastModified()),
                SongItem(id = 123L, name = "stage title", artist = "stage artist", album = "stage album",
                    albumId = 0L, durationMs = 1000L, coverUrl = null, mediaUri = uri,
                    matchedLyric = "[00:00.00]original", matchedTranslatedLyric = "[00:00.00]translation",
                    matchedRomanizedLyric = "[00:00.00]romanized"),
                AudioDownloadManager.DownloadedSidecarReferences(
                    coverReference = cover.toURI().toString(), expectedCover = true,
                    expectedLyric = true, expectedTranslatedLyric = true, expectedRomanizedLyric = true,
                    lyricContent = "[00:00.00]new original",
                    translatedLyricContent = "[00:00.00]new translation",
                    romanizedLyricContent = "[00:00.00]new romanized"
                ), true
            )
        }
        assertEquals(DownloadedAudioTagWriteOutcome.SUCCESS, outcome)
        if (pipe) {
            context.contentResolver.openInputStream(provider)!!.use { input -> file.outputStream().use(input::copyTo) }
            context.contentResolver.delete(provider, null, null)
        }
        assertEquals(before, audioPayload(file))
        val readable = File(file.parentFile, "readback.m4a")
        file.copyTo(readable, overwrite = true)
        val properties = ParcelFileDescriptor.open(readable, ParcelFileDescriptor.MODE_READ_ONLY).use {
            requireNotNull(TagLib.getMetadata(it.dup().detachFd(), false)).propertyMap
        }
        assertEquals("stage title", properties["TITLE"]?.firstOrNull())
        assertEquals("stage artist", properties["ARTIST"]?.firstOrNull())
        assertTrue(properties.values.any { values -> values.any { it.contains("new original") } })
        assertTrue(properties.values.any { values -> values.any { it.contains("new translation") } })
        assertTrue(properties.values.any { values -> values.any { it.contains("new romanized") } })
        ParcelFileDescriptor.open(readable, ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertTrue(TagLib.getPictures(it.dup().detachFd()).any { picture -> picture.data.contentEquals(cover.readBytes()) })
        }
        if (seedTags) {
            assertEquals("7", properties["TRACKNUMBER"]?.firstOrNull())
            assertEquals("keep comment", properties["COMMENT"]?.firstOrNull())
            assertEquals("keep unknown", properties["NERI_TEST_UNKNOWN"]?.firstOrNull())
        }
    }

    private fun fixture(pending: Boolean, block: (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "free8-${System.nanoTime()}").apply { check(mkdir()) }
        val file = File(directory, if (pending) "probe.m4a.npdl_pending.fixture.pending" else "probe.m4a")
        try {
            instrumentation.context.assets.open("metadata/synthetic-free-first-8.m4a").use { input -> file.outputStream().use(input::copyTo) }
            block(file)
        } finally { directory.deleteRecursively() }
    }

    private fun audioPayload(file: File): Pair<Long, String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm", format.getString(MediaFormat.KEY_MIME))
            extractor.selectTrack(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(65536)
            var frames = 0
            while (true) {
                buffer.clear()
                val count = extractor.readSampleData(buffer, 0)
                if (count < 0) break
                val bytes = ByteArray(count)
                buffer.position(0)
                buffer.get(bytes)
                digest.update(bytes)
                frames++
                extractor.advance()
            }
            assertTrue(frames > 0)
            return format.getLong(MediaFormat.KEY_DURATION) to digest.digest().joinToString("") { "%02x".format(it) }
        } finally { extractor.release() }
    }
}
