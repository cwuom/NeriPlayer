package moe.ouom.neriplayer.core.download.model

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.isFinalizedDownloadedMetadata
import kotlin.math.abs
import kotlin.math.max
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey

internal data class DownloadedArtifactReferenceState(
    val audioReadable: Boolean,
    val audioDurationMs: Long? = null,
    val coverReadable: Boolean,
    val originalLyricReadable: Boolean,
    val translatedLyricReadable: Boolean,
    val romanizedLyricReadable: Boolean
)

internal enum class DownloadedArtifactIntegrityIssue {
    AUDIO_UNREADABLE,
    AUDIO_DURATION_UNAVAILABLE,
    AUDIO_DURATION_MISMATCH,
    METADATA_MISSING,
    METADATA_NOT_FINALIZED,
    STABLE_KEY_MISMATCH,
    IDENTITY_ALBUM_MISMATCH,
    SONG_ID_MISMATCH,
    NAME_MISSING,
    NAME_MISMATCH,
    ARTIST_MISSING,
    ARTIST_MISMATCH,
    ALBUM_MISMATCH,
    DURATION_MISMATCH,
    MEDIA_URI_MISMATCH,
    CHANNEL_ID_MISMATCH,
    AUDIO_ID_MISMATCH,
    SUB_AUDIO_ID_MISMATCH,
    PLAYLIST_CONTEXT_ID_MISMATCH,
    COVER_URL_MISMATCH,
    CUSTOM_COVER_URL_MISMATCH,
    ORIGINAL_COVER_URL_MISMATCH,
    COVER_REFERENCE_MISSING,
    COVER_REFERENCE_UNREADABLE,
    ORIGINAL_LYRIC_REFERENCE_MISSING,
    ORIGINAL_LYRIC_REFERENCE_UNREADABLE,
    TRANSLATED_LYRIC_REFERENCE_MISSING,
    TRANSLATED_LYRIC_REFERENCE_UNREADABLE,
    ROMANIZED_LYRIC_REFERENCE_MISSING,
    ROMANIZED_LYRIC_REFERENCE_UNREADABLE
}

internal data class DownloadedArtifactIntegrityResult(
    val issues: Set<DownloadedArtifactIntegrityIssue>
) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

internal fun verifyDownloadedArtifactIntegrity(
    song: SongItem,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    references: DownloadedArtifactReferenceState,
    expectCover: Boolean,
    expectOriginalLyric: Boolean,
    expectTranslatedLyric: Boolean,
    expectRomanizedLyric: Boolean,
    requireFinalizedMetadata: Boolean = true
): DownloadedArtifactIntegrityResult {
    val issues = linkedSetOf<DownloadedArtifactIntegrityIssue>()
    if (!references.audioReadable) {
        issues += DownloadedArtifactIntegrityIssue.AUDIO_UNREADABLE
    }
    verifyAudioDuration(
        expectedDurationMs = expectedDownloadedAudioDurationMs(song, metadata),
        actualDurationMs = references.audioDurationMs,
        issues = issues
    )

    if (metadata == null) {
        issues += DownloadedArtifactIntegrityIssue.METADATA_MISSING
        return DownloadedArtifactIntegrityResult(issues)
    }
    if (requireFinalizedMetadata && !isFinalizedDownloadedMetadata(metadata)) {
        issues += DownloadedArtifactIntegrityIssue.METADATA_NOT_FINALIZED
    }

    val identity = song.identity()
    if (metadata.stableKey != identity.stableKey()) {
        issues += DownloadedArtifactIntegrityIssue.STABLE_KEY_MISMATCH
    }
    if (metadata.identityAlbum != identity.album) {
        issues += DownloadedArtifactIntegrityIssue.IDENTITY_ALBUM_MISMATCH
    }
    if (song.id > 0L && metadata.songId != song.id) {
        issues += DownloadedArtifactIntegrityIssue.SONG_ID_MISMATCH
    }

    addTextFieldIssues(
        expected = song.name,
        actual = metadata.name,
        missingIssue = DownloadedArtifactIntegrityIssue.NAME_MISSING,
        mismatchIssue = DownloadedArtifactIntegrityIssue.NAME_MISMATCH,
        issues = issues
    )
    addTextFieldIssues(
        expected = song.artist,
        actual = metadata.artist,
        missingIssue = DownloadedArtifactIntegrityIssue.ARTIST_MISSING,
        mismatchIssue = DownloadedArtifactIntegrityIssue.ARTIST_MISMATCH,
        issues = issues,
        required = song.artist.isNotBlank()
    )
    addOptionalExactIssue(
        expected = song.album,
        actual = metadata.album,
        issue = DownloadedArtifactIntegrityIssue.ALBUM_MISMATCH,
        issues = issues
    )
    if (song.durationMs > 0L && metadata.durationMs != song.durationMs) {
        issues += DownloadedArtifactIntegrityIssue.DURATION_MISMATCH
    }

    addOptionalExactIssue(
        expected = identity.mediaUri ?: song.mediaUri,
        actual = metadata.mediaUri,
        issue = DownloadedArtifactIntegrityIssue.MEDIA_URI_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.channelId,
        actual = metadata.channelId,
        issue = DownloadedArtifactIntegrityIssue.CHANNEL_ID_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.audioId,
        actual = metadata.audioId,
        issue = DownloadedArtifactIntegrityIssue.AUDIO_ID_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.subAudioId,
        actual = metadata.subAudioId,
        issue = DownloadedArtifactIntegrityIssue.SUB_AUDIO_ID_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.playlistContextId,
        actual = metadata.playlistContextId,
        issue = DownloadedArtifactIntegrityIssue.PLAYLIST_CONTEXT_ID_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.coverUrl,
        actual = metadata.coverUrl,
        issue = DownloadedArtifactIntegrityIssue.COVER_URL_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.customCoverUrl,
        actual = metadata.customCoverUrl,
        issue = DownloadedArtifactIntegrityIssue.CUSTOM_COVER_URL_MISMATCH,
        issues = issues
    )
    addOptionalExactIssue(
        expected = song.originalCoverUrl,
        actual = metadata.originalCoverUrl,
        issue = DownloadedArtifactIntegrityIssue.ORIGINAL_COVER_URL_MISMATCH,
        issues = issues
    )

    verifyReference(
        reference = metadata.coverPath,
        expected = expectCover,
        accessible = references.coverReadable,
        missingIssue = DownloadedArtifactIntegrityIssue.COVER_REFERENCE_MISSING,
        unreadableIssue = DownloadedArtifactIntegrityIssue.COVER_REFERENCE_UNREADABLE,
        issues = issues
    )
    verifyReference(
        reference = metadata.lyricPath,
        expected = expectOriginalLyric,
        accessible = references.originalLyricReadable,
        missingIssue = DownloadedArtifactIntegrityIssue.ORIGINAL_LYRIC_REFERENCE_MISSING,
        unreadableIssue = DownloadedArtifactIntegrityIssue.ORIGINAL_LYRIC_REFERENCE_UNREADABLE,
        issues = issues
    )
    verifyReference(
        reference = metadata.translatedLyricPath,
        expected = expectTranslatedLyric,
        accessible = references.translatedLyricReadable,
        missingIssue = DownloadedArtifactIntegrityIssue.TRANSLATED_LYRIC_REFERENCE_MISSING,
        unreadableIssue = DownloadedArtifactIntegrityIssue.TRANSLATED_LYRIC_REFERENCE_UNREADABLE,
        issues = issues
    )
    verifyReference(
        reference = metadata.romanizedLyricPath,
        expected = expectRomanizedLyric,
        accessible = references.romanizedLyricReadable,
        missingIssue = DownloadedArtifactIntegrityIssue.ROMANIZED_LYRIC_REFERENCE_MISSING,
        unreadableIssue = DownloadedArtifactIntegrityIssue.ROMANIZED_LYRIC_REFERENCE_UNREADABLE,
        issues = issues
    )

    return DownloadedArtifactIntegrityResult(issues)
}

private fun addTextFieldIssues(
    expected: String?,
    actual: String?,
    missingIssue: DownloadedArtifactIntegrityIssue,
    mismatchIssue: DownloadedArtifactIntegrityIssue,
    issues: MutableSet<DownloadedArtifactIntegrityIssue>,
    required: Boolean = true
) {
    if (actual.isNullOrBlank()) {
        if (required) issues += missingIssue
    } else if (!expected.isNullOrBlank() && actual != expected) {
        issues += mismatchIssue
    }
}

/** 下载目录只接受与来源时长相符的成品，编码 padding 保留很小的容差 */
private fun verifyAudioDuration(
    expectedDurationMs: Long,
    actualDurationMs: Long?,
    issues: MutableSet<DownloadedArtifactIntegrityIssue>
) {
    if (expectedDurationMs <= 0L) return
    val actual = actualDurationMs?.takeIf { it > 0L }
    if (actual == null) {
        issues += DownloadedArtifactIntegrityIssue.AUDIO_DURATION_UNAVAILABLE
        return
    }
    if (hasDownloadedAudioDurationMismatch(expectedDurationMs, actual)) {
        issues += DownloadedArtifactIntegrityIssue.AUDIO_DURATION_MISMATCH
    }
}

internal fun hasDownloadedAudioDurationMismatch(expectedMs: Long, actualMs: Long?): Boolean {
    if (expectedMs <= 0L || actualMs == null || actualMs <= 0L) return false
    val toleranceMs = max(1_000L, expectedMs / 200L).coerceAtMost(2_000L)
    return abs(actualMs - expectedMs) > toleranceMs
}

internal fun expectedDownloadedAudioDurationMs(
    song: SongItem,
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): Long {
    return metadata?.takeIf { it.stableKey == song.stableKey() }
        ?.verifiedAudioDurationMs?.takeIf { it > 0L } ?: song.durationMs
}

private fun addOptionalExactIssue(
    expected: String?,
    actual: String?,
    issue: DownloadedArtifactIntegrityIssue,
    issues: MutableSet<DownloadedArtifactIntegrityIssue>
) {
    if (!expected.isNullOrBlank() && actual != expected) {
        issues += issue
    }
}

private fun verifyReference(
    reference: String?,
    expected: Boolean,
    accessible: Boolean,
    missingIssue: DownloadedArtifactIntegrityIssue,
    unreadableIssue: DownloadedArtifactIntegrityIssue,
    issues: MutableSet<DownloadedArtifactIntegrityIssue>
) {
    if (expected && reference.isNullOrBlank()) {
        issues += missingIssue
    } else if (!reference.isNullOrBlank() && !accessible) {
        issues += unreadableIssue
    }
}
