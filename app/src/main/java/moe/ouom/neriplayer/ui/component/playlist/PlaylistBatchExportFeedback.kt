package moe.ouom.neriplayer.ui.component.playlist

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSongAddResult
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar

internal fun CoroutineScope.showPlaylistBatchExportCreatedResult(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    result: Result<LocalPlaylist>
) {
    result.onSuccess { playlist ->
        showPlaylistBatchExportCreatedPlaylist(
            context = context,
            snackbarHostState = snackbarHostState,
            repository = repository,
            playlist = playlist
        )
    }.onFailure {
        showPlaylistBatchExportFailure(context, snackbarHostState)
    }
}

internal fun CoroutineScope.showPlaylistBatchExportAddedResult(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    targetPlaylistId: Long,
    targetPlaylistName: String,
    result: Result<LocalPlaylistSongAddResult>
) {
    result.onSuccess { addResult ->
        showPlaylistBatchExportAddedSongs(
            context = context,
            snackbarHostState = snackbarHostState,
            repository = repository,
            targetPlaylistId = targetPlaylistId,
            targetPlaylistName = targetPlaylistName,
            addedSongs = addResult.addedSongs
        )
    }.onFailure {
        showPlaylistBatchExportFailure(context, snackbarHostState)
    }
}

internal fun CoroutineScope.showPlaylistBatchExportCreatedPlaylist(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    playlist: LocalPlaylist
) {
    showPlaylistBatchExportSnackbar(
        context = context,
        snackbarHostState = snackbarHostState,
        targetPlaylistName = playlist.name,
        addedSongs = playlist.songs.toList(),
        undoOperation = {
            repository.deletePlaylist(playlist.id)
        }
    )
}

internal fun CoroutineScope.showPlaylistBatchExportAddedSongs(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    targetPlaylistId: Long,
    targetPlaylistName: String,
    addedSongs: List<SongItem>
) {
    val undoSongs = addedSongs.toList()
    showPlaylistBatchExportSnackbar(
        context = context,
        snackbarHostState = snackbarHostState,
        targetPlaylistName = targetPlaylistName,
        addedSongs = undoSongs,
        undoOperation = {
            if (undoSongs.isNotEmpty()) {
                repository.removeSongsFromPlaylistByIdentity(targetPlaylistId, undoSongs)
            }
            true
        }
    )
}

internal fun CoroutineScope.showPlaylistBatchExportFailure(
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    launch {
        snackbarHostState.showNeriSnackbar(context.getString(R.string.playlist_export_failed))
    }
}

private fun CoroutineScope.showPlaylistBatchExportSnackbar(
    context: Context,
    snackbarHostState: SnackbarHostState,
    targetPlaylistName: String,
    addedSongs: List<SongItem>,
    undoOperation: suspend () -> Boolean
) {
    val addedCount = addedSongs.size
    val message = context.resources.getQuantityString(
        R.plurals.playlist_batch_export_success,
        addedCount,
        addedCount,
        targetPlaylistName
    )
    launch {
        val result = snackbarHostState.showNeriSnackbar(
            message = message,
            actionLabel = context.getString(R.string.playlist_batch_export_undo),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result != SnackbarResult.ActionPerformed) return@launch

        val undoSucceeded = runLocalPlaylistMutationSafely("undoPlaylistBatchExport") {
            undoOperation()
        }.getOrDefault(false)
        val undoMessage = if (undoSucceeded) {
            context.getString(R.string.playlist_batch_export_undone, targetPlaylistName)
        } else {
            context.getString(R.string.playlist_batch_export_undo_failed)
        }
        snackbarHostState.showNeriSnackbar(undoMessage)
    }
}
