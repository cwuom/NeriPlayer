package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TagLibM4aWriteProbeTest {
    @Test
    fun probeM4aMetadataAndCoverWrite() {
        val arguments = InstrumentationRegistry.getArguments()
        val sourcePath = arguments.getString("samplePath")
        val coverPath = arguments.getString("coverPath")
        assumeTrue("samplePath is required", !sourcePath.isNullOrBlank())
        assumeTrue("coverPath is required", !coverPath.isNullOrBlank())
        val source = File(requireNotNull(sourcePath))
        val cover = File(requireNotNull(coverPath))
        assumeTrue("sample file is missing", source.isFile)
        assumeTrue("cover file is missing", cover.isFile)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "taglib-m4a-probe.m4a")
        source.copyTo(work, overwrite = true)
        val coverBytes = cover.readBytes()
        try {
            val before = openMetadata(work)
            Log.e(TAG, "before properties=${before?.first?.keys?.sorted()} pictures=${before?.second?.size}")
            val properties = before?.first?.let { source ->
                hashMapOf<String, Array<String>>().apply {
                    source.forEach { (key, values) -> put(key, values.copyOf()) }
                }
            }?.apply {
                put("TITLE", arrayOf("Probe title"))
                put("ARTIST", arrayOf("Probe artist"))
                put("ALBUM", arrayOf("Probe album"))
                put("ALBUMARTIST", arrayOf("Probe artist"))
                put("TRACKNUMBER", arrayOf("1"))
                put("LYRICS", arrayOf("[00:01.00]original\n[00:01.00]translation"))
                put("DESCRIPTION", arrayOf("[00:01.00]original\n[00:01.00]translation"))
                put("NERI_LYRICS_ORIGINAL", arrayOf("[00:01.00]original"))
                put("LYRICS:TRANSLATION", arrayOf("[00:01.00]translation"))
                put("LYRICS_TRANSLATED", arrayOf("[00:01.00]translation"))
                put("NERI_LYRICS_TRANSLATED", arrayOf("[00:01.00]translation"))
                put("NERI_STABLE_KEY", arrayOf("probe-key"))
                put("NERI_MEDIA_URI", arrayOf("file:///probe.m4a"))
                put("NERI_SOURCE", arrayOf("BILIBILI"))
                put("COMMENT", arrayOf("{\"app\":\"NeriPlayer\"}"))
            }
            val saveProperties = properties?.let {
                ParcelFileDescriptor.open(work, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                    TagLib.savePropertyMap(descriptor.dup().detachFd(), it)
                }
            }
            val afterProperties = openMetadata(work)?.first
            Log.e(
                TAG,
                "savePropertyMap=$saveProperties afterProperties=${afterProperties?.keys?.sorted()} " +
                    "lyrics=${afterProperties?.get("LYRICS")?.firstOrNull()} " +
                    "translation=${afterProperties?.get("LYRICS:TRANSLATION")?.firstOrNull()}"
            )
            val picture = Picture(
                data = coverBytes,
                description = "",
                pictureType = "Front Cover",
                mimeType = "image/jpeg"
            )
            val savePictures = ParcelFileDescriptor.open(
                work,
                ParcelFileDescriptor.MODE_READ_WRITE
            ).use { descriptor ->
                TagLib.savePictures(descriptor.dup().detachFd(), arrayOf(picture))
            }
            val afterPictures = openMetadata(work)?.second.orEmpty()
            Log.e(
                TAG,
                "savePictures=$savePictures afterPictures=${afterPictures.size} " +
                    "bytes=${afterPictures.firstOrNull()?.data?.size} " +
                    "mime=${afterPictures.firstOrNull()?.mimeType}"
            )
            assertTrue("TagLib.savePictures returned false", savePictures)
            assertTrue(
                "saved cover was not readable",
                afterPictures.any { it.data.contentEquals(coverBytes) }
            )
        } finally {
            work.delete()
        }
    }

    @Test
    fun probeLocalMediaSupportM4aWrite() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val sourcePath = arguments.getString("samplePath")
        val coverPath = arguments.getString("coverPath")
        assumeTrue("samplePath is required", !sourcePath.isNullOrBlank())
        assumeTrue("coverPath is required", !coverPath.isNullOrBlank())
        val source = File(requireNotNull(sourcePath))
        val cover = File(requireNotNull(coverPath))
        assumeTrue("sample file is missing", source.isFile)
        assumeTrue("cover file is missing", cover.isFile)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "local-media-m4a-probe.m4a")
        source.copyTo(work, overwrite = true)
        try {
            val song = SongItem(
                id = 42L,
                name = "Probe title",
                artist = "Probe artist",
                album = "Probe album",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = cover.toURI().toString(),
                mediaUri = work.toURI().toString(),
                matchedLyric = "[00:01.00]original",
                matchedTranslatedLyric = "[00:01.00]translation",
                localFileName = work.name,
                localFilePath = work.absolutePath
            )
            val outcome = LocalMediaSupport.writeEditableMetadata(
                context = context,
                song = song,
                coverReference = cover.toURI().toString(),
                writeCover = true,
                writeLyrics = true
            )
            Log.e(TAG, "LocalMediaSupport.writeEditableMetadata outcome=$outcome")
            assertTrue("application metadata write failed: $outcome", outcome.name == "SUCCESS")
        } finally {
            work.delete()
        }
    }

    @Test
    fun probeLocalMediaSupportM4aCoverWritePreservesEmbeddedLyrics() = runBlocking {
        val source = requiredProbeSource()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "local-media-m4a-lyrics-preserve-probe.m4a")
        val firstCover = File(context.cacheDir, "taglib-m4a-lyrics-first-cover.jpg")
        val secondCover = File(context.cacheDir, "taglib-m4a-lyrics-second-cover.jpg")
        source.copyTo(work, overwrite = true)
        val firstBytes = writeJpegCover(firstCover, Color.rgb(182, 72, 88))
        val secondBytes = writeJpegCover(secondCover, Color.rgb(48, 145, 198))
        val embeddedLyrics = "[00:01.00]embedded lyrics"
        val embeddedTranslation = "[00:01.00]embedded translation"
        try {
            val song = probeSong(work).copy(
                matchedLyric = embeddedLyrics,
                matchedTranslatedLyric = embeddedTranslation
            )
            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = firstCover.toURI().toString(),
                    writeCover = true,
                    writeLyrics = true
                )
            )
            assertArrayEquals(firstBytes, openMetadata(work)?.second?.singleOrNull()?.data)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song.copy(
                        matchedLyric = "[00:02.00]transient matched lyrics",
                        matchedTranslatedLyric = "[00:02.00]transient matched translation"
                    ),
                    coverReference = secondCover.toURI().toString(),
                    writeCover = true,
                    writeLyrics = false
                )
            )

            val metadata = openMetadata(work)
            assertArrayEquals(secondBytes, metadata?.second?.singleOrNull()?.data)
            assertArrayEquals(
                arrayOf(embeddedLyrics),
                metadata?.first?.get("NERI_LYRICS_ORIGINAL")
            )
            val expectedExternalLyrics = "$embeddedLyrics\n$embeddedTranslation"
            assertTrue(
                metadata?.first?.get("LYRICS")?.any { it == expectedExternalLyrics } == true ||
                    metadata?.first?.get("DESCRIPTION")?.any {
                        it == expectedExternalLyrics
                    } == true
            )
        } finally {
            work.delete()
            firstCover.delete()
            secondCover.delete()
        }
    }

    @Test
    fun probeLocalMediaSupportM4aRepeatedCoverWritesAndClear() = runBlocking {
        assumeTrue("WebP probe needs Android 11+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val source = requiredProbeSource()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "local-media-m4a-repeated-cover-probe.m4a")
        val firstCover = File(context.cacheDir, "taglib-m4a-first-cover.jpg")
        val secondCover = File(context.cacheDir, "taglib-m4a-second-cover.jpg")
        val gifCover = File(context.cacheDir, "taglib-m4a-cover.gif")
        val webpCover = File(context.cacheDir, "taglib-m4a-cover.webp")
        source.copyTo(work, overwrite = true)
        val firstBytes = writeJpegCover(firstCover, Color.rgb(210, 60, 72))
        val secondBytes = writeJpegCover(secondCover, Color.rgb(55, 110, 210))
        gifCover.writeBytes(SINGLE_PIXEL_GIF)
        val webpBytes = writeWebpCover(webpCover, Color.rgb(112, 92, 218))
        try {
            val song = probeSong(work)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = firstCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(firstBytes, openMetadata(work)?.second?.singleOrNull()?.data)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = secondCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(secondBytes, openMetadata(work)?.second?.singleOrNull()?.data)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = firstCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(firstBytes, openMetadata(work)?.second?.singleOrNull()?.data)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = gifCover.toURI().toString(),
                    writeCover = true
                )
            )
            val normalizedGifCover = openMetadata(work)?.second?.singleOrNull()
            assertTrue(normalizedGifCover?.data?.isJpeg() == true)
            assertEquals("image/jpeg", normalizedGifCover?.mimeType)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = webpCover.toURI().toString(),
                    writeCover = true
                )
            )
            val normalizedWebpCover = openMetadata(work)?.second?.singleOrNull()
            assertTrue(webpBytes.isWebp())
            assertTrue(normalizedWebpCover?.data?.isJpeg() == true)
            assertEquals("image/jpeg", normalizedWebpCover?.mimeType)

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = null,
                    writeCover = true
                )
            )
            assertTrue(openMetadata(work)?.second?.isEmpty() == true)
        } finally {
            work.delete()
            firstCover.delete()
            secondCover.delete()
            gifCover.delete()
            webpCover.delete()
        }
    }

    @Test
    fun probeLocalMediaSupportM4aContentUriWrite() = runBlocking {
        assumeTrue("MediaStore test needs Android 10+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val source = requiredProbeSource()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val displayName = "neriplayer-taglib-probe-${UUID.randomUUID()}.m4a"
        val sourceUri = resolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/NeriPlayerTagLibProbe")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: error("Unable to create MediaStore probe entry")
        val firstCover = File(context.cacheDir, "taglib-m4a-content-first-cover.jpg")
        val secondCover = File(context.cacheDir, "taglib-m4a-content-second-cover.jpg")
        val firstBytes = writeJpegCover(firstCover, Color.rgb(45, 175, 110))
        val secondBytes = writeJpegCover(secondCover, Color.rgb(180, 85, 205))
        try {
            resolver.openOutputStream(sourceUri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to populate MediaStore probe entry")
            resolver.update(
                sourceUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )

            val song = SongItem(
                id = 43L,
                name = "Content probe title",
                artist = "Content probe artist",
                album = "Content probe album",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = null,
                mediaUri = sourceUri.toString(),
                localFileName = displayName
            )

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = firstCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(
                firstBytes,
                openMetadata(resolver, sourceUri)?.second?.singleOrNull()?.data
            )

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = secondCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(
                secondBytes,
                openMetadata(resolver, sourceUri)?.second?.singleOrNull()?.data
            )

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = firstCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(
                firstBytes,
                openMetadata(resolver, sourceUri)?.second?.singleOrNull()?.data
            )

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = null,
                    writeCover = true
                )
            )
            assertTrue(openMetadata(resolver, sourceUri)?.second?.isEmpty() == true)
        } finally {
            resolver.delete(sourceUri, null, null)
            firstCover.delete()
            secondCover.delete()
        }
    }

    @Test
    fun probeLocalMediaSupportM4aStagedContentWrite() = runBlocking {
        val source = requiredProbeSource()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val sourceUri = StagedMetadataTestProvider.CONTENT_URI
        val firstCover = File(context.cacheDir, "taglib-m4a-staged-first-cover.jpg")
        val secondCover = File(context.cacheDir, "taglib-m4a-staged-second-cover.jpg")
        val firstBytes = writeJpegCover(firstCover, Color.rgb(220, 125, 45))
        val secondBytes = writeJpegCover(secondCover, Color.rgb(40, 155, 205))
        resolver.delete(sourceUri, null, null)
        try {
            resolver.openOutputStream(sourceUri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to populate staged provider probe entry")
            val song = SongItem(
                id = 44L,
                name = "Staged content probe title",
                artist = "Staged content probe artist",
                album = "Staged content probe album",
                albumId = 0L,
                durationMs = 180_000L,
                coverUrl = null,
                mediaUri = sourceUri.toString(),
                localFileName = "staged-content-probe.m4a"
            )

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = firstCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(
                firstBytes,
                openMetadata(resolver, sourceUri)?.second?.singleOrNull()?.data
            )

            assertWriteSucceeded(
                LocalMediaSupport.writeEditableMetadata(
                    context = context,
                    song = song,
                    coverReference = secondCover.toURI().toString(),
                    writeCover = true
                )
            )
            assertArrayEquals(
                secondBytes,
                openMetadata(resolver, sourceUri)?.second?.singleOrNull()?.data
            )
        } finally {
            resolver.delete(sourceUri, null, null)
            firstCover.delete()
            secondCover.delete()
        }
    }

    private fun requiredProbeSource(): File {
        val sourcePath = InstrumentationRegistry.getArguments().getString("samplePath")
        assumeTrue("samplePath is required", !sourcePath.isNullOrBlank())
        val source = File(requireNotNull(sourcePath))
        assumeTrue("sample file is missing", source.isFile)
        return source
    }

    private fun probeSong(work: File): SongItem {
        return SongItem(
            id = 42L,
            name = "Probe title",
            artist = "Probe artist",
            album = "Probe album",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = work.toURI().toString(),
            localFileName = work.name,
            localFilePath = work.absolutePath
        )
    }

    private fun writeJpegCover(target: File, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            FileOutputStream(target).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
            target.readBytes()
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeWebpCover(target: File, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            FileOutputStream(target).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, output))
            }
            target.readBytes()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertWriteSucceeded(outcome: LocalMediaMetadataWriteOutcome) {
        assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS, outcome)
    }

    private fun openMetadata(file: File): Pair<Map<String, Array<String>>, Array<Picture>>? {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), true)
                ?: return@use null
            metadata.propertyMap to metadata.pictures
        }
    }

    private fun openMetadata(
        resolver: ContentResolver,
        uri: Uri
    ): Pair<Map<String, Array<String>>, Array<Picture>>? {
        return resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val metadata = TagLib.getMetadata(descriptor.dup().detachFd(), true)
                ?: return@use null
            metadata.propertyMap to metadata.pictures
        }
    }

    private fun ByteArray.isJpeg(): Boolean {
        return size >= 3 &&
            this[0] == 0xFF.toByte() &&
            this[1] == 0xD8.toByte() &&
            this[2] == 0xFF.toByte()
    }

    private fun ByteArray.isWebp(): Boolean {
        return size >= 12 &&
            this[0] == 0x52.toByte() &&
            this[1] == 0x49.toByte() &&
            this[2] == 0x46.toByte() &&
            this[3] == 0x46.toByte() &&
            this[8] == 0x57.toByte() &&
            this[9] == 0x45.toByte() &&
            this[10] == 0x42.toByte() &&
            this[11] == 0x50.toByte()
    }

    private companion object {
        const val TAG = "TagLibM4aProbe"
        val SINGLE_PIXEL_GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
            0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x02, 0x02, 0x4C, 0x01, 0x00,
            0x3B
        )
    }
}
