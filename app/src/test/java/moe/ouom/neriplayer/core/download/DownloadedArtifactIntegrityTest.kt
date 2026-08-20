package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey

class DownloadedArtifactIntegrityTest {
    @Test
    fun `complete downloaded artifacts pass integrity verification`() {
        val song = remoteSong()
        val metadata = completeMetadata(song)

        val result = verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = metadata,
            references = readableReferences(),
            expectCover = true,
            expectOriginalLyric = true,
            expectTranslatedLyric = true,
            expectRomanizedLyric = true
        )

        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `missing expected cover is rejected`() {
        val song = remoteSong()
        val result = verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = completeMetadata(song).copy(coverPath = null),
            references = readableReferences().copy(coverReadable = false),
            expectCover = true,
            expectOriginalLyric = true,
            expectTranslatedLyric = true,
            expectRomanizedLyric = true
        )

        assertEquals(
            setOf(DownloadedArtifactIntegrityIssue.COVER_REFERENCE_MISSING),
            result.issues
        )
    }

    @Test
    fun `missing translated and romanized files are rejected independently`() {
        val song = remoteSong()
        val result = verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = completeMetadata(song),
            references = readableReferences().copy(
                translatedLyricReadable = false,
                romanizedLyricReadable = false
            ),
            expectCover = true,
            expectOriginalLyric = true,
            expectTranslatedLyric = true,
            expectRomanizedLyric = true
        )

        assertEquals(
            setOf(
                DownloadedArtifactIntegrityIssue.TRANSLATED_LYRIC_REFERENCE_UNREADABLE,
                DownloadedArtifactIntegrityIssue.ROMANIZED_LYRIC_REFERENCE_UNREADABLE
            ),
            result.issues
        )
    }

    @Test
    fun `remote identity fields are part of the integrity contract`() {
        val song = remoteSong()
        val result = verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = completeMetadata(song).copy(audioId = "different"),
            references = readableReferences(),
            expectCover = true,
            expectOriginalLyric = true,
            expectTranslatedLyric = true,
            expectRomanizedLyric = true
        )

        assertTrue(
            DownloadedArtifactIntegrityIssue.AUDIO_ID_MISMATCH in result.issues
        )
    }

    @Test
    fun `artifacts without a source are optional`() {
        val song = remoteSong().copy(
            coverUrl = null,
            matchedLyric = null,
            matchedTranslatedLyric = null,
            matchedRomanizedLyric = null
        )
        val metadata = completeMetadata(song).copy(
            coverUrl = null,
            coverPath = null,
            lyricPath = null,
            translatedLyricPath = null,
            romanizedLyricPath = null
        )

        val result = verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = metadata,
            references = readableReferences().copy(
                coverReadable = false,
                originalLyricReadable = false,
                translatedLyricReadable = false,
                romanizedLyricReadable = false
            ),
            expectCover = false,
            expectOriginalLyric = false,
            expectTranslatedLyric = false,
            expectRomanizedLyric = false
        )

        assertTrue(result.isValid)
    }

    private fun remoteSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "netease",
            albumId = 7L,
            durationMs = 180_000L,
            coverUrl = "https://example.com/cover.jpg",
            mediaUri = "https://example.com/audio.mp3",
            matchedLyric = "[00:01.00]original",
            matchedTranslatedLyric = "[00:01.00]translation",
            matchedRomanizedLyric = "[00:01.00]romanized",
            channelId = "netease",
            audioId = "42",
            subAudioId = "standard",
            playlistContextId = "playlist"
        )
    }

    private fun completeMetadata(song: SongItem): ManagedDownloadStorage.DownloadedAudioMetadata {
        val identity = song.identity()
        return ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = song.stableKey(),
            songId = song.id,
            identityAlbum = identity.album,
            album = song.album,
            name = song.name,
            artist = song.artist,
            coverUrl = song.coverUrl,
            mediaUri = identity.mediaUri ?: song.mediaUri,
            channelId = song.channelId,
            audioId = song.audioId,
            subAudioId = song.subAudioId,
            playlistContextId = song.playlistContextId,
            coverPath = "content://covers/song.jpg",
            lyricPath = "content://lyrics/song.lrc",
            translatedLyricPath = "content://lyrics/song_trans.lrc",
            romanizedLyricPath = "content://lyrics/song_roma.lrc",
            durationMs = song.durationMs,
            downloadFinalized = true
        )
    }

    private fun readableReferences(): DownloadedArtifactReferenceState {
        return DownloadedArtifactReferenceState(
            audioReadable = true,
            coverReadable = true,
            originalLyricReadable = true,
            translatedLyricReadable = true,
            romanizedLyricReadable = true
        )
    }
}
