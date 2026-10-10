package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource

internal data class LyricsEditorSongIdentityPresentation(
    val title: String,
    val artist: String
) {
    val summary: String get() = "$title · $artist"
}

internal fun resolveLyricsEditorSongIdentityPresentation(song: SongItem): LyricsEditorSongIdentityPresentation =
    LyricsEditorSongIdentityPresentation(
        title = song.customName ?: song.name,
        artist = song.customArtist ?: song.artist
    )

internal data class LyricsEditorHeaderPresentation(
    val songIdentity: LyricsEditorSongIdentityPresentation?,
    val actionsEnabled: Boolean,
    val saving: Boolean
)

internal fun resolveLyricsEditorHeaderPresentation(
    song: SongItem?,
    isSaving: Boolean
): LyricsEditorHeaderPresentation = LyricsEditorHeaderPresentation(
    songIdentity = song?.let(::resolveLyricsEditorSongIdentityPresentation),
    actionsEnabled = !isSaving,
    saving = isSaving
)

internal data class LyricsEditorActionPresentation(
    val enabled: Boolean,
    val saving: Boolean
)

internal fun resolveLyricsEditorActionPresentation(isSaving: Boolean): LyricsEditorActionPresentation =
    LyricsEditorActionPresentation(enabled = !isSaving, saving = isSaving)

internal data class LyricMatchQueryPresentation(
    val query: String,
    val searchEnabled: Boolean
)

internal fun resolveLyricMatchQueryPresentation(
    query: String,
    isLoading: Boolean,
    selectedSources: Set<EditableLyricMatchSource>
): LyricMatchQueryPresentation = LyricMatchQueryPresentation(
    query = query,
    searchEnabled = !isLoading && query.isNotBlank() && selectedSources.isNotEmpty()
)

internal enum class LyricMatchFeedbackKind {
    LOADING,
    ERROR,
    EMPTY
}

internal data class LyricMatchFeedbackPresentation(
    val items: List<LyricMatchFeedbackKind>,
    val errorMessage: String?
)

internal fun resolveLyricMatchFeedbackPresentation(
    isLoading: Boolean,
    errorMessage: String?,
    hasSearched: Boolean,
    resultsEmpty: Boolean
): LyricMatchFeedbackPresentation = LyricMatchFeedbackPresentation(
    items = buildList {
        if (isLoading) add(LyricMatchFeedbackKind.LOADING)
        if (errorMessage != null) add(LyricMatchFeedbackKind.ERROR)
        if (!isLoading && errorMessage == null && hasSearched && resultsEmpty) {
            add(LyricMatchFeedbackKind.EMPTY)
        }
    },
    errorMessage = errorMessage
)
