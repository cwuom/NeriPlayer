package moe.ouom.neriplayer.core.player

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
