package moe.ouom.neriplayer.data.model.download

data class DownloadedArtifactReferenceState(
    val audioReadable: Boolean,
    val audioDurationMs: Long? = null,
    val coverReadable: Boolean,
    val originalLyricReadable: Boolean,
    val translatedLyricReadable: Boolean,
    val romanizedLyricReadable: Boolean
)

enum class DownloadedArtifactIntegrityIssue {
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

data class DownloadedArtifactIntegrityResult(
    val issues: Set<DownloadedArtifactIntegrityIssue>
) {
    val isValid: Boolean
        get() = issues.isEmpty()
}
