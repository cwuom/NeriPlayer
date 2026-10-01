package moe.ouom.neriplayer.ui.screen.nowplaying.edit

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem

internal interface EditSongSaveSteps {
    fun latestSong(original: SongItem): SongItem
    suspend fun writeLyrics(song: SongItem): Boolean
    suspend fun writeMetadata(song: SongItem): Boolean
}

internal enum class EditSongSaveResult {
    SAVED,
    LYRICS_FAILED,
    METADATA_FAILED
}

internal fun EditSongSaveResult.failureMessage(): Int = when (this) {
    EditSongSaveResult.LYRICS_FAILED -> CoreCommonR.string.local_song_lyrics_write_failed
    EditSongSaveResult.METADATA_FAILED -> CoreCommonR.string.local_song_metadata_write_failed
    EditSongSaveResult.SAVED -> error("Successful save has no failure message")
}

internal suspend fun executeEditSongSave(
    song: SongItem,
    steps: EditSongSaveSteps
): EditSongSaveResult {
    val beforeLyrics = steps.latestSong(song)
    if (!steps.writeLyrics(beforeLyrics)) return EditSongSaveResult.LYRICS_FAILED
    val afterLyrics = steps.latestSong(beforeLyrics)
    if (!steps.writeMetadata(afterLyrics)) return EditSongSaveResult.METADATA_FAILED
    return EditSongSaveResult.SAVED
}

internal suspend fun <T> captureEditSongOperation(
    onCancelled: () -> Unit = {},
    block: suspend () -> T
): Result<T> {
    val result = runCatching { block() }
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) {
        onCancelled()
        throw failure
    }
    if (failure != null && failure !is Exception) throw failure
    return result
}
