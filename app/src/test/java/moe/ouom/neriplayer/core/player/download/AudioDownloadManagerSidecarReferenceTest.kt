package moe.ouom.neriplayer.core.player.download

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.nio.file.Files

class AudioDownloadManagerSidecarReferenceTest {

    @Test
    fun `resolveVisibleDownloadFileName prefers target file name over staging temp file`() {
        assertEquals(
            "netease - artist - song.flac",
            resolveVisibleDownloadFileName(
                "netease - artist - song.flac",
                "netease_-___-_____8601164265291179768.flac.download"
            )
        )
    }

    @Test
    fun `resolveVisibleDownloadFileName falls back to temp file when target is blank`() {
        assertEquals(
            "netease_-___-_____8601164265291179768.flac.download",
            resolveVisibleDownloadFileName(
                "",
                "netease_-___-_____8601164265291179768.flac.download"
            )
        )
    }

    @Test
    fun `mergeDownloadedSidecarReferences keeps earlier files when later stage adds new refs`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/song.lrc"
        )
        val incoming = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/song.jpg"
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(existing, incoming)

        assertEquals("content://covers/song.jpg", merged.coverReference)
        assertEquals("content://lyrics/song.lrc", merged.lyricReference)
        assertEquals(null, merged.translatedLyricReference)
    }

    @Test
    fun `mergeDownloadedSidecarReferences ignores empty updates and keeps translated lyric`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/song.lrc",
            translatedLyricReference = "content://lyrics/song_trans.lrc"
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing,
            AudioDownloadManager.DownloadedSidecarReferences()
        )

        assertEquals(existing, merged)
    }

    @Test
    fun `mergeDownloadedSidecarReferences preserves created ownership for same reference`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/song.jpg",
            createdCover = true
        )
        val incoming = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/song.jpg",
            createdCover = false
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(existing, incoming)

        assertEquals("content://covers/song.jpg", merged.coverReference)
        assertEquals(true, merged.createdCover)
    }

    @Test
    fun `prepared sidecar state and expected cover survive merge`() {
        val prepared = AudioDownloadManager.DownloadedSidecarReferences(
            expectedCover = true,
            expectedLyric = true,
            prepared = true
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = null,
            incoming = prepared
        )

        assertTrue(merged.prepared)
        assertTrue(merged.expectedCover)
        assertTrue(merged.expectedLyric)
        assertTrue(!merged.isEmpty)
    }

    @Test
    fun `retainCreatedOnly keeps only created romanized lyric`() {
        val created = AudioDownloadManager.DownloadedSidecarReferences(
            romanizedLyricReference = "content://lyrics/song_roma.lrc",
            createdRomanizedLyric = true
        )
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            romanizedLyricReference = "content://library/song_roma.lrc",
            createdRomanizedLyric = false
        )

        assertEquals(created, created.retainCreatedOnly())
        assertEquals(
            AudioDownloadManager.DownloadedSidecarReferences(),
            existing.retainCreatedOnly()
        )
    }

    @Test
    fun `mergeDownloadedSidecarReferences uses incoming ownership when reference changes`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/old.jpg",
            createdCover = true
        )
        val incoming = AudioDownloadManager.DownloadedSidecarReferences(
            coverReference = "content://covers/new.jpg",
            createdCover = false
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(existing, incoming)

        assertEquals("content://covers/new.jpg", merged.coverReference)
        assertEquals(false, merged.createdCover)
    }

    @Test
    fun `completed audio reference is consumed once`() {
        val songKey = "song-key"
        AudioDownloadManager.consumeCompletedAudioReference(songKey)
        val storedEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Song.mp3",
            reference = "/downloads/Artist - Song.mp3",
            mediaUri = "file:///downloads/Artist%20-%20Song.mp3",
            localFilePath = "/downloads/Artist - Song.mp3",
            sizeBytes = 1024L,
            lastModifiedMs = 42L
        )

        AudioDownloadManager.rememberCompletedAudioReference(songKey, storedEntry)

        assertEquals(storedEntry, AudioDownloadManager.consumeCompletedAudioReference(songKey))
        assertNull(AudioDownloadManager.consumeCompletedAudioReference(songKey))
    }

    @Test
    fun `prepared artifacts cleanup removes all staged files`() {
        val directory = Files.createTempDirectory("neriplayer-prepared-artifacts").toFile()
        val lyric = directory.resolve("song.lrc").apply { writeText("[00:00.00]hello") }
        val cover = directory.resolve("song.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val artifacts = PreparedDownloadArtifacts(
            songKey = "song-key",
            attemptId = 1L,
            lyric = PreparedTextArtifact(lyric, "lyric-hash", "song.lrc"),
            cover = PreparedBinaryArtifact(cover, cover.length(), "cover-hash", "image/jpeg", "song.jpg")
        )

        artifacts.cleanup()

        assertTrue(!lyric.exists())
        assertTrue(!cover.exists())
        directory.delete()
    }

    @Test
    fun `prepared artifact records final audio target name`() {
        val artifacts = PreparedDownloadArtifacts(
            songKey = "song-key",
            attemptId = 2L,
            audioTargetName = "artist - song.flac"
        )

        assertEquals("artist - song.flac", artifacts.audioTargetName)
        assertTrue(artifacts.audioTargetName!!.substringAfterLast('.') == "flac")
    }

    @Test
    fun `prepared manifest exposes audio target and reference fields`() {
        val payload = JSONObject().apply {
            put("audioTargetName", "artist - song.mp3")
            put("audioReference", "content://downloads/song.mp3")
        }

        assertEquals("artist - song.mp3", payload.optString("audioTargetName"))
        assertEquals("content://downloads/song.mp3", payload.optString("audioReference"))
    }

    @Test
    fun `prepared manifest restores legacy entry with only audio reference`() {
        val directory = Files.createTempDirectory("neriplayer-prepared-manifest").toFile()
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(directory)
        val original = PreparedDownloadArtifacts(
            songKey = "legacy-song",
            attemptId = 3L,
            audioReference = "content://downloads/legacy-song.mp3"
        )

        PreparedDownloadArtifactsStore.persist(context, original)
        val manifest = directory.resolve("download_staging")
            .listFiles()
            .orEmpty()
            .single { it.name.startsWith("npdl_sidecar_manifest_") }
        val legacyPayload = JSONObject(manifest.readText(Charsets.UTF_8)).apply {
            remove("audioTargetName")
            remove("expectedLyric")
            remove("expectedTranslatedLyric")
            remove("expectedRomanizedLyric")
        }
        manifest.writeText(legacyPayload.toString(), Charsets.UTF_8)

        val restored = PreparedDownloadArtifactsStore.restore(context, "legacy-song")

        assertEquals(original.audioReference, restored?.audioReference)
        assertTrue(restored?.audioTargetName == null)
        restored?.cleanup()
        directory.deleteRecursively()
    }
}
