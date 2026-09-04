package moe.ouom.neriplayer.core.player.download

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadManagerSidecarReferenceTest {

    @Test
    fun `cover single flight shares one producer for the same target`() = runBlocking {
        val singleFlight = CoverDownloadSingleFlight<String, String?>()
        val releaseProducer = CompletableDeferred<Unit>()
        val producerCalls = AtomicInteger(0)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("song-key|Song-12345678.jpg") {
                producerCalls.incrementAndGet()
                releaseProducer.await()
                "content://covers/Song-12345678.jpg"
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("song-key|Song-12345678.jpg") {
                producerCalls.incrementAndGet()
                "content://covers/duplicate.jpg"
            }
        }

        releaseProducer.complete(Unit)

        assertEquals("content://covers/Song-12345678.jpg", first.await())
        assertEquals("content://covers/Song-12345678.jpg", second.await())
        assertEquals(1, producerCalls.get())
        assertEquals(0, singleFlight.inFlightCount)
    }

    @Test
    fun `cover single flight releases cancelled owner before the next retry`() = runBlocking {
        val singleFlight = CoverDownloadSingleFlight<String, String?>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            singleFlight.run("song-key|Song-12345678.jpg") {
                awaitCancellation()
            }
        }

        owner.cancelAndJoin()

        assertEquals(0, singleFlight.inFlightCount)
        assertEquals(
            "content://covers/Song-12345678.jpg",
            singleFlight.run("song-key|Song-12345678.jpg") {
                "content://covers/Song-12345678.jpg"
            }
        )
        assertEquals(0, singleFlight.inFlightCount)
    }

    @Test
    fun `cover single flight releases failed owner before the next retry`() = runBlocking {
        val singleFlight = CoverDownloadSingleFlight<String, String?>()

        val failure = runCatching {
            singleFlight.run("song-key|Song-12345678.jpg") {
                throw IllegalStateException("network failure")
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, singleFlight.inFlightCount)
        assertEquals(
            "content://covers/Song-12345678.jpg",
            singleFlight.run("song-key|Song-12345678.jpg") {
                "content://covers/Song-12345678.jpg"
            }
        )
        assertEquals(0, singleFlight.inFlightCount)
    }

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
    fun `expected sidecar state survives merge`() {
        val expected = AudioDownloadManager.DownloadedSidecarReferences(
            expectedCover = true,
            expectedLyric = true
        )

        val merged = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = null,
            incoming = expected
        )

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
    fun `sidecar lyric content follows its reference and avoids stale reuse`() {
        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/old.lrc",
            lyricContent = "old"
        )
        val changed = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = existing,
            incoming = AudioDownloadManager.DownloadedSidecarReferences(
                lyricReference = "content://lyrics/new.lrc"
            )
        )
        assertEquals("content://lyrics/new.lrc", changed.lyricReference)
        assertNull(changed.lyricContent)

        val same = AudioDownloadManager.mergeDownloadedSidecarReferences(
            existing = existing,
            incoming = AudioDownloadManager.DownloadedSidecarReferences(
                lyricReference = "content://lyrics/old.lrc"
            )
        )
        assertEquals("old", same.lyricContent)
    }

    @Test
    fun `retainCreatedOnly keeps inline lyric content only for created sidecar`() {
        val created = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/song.lrc",
            createdLyric = true,
            lyricContent = "[00:01.00]hello"
        )
        assertEquals("[00:01.00]hello", created.retainCreatedOnly().lyricContent)

        val existing = AudioDownloadManager.DownloadedSidecarReferences(
            lyricReference = "content://lyrics/song.lrc",
            createdLyric = false,
            lyricContent = "must not leak"
        )
        assertNull(existing.retainCreatedOnly().lyricContent)
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
    fun `completed audio reference stays available for the completion callback race`() {
        val songKey = "race-song-key"
        AudioDownloadManager.releaseCompletedAudioReference(songKey)
        val storedEntry = ManagedDownloadStorage.StoredEntry(
            name = "Race - Song.mp3",
            reference = "/downloads/Race - Song.mp3",
            mediaUri = "file:///downloads/Race%20-%20Song.mp3",
            localFilePath = "/downloads/Race - Song.mp3",
            sizeBytes = 1024L,
            lastModifiedMs = 43L
        )

        AudioDownloadManager.rememberCompletedAudioReference(songKey, storedEntry)

        assertEquals(storedEntry, AudioDownloadManager.peekCompletedAudioReference(songKey))
        AudioDownloadManager.releaseCompletedAudioReference(songKey, storedEntry)
        assertNull(AudioDownloadManager.peekCompletedAudioReference(songKey))
    }

    @Test
    fun `completed audio reference is found by local URI when queue identity changes`() {
        val songKey = "download-origin-key"
        AudioDownloadManager.releaseCompletedAudioReference(songKey)
        val storedEntry = ManagedDownloadStorage.StoredEntry(
            name = "Race - Song.mp3",
            reference = "/downloads/Race - Song.mp3",
            mediaUri = "file:///downloads/Race%20-%20Song.mp3",
            localFilePath = "/downloads/Race - Song.mp3",
            sizeBytes = 1024L,
            lastModifiedMs = 44L
        )
        val queuedSong = SongItem(
            id = 999L,
            name = "Race - Song",
            artist = "Race",
            album = "New queue identity",
            albumId = 999L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = storedEntry.mediaUri,
            localFilePath = storedEntry.localFilePath
        )

        AudioDownloadManager.rememberCompletedAudioReference(songKey, storedEntry)

        assertEquals(storedEntry, AudioDownloadManager.peekCompletedAudioReference(queuedSong))
        AudioDownloadManager.releaseCompletedAudioReference(songKey, storedEntry)
        assertNull(AudioDownloadManager.peekCompletedAudioReference(queuedSong))
    }

}
