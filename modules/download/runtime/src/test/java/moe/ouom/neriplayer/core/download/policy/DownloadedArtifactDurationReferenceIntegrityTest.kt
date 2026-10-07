package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadedArtifactIntegrityIssue
import moe.ouom.neriplayer.data.model.download.DownloadedArtifactIntegrityResult
import moe.ouom.neriplayer.data.model.download.DownloadedArtifactReferenceState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedArtifactDurationReferenceIntegrityTest {
    @Test
    fun `expected audio duration trusts the verified duration only for the same song`() {
        val song = song(durationMs = 180_000L)
        val metadata = completeMetadata(song).copy(verifiedAudioDurationMs = 179_000L)

        assertEquals(179_000L, expectedDownloadedAudioDurationMs(song, metadata))
        assertEquals(180_000L, expectedDownloadedAudioDurationMs(song, null))
        assertEquals(180_000L, expectedDownloadedAudioDurationMs(song, metadata.copy(stableKey = "other-song")))
        assertEquals(180_000L, expectedDownloadedAudioDurationMs(song, metadata.copy(verifiedAudioDurationMs = null)))
        assertEquals(180_000L, expectedDownloadedAudioDurationMs(song, metadata.copy(verifiedAudioDurationMs = 0L)))
    }

    @Test
    fun `final audio duration is not checked when the source duration is unknown`() {
        val song = song(durationMs = 0L)

        val result = verify(song, completeMetadata(song), references(audioDurationMs = null))

        assertEquals(emptySet<DownloadedArtifactIntegrityIssue>(), result.issues)
    }

    @Test
    fun `missing or non positive final audio duration is reported as unavailable`() {
        val song = song()

        listOf(null, 0L, -1L).forEach { audioDurationMs ->
            assertEquals(
                "audioDurationMs=$audioDurationMs",
                setOf(DownloadedArtifactIntegrityIssue.AUDIO_DURATION_UNAVAILABLE),
                verify(song, completeMetadata(song), references(audioDurationMs = audioDurationMs)).issues
            )
        }
    }

    @Test
    fun `final audio is accepted only inside the whole second source tolerance`() {
        val song = song(durationMs = 180_000L)

        assertEquals(
            emptySet<DownloadedArtifactIntegrityIssue>(),
            verify(song, completeMetadata(song), references(audioDurationMs = 181_900L)).issues
        )
        assertEquals(
            setOf(DownloadedArtifactIntegrityIssue.AUDIO_DURATION_MISMATCH),
            verify(song, completeMetadata(song), references(audioDurationMs = 182_500L)).issues
        )
    }

    @Test
    fun `blank sidecar references count as missing only when the sidecar is expected`() {
        val song = song()
        val metadata = completeMetadata(song).copy(
            coverPath = "",
            lyricPath = " ",
            translatedLyricPath = null,
            romanizedLyricPath = "\t"
        )

        assertEquals(
            setOf(
                DownloadedArtifactIntegrityIssue.COVER_REFERENCE_MISSING,
                DownloadedArtifactIntegrityIssue.ORIGINAL_LYRIC_REFERENCE_MISSING,
                DownloadedArtifactIntegrityIssue.TRANSLATED_LYRIC_REFERENCE_MISSING,
                DownloadedArtifactIntegrityIssue.ROMANIZED_LYRIC_REFERENCE_MISSING
            ),
            verify(song, metadata, references(), expectSidecars = true).issues
        )
        assertEquals(
            emptySet<DownloadedArtifactIntegrityIssue>(),
            verify(song, metadata, references(), expectSidecars = false).issues
        )
    }

    @Test
    fun `recorded sidecars must stay readable even when they are optional`() {
        val song = song()
        val references = references().copy(
            coverReadable = false,
            originalLyricReadable = false,
            romanizedLyricReadable = false
        )

        assertEquals(
            setOf(
                DownloadedArtifactIntegrityIssue.COVER_REFERENCE_UNREADABLE,
                DownloadedArtifactIntegrityIssue.ORIGINAL_LYRIC_REFERENCE_UNREADABLE,
                DownloadedArtifactIntegrityIssue.ROMANIZED_LYRIC_REFERENCE_UNREADABLE
            ),
            verify(song, completeMetadata(song), references, expectSidecars = false).issues
        )
    }

    private fun verify(
        song: SongItem,
        metadata: DownloadedAudioMetadata,
        references: DownloadedArtifactReferenceState,
        expectSidecars: Boolean = true
    ): DownloadedArtifactIntegrityResult {
        return verifyDownloadedArtifactIntegrity(
            song = song,
            metadata = metadata,
            references = references,
            expectCover = expectSidecars,
            expectOriginalLyric = expectSidecars,
            expectTranslatedLyric = expectSidecars,
            expectRomanizedLyric = expectSidecars
        )
    }

    private fun song(durationMs: Long = 180_000L): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "netease",
            albumId = 7L,
            durationMs = durationMs,
            coverUrl = "https://example.com/cover.jpg",
            mediaUri = "https://example.com/audio.mp3"
        )
    }

    private fun completeMetadata(song: SongItem): DownloadedAudioMetadata {
        val identity = song.identity()
        return DownloadedAudioMetadata(
            stableKey = song.stableKey(),
            songId = song.id,
            identityAlbum = identity.album,
            album = song.album,
            name = song.name,
            artist = song.artist,
            coverUrl = song.coverUrl,
            mediaUri = identity.mediaUri ?: song.mediaUri,
            coverPath = "content://covers/song.jpg",
            lyricPath = "content://lyrics/song.lrc",
            translatedLyricPath = "content://lyrics/song_trans.lrc",
            romanizedLyricPath = "content://lyrics/song_roma.lrc",
            durationMs = song.durationMs,
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
    }

    private fun references(audioDurationMs: Long? = 180_000L): DownloadedArtifactReferenceState {
        return DownloadedArtifactReferenceState(
            audioReadable = true,
            audioDurationMs = audioDurationMs,
            coverReadable = true,
            originalLyricReadable = true,
            translatedLyricReadable = true,
            romanizedLyricReadable = true
        )
    }
}
