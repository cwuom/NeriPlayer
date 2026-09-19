package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kyant.taglib.TagLib
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriter
import moe.ouom.neriplayer.core.download.manager.runtime.validateExistingDownloadedAudio
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.player.download.DownloadIntegrityException
import moe.ouom.neriplayer.core.player.download.verifyDownloadedAudioPayload
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.api.youtube.YouTubePlayableStreamType
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.util.media.NERI_ORIGINAL_LYRICS_METADATA_KEY
import moe.ouom.neriplayer.util.media.NERI_ROMANIZED_LYRICS_METADATA_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedMp3IntegrityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun mp3DownloadRoundTripsAllLyricsAndCover() = runBlocking {
        val audio = createSilentMp3()
        val cover = File.createTempFile("download-cover-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                cover.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                bitmap.recycle()
            }
            val song = song().copy(
                matchedLyric = "[00:00.00]原文\r\n[00:00.20]二行目",
                matchedTranslatedLyric = "[00:00.00]译文\n[00:00.20]第二行",
                matchedRomanizedLyric = "[00:00.00]genbun\n[00:00.20]nigyoume"
            )
            val outcome = DownloadedAudioTagWriter.write(
                context = context,
                audio = entry(audio),
                song = song,
                sidecarReferences = AudioDownloadManager.DownloadedSidecarReferences(
                    coverReference = cover.toURI().toString(), expectedCover = true,
                    expectedLyric = true, expectedTranslatedLyric = true, expectedRomanizedLyric = true,
                    lyricContent = song.matchedLyric,
                    translatedLyricContent = song.matchedTranslatedLyric,
                    romanizedLyricContent = song.matchedRomanizedLyric
                ),
                standardizedLyricEmbeddingEnabled = true
            )
            assertEquals(DownloadedAudioTagWriteOutcome.SUCCESS, outcome)
            ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                val metadata = requireNotNull(TagLib.getMetadata(descriptor.dup().detachFd(), true))
                assertEquals(song.stableKey(), metadata.propertyMap["NERI_STABLE_KEY"]?.single())
                assertEquals(song.matchedLyric, metadata.propertyMap[NERI_ORIGINAL_LYRICS_METADATA_KEY]?.single())
                assertEquals(song.matchedRomanizedLyric, metadata.propertyMap[NERI_ROMANIZED_LYRICS_METADATA_KEY]?.single())
                assertTrue(metadata.pictures.isNotEmpty())
            }
        } finally {
            audio.delete()
            cover.delete()
        }
    }

    @Test
    fun wrongDurationIsRejectedBeforeWritingAnyManagedCopy() = runBlocking {
        val audio = createSilentMp3()
        try {
            val payload = AudioDownloadManager.DownloadedPayloadSummary(
                actualBytes = audio.length(), expectedBytes = audio.length()
            )
            AudioDownloadManager.verifyDownloadedAudioPayload(song(), audio, audio.name, payload)
            assertThrows(IOException::class.java) {
                runBlocking {
                    AudioDownloadManager.verifyDownloadedAudioPayload(
                        song().copy(durationMs = 275_000L), audio, audio.name, payload
                    )
                }
            }
            assertTrue(audio.isFile)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun completeSourceChecksumResolvesBothLoggedCatalogDurationDiscrepancies() = runBlocking {
        for ((frames, catalogDuration) in listOf(7491 to 200869L, 7624 to 197000L)) {
            val audio = createSilentMp3(frames)
            try {
                val track = song().copy(durationMs = catalogDuration)
                val payload = AudioDownloadManager.DownloadedPayloadSummary(audio.length(), audio.length())
                val oldCheck = runCatching {
                    AudioDownloadManager.verifyDownloadedAudioPayload(track, audio, audio.name, payload)
                }.exceptionOrNull() as? DownloadIntegrityException
                assertEquals("DOWNLOAD_INTEGRITY_DURATION_MISMATCH", oldCheck?.errorCode)
                val verified = AudioDownloadManager.verifyDownloadedAudioPayload(
                    track, audio, audio.name, payload, source(audio)
                )
                assertTrue(requireNotNull(verified) in 195000L..200000L)
            } finally {
                audio.delete()
            }
        }
    }

    @Test
    fun corruptChecksumTruncationAndContradictorySourceSizeRemainRejected() = runBlocking {
        val audio = createSilentMp3()
        try {
            val payload = AudioDownloadManager.DownloadedPayloadSummary(audio.length(), audio.length())
            val verifiedSource = source(audio)
            val wrongHash = runCatching {
                AudioDownloadManager.verifyDownloadedAudioPayload(
                    song(), audio, audio.name, payload, verifiedSource.copy(contentMd5 = "0".repeat(32))
                )
            }.exceptionOrNull() as? DownloadIntegrityException
            assertEquals("DOWNLOAD_INTEGRITY_CHECKSUM_MISMATCH", wrongHash?.errorCode)
            val wrongSize = runCatching {
                AudioDownloadManager.verifyDownloadedAudioPayload(
                    song(), audio, audio.name, payload,
                    verifiedSource.copy(contentLength = audio.length() + 417L)
                )
            }.exceptionOrNull() as? DownloadIntegrityException
            assertEquals("DOWNLOAD_INTEGRITY_SIZE_MISMATCH", wrongSize?.errorCode)
            assertTrue(requireNotNull(AudioDownloadManager.verifyDownloadedAudioPayload(
                song(), audio, audio.name, payload, verifiedSource.copy(
                    streamType = YouTubePlayableStreamType.HLS, contentMd5 = null,
                    contentLength = audio.length() + 417L
                )
            )) > 0L)
            audio.writeBytes(audio.readBytes().copyOf(audio.length().toInt() / 2))
            val truncated = runCatching {
                AudioDownloadManager.verifyDownloadedAudioPayload(song(), audio, audio.name, payload, verifiedSource)
            }.exceptionOrNull() as? DownloadIntegrityException
            assertEquals("DOWNLOAD_INTEGRITY_SIZE_MISMATCH", truncated?.errorCode)
            audio.writeText("not an audio payload")
            val unreadable = runCatching {
                AudioDownloadManager.verifyDownloadedAudioPayload(song(), audio, audio.name,
                    AudioDownloadManager.DownloadedPayloadSummary(audio.length(), audio.length()), source(audio))
            }.exceptionOrNull() as? DownloadIntegrityException
            assertEquals("DOWNLOAD_INTEGRITY_AUDIO_UNREADABLE", unreadable?.errorCode)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun exactSourceDurationWithoutChecksumStillRequiresMatchingAudio() = runBlocking {
        val audio = createSilentMp3()
        try {
            val payload = AudioDownloadManager.DownloadedPayloadSummary(audio.length(), audio.length())
            val source = source(audio).copy(contentMd5 = null, durationMs = 1300L)
            assertTrue(requireNotNull(AudioDownloadManager.verifyDownloadedAudioPayload(
                song().copy(durationMs = 200869L), audio, audio.name, payload, source
            )) > 0L)
            val error = runCatching {
                AudioDownloadManager.verifyDownloadedAudioPayload(
                    song(), audio, audio.name, payload, source.copy(durationMs = 200869L)
                )
            }.exceptionOrNull() as? DownloadIntegrityException
            assertEquals("DOWNLOAD_INTEGRITY_DURATION_MISMATCH", error?.errorCode)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun verifiedDurationSurvivesTaggingMetadataRewriteAndRecoveryReadback() = runBlocking {
        val audio = createSilentMp3(7491)
        val directory = File(context.cacheDir, "duration-metadata-${UUID.randomUUID()}").apply { mkdirs() }
        val previousRoot = ManagedDownloadStorage.configuredDirectoryUri()
        val isolatedContext = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File =
                File(directory, type ?: "files").apply { mkdirs() }
        }
        try {
            ManagedDownloadStorage.primeSettings(null, null)
            val track = song().copy(durationMs = 200869L)
            val verifiedDuration = requireNotNull(AudioDownloadManager.verifyDownloadedAudioPayload(
                track, audio, audio.name,
                AudioDownloadManager.DownloadedPayloadSummary(audio.length(), audio.length()), source(audio)
            ))
            val baseline = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = track.stableKey(), durationMs = track.durationMs,
                verifiedAudioDurationMs = verifiedDuration, artifactState = "CORE_COMMITTED", downloadFinalized = false
            )
            assertTrue(ManagedDownloadStorage.saveMetadata(isolatedContext, entry(audio),
                ManagedDownloadStorageJsonCodec.downloadedAudioMetadataToJson(baseline).toString()))
            assertEquals(DownloadedAudioTagWriteOutcome.SUCCESS,
                DownloadedAudioTagWriter.write(isolatedContext, entry(audio), track, null, true))
            val store = DownloadedAudioMetadataStore(1, 0L, "IntegrityTest")
            assertTrue(store.persist(isolatedContext, entry(audio), track,
                downloadFinalized = false, resolveExistingSidecars = false))
            val reloaded = requireNotNull(store.read(isolatedContext, entry(audio)))
            assertEquals(verifiedDuration, reloaded.verifiedAudioDurationMs)
            assertEquals(entry(audio), GlobalDownloadManager.validateExistingDownloadedAudio(
                isolatedContext, track, entry(audio), reloaded
            ))
            assertNull(GlobalDownloadManager.validateExistingDownloadedAudio(
                isolatedContext, track, entry(audio), reloaded.copy(verifiedAudioDurationMs = null)
            ))
        } finally {
            ManagedDownloadStorage.primeSettings(previousRoot, null)
            audio.delete()
            directory.deleteRecursively()
        }
    }

    @Test
    fun existingMp3CommentsSurviveDownloadTagWrite() = runBlocking {
        val cases = listOf(
            listOf(Triple("eng", "", "original comment")),
            listOf(Triple("eng", "", "first"), Triple("chi", "", "second")),
            listOf(Triple("eng", "", "first"), Triple("eng", "COMMENT", "second")),
            listOf(Triple("eng", "", ""), Triple("eng", "DESCRIPTION", "described comment"))
        )
        for ((index, comments) in cases.withIndex()) {
            val audio = createMp3WithComments(comments)
            try {
                val before = ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    requireNotNull(TagLib.getMetadata(it.dup().detachFd(), false)).propertyMap
                }
                val outcome = DownloadedAudioTagWriter.write(context, entry(audio), song(), null, true)
                assertEquals("comment fixture $index",
                    DownloadedAudioTagWriteOutcome.SUCCESS, outcome)
                // 重复执行收尾也不能继续膨胀注释，更不能丢失其中任意一种内容
                repeat(3) {
                    assertEquals(DownloadedAudioTagWriteOutcome.SUCCESS,
                        DownloadedAudioTagWriter.write(context, entry(audio), song(), null, true))
                }
                ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    val after = requireNotNull(TagLib.getMetadata(it.dup().detachFd(), false)).propertyMap
                    before.filter { (key, values) ->
                        key.startsWith("COMMENT") && values.any(String::isNotBlank)
                    }.forEach { (key, values) ->
                        assertEquals("fixture $index $key", values.filter(String::isNotBlank).toSet(),
                            after[key].orEmpty().filter(String::isNotBlank).toSet())
                    }
                    assertTrue(after["COMMENT"].orEmpty().size <= before["COMMENT"].orEmpty().size + 1)
                }
            } finally {
                audio.delete()
            }
        }
    }

    @Test
    fun localMetadataEditPreservesDescribedCommentsBesideAnEmptyComment() = runBlocking {
        val audio = createMp3WithComments(listOf(
            Triple("eng", "", ""), Triple("eng", "DESCRIPTION", "original described comment")
        ))
        try {
            repeat(3) { index ->
                val edited = song().copy(name = "edited-$index", mediaUri = audio.toURI().toString(),
                    localFilePath = audio.absolutePath, localFileName = audio.name)
                assertEquals(LocalMediaMetadataWriteOutcome.SUCCESS,
                    LocalMediaSupport.writeEditableMetadata(context, edited, persistCompanionSidecars = false))
                ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    val properties = requireNotNull(TagLib.getMetadata(it.dup().detachFd(), false)).propertyMap
                    assertEquals("original described comment", properties["COMMENT:DESCRIPTION"]?.single())
                }
            }
        } finally {
            audio.delete()
        }
    }

    @Test
    fun readableButWrongSongOrDurationCannotBeReusedAsComplete() = runBlocking {
        val audio = createSilentMp3()
        try {
            val song = song()
            val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = song.stableKey(), artifactState = "CORE_COMMITTED", downloadFinalized = false
            )
            assertEquals(entry(audio), GlobalDownloadManager.validateExistingDownloadedAudio(
                context, song, entry(audio), metadata
            ))
            assertNull(GlobalDownloadManager.validateExistingDownloadedAudio(
                context, song.copy(durationMs = 275_000L), entry(audio), metadata
            ))
            assertNull(GlobalDownloadManager.validateExistingDownloadedAudio(
                context, song, entry(audio), metadata.copy(stableKey = "other|netease|", downloadFinalized = true)
            ))
            assertTrue(audio.isFile)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun downloadStartCannotReuseRejectedOrExternallyDeletedSnapshotAudio() = runBlocking {
        val audio = createSilentMp3()
        val cache = ManagedDownloadStorage.snapshotCacheStore
        val cacheKey = cache.currentKey(context)
        val previous = cache.cachedSnapshot(context, restorePersisted = false)
        val track = song().copy(durationMs = 275_000L)
        val stored = entry(audio)
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = track.stableKey(), artifactState = "CORE_COMMITTED", downloadFinalized = false
        )
        fun publish(value: ManagedDownloadStorage.DownloadedAudioMetadata) {
            cache.putSnapshot(context, cacheKey, ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
                audioEntries = listOf(stored), audioEntriesByLookupKey = mapOf(stored.reference to stored),
                audioEntriesByStableKey = mapOf(track.stableKey() to listOf(stored)),
                metadataByAudioName = mapOf(stored.name to value)
            ))
        }
        try {
            publish(metadata)
            assertNull(GlobalDownloadManager.validateExistingDownloadedAudio(context, track, stored, metadata))
            assertFalse(AudioDownloadManager.hasFastCachedManagedDownloadForStart(context, track))
            publish(metadata.copy(verifiedAudioDurationMs = 1306L))
            assertTrue(AudioDownloadManager.hasFastCachedManagedDownloadForStart(context, track))
            publish(metadata.copy(downloadFinalized = true, artifactState = "COMPLETE"))
            assertTrue(audio.delete())
            assertFalse(AudioDownloadManager.hasFastCachedManagedDownloadForStart(context, track))
            assertNull(GlobalDownloadManager.validateExistingDownloadedAudio(
                context, track, stored, metadata.copy(downloadFinalized = true)
            ))
        } finally {
            audio.delete()
            cache.invalidate()
            if (previous != null) cache.putSnapshot(context, cacheKey, previous)
        }
    }

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(), ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(), (value and 0x7f).toByte()
    )

    private fun createMp3WithComments(comments: List<Triple<String, String, String>>): File =
        createSilentMp3().apply {
            val frames = comments.fold(byteArrayOf()) { result, (language, description, text) ->
                val content = byteArrayOf(3) + language.toByteArray() +
                    description.toByteArray() + byteArrayOf(0) + text.toByteArray()
                result + "COMM".toByteArray() + synchsafe(content.size) + byteArrayOf(0, 0) + content
            }
            writeBytes("ID3".toByteArray() + byteArrayOf(4, 0, 0) + synchsafe(frames.size) + frames + readBytes())
        }

    private fun song() = SongItem(
        id = 26503085L, name = "空想少女", artist = "鹿乃", album = "NeteaseAlbum",
        albumId = 0L, durationMs = 1_300L, coverUrl = null,
        sourceStableKey = "26503085|netease|"
    )

    private fun createSilentMp3(frameCount: Int = 50): File = File.createTempFile("download-mp3-", ".mp3", context.cacheDir).apply {
        // MPEG-1 Layer III 128 kbps、44.1 kHz 的静音帧，无需网络音源
        val frame = ByteArray(417)
        byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64).copyInto(frame)
        outputStream().use { output -> repeat(frameCount) { output.write(frame) } }
    }

    private fun source(file: File) = AudioDownloadManager.ResolvedDownloadSource(
        url = "https://example.invalid/audio.mp3", contentLength = file.length(),
        contentMd5 = MessageDigest.getInstance("MD5").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    )

    private fun entry(file: File) = ManagedDownloadStorage.StoredEntry(
        name = file.name, reference = file.toURI().toString(), mediaUri = file.toURI().toString(),
        localFilePath = file.absolutePath, sizeBytes = file.length(), lastModifiedMs = file.lastModified()
    )
}
