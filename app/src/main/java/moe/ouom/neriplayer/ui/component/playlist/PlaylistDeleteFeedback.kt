package moe.ouom.neriplayer.ui.component.playlist

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistDeleteResult
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSongDeleteResult
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar

internal fun CoroutineScope.showPlaylistDeleteResult(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    result: Result<List<LocalPlaylistDeleteResult>>
) {
    result.onSuccess { deleteResults ->
        showPlaylistDeletedSnackbar(
            context = context,
            snackbarHostState = snackbarHostState,
            repository = repository,
            deleteResults = deleteResults
        )
    }.onFailure {
        showPlaylistDeleteFailure(context, snackbarHostState)
    }
}

internal fun CoroutineScope.showPlaylistSongDeleteResult(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    result: Result<List<LocalPlaylistSongDeleteResult>>
) {
    result.onSuccess { deleteResults ->
        showPlaylistSongDeletedSnackbar(
            context = context,
            snackbarHostState = snackbarHostState,
            repository = repository,
            deleteResults = deleteResults
        )
    }.onFailure {
        showPlaylistSongDeleteFailure(context, snackbarHostState)
    }
}

internal fun showPlaylistDeleteResultGlobally(
    context: Context,
    repository: LocalPlaylistRepository,
    result: Result<List<LocalPlaylistDeleteResult>>
) {
    result.onSuccess { deleteResults ->
        showPlaylistDeletedGlobalSnackbar(
            context = context,
            repository = repository,
            deleteResults = deleteResults
        )
    }.onFailure {
        AppFeedback.show(context, context.getString(R.string.local_playlist_delete_failed))
    }
}

internal fun CoroutineScope.showPlaylistDeletedSnackbar(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    deleteResults: List<LocalPlaylistDeleteResult>
) {
    if (deleteResults.isEmpty()) {
        showPlaylistDeleteFailure(context, snackbarHostState)
        return
    }
    val deletedCount = deleteResults.size
    val displayName = deleteResults.singleOrNull()?.playlist?.name.orEmpty()
    val message = context.resources.getQuantityString(
        R.plurals.local_playlist_delete_snackbar,
        deletedCount,
        deletedCount,
        displayName
    )
    launch {
        val result = snackbarHostState.showNeriSnackbar(
            message = message,
            actionLabel = context.getString(R.string.playlist_batch_export_undo),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result != SnackbarResult.ActionPerformed) return@launch

        val undoSucceeded = runLocalPlaylistMutationSafely("undoPlaylistDelete") {
            repository.restoreDeletedPlaylists(deleteResults)
        }.getOrDefault(false)
        val undoMessage = if (undoSucceeded) {
            context.resources.getQuantityString(
                R.plurals.local_playlist_delete_undone,
                deletedCount,
                deletedCount,
                displayName
            )
        } else {
            context.getString(R.string.local_playlist_delete_undo_failed)
        }
        snackbarHostState.showNeriSnackbar(undoMessage)
    }
}

internal fun showPlaylistDeletedGlobalSnackbar(
    context: Context,
    repository: LocalPlaylistRepository,
    deleteResults: List<LocalPlaylistDeleteResult>
) {
    if (deleteResults.isEmpty()) {
        AppFeedback.show(context, context.getString(R.string.local_playlist_delete_failed))
        return
    }
    val deletedCount = deleteResults.size
    val displayName = deleteResults.singleOrNull()?.playlist?.name.orEmpty()
    val message = context.resources.getQuantityString(
        R.plurals.local_playlist_delete_snackbar,
        deletedCount,
        deletedCount,
        displayName
    )
    AppFeedback.showWithAction(
        context = context,
        message = message,
        actionLabel = context.getString(R.string.playlist_batch_export_undo),
        duration = SnackbarDuration.Long
    ) {
        val undoSucceeded = runLocalPlaylistMutationSafely("undoPlaylistDelete") {
            repository.restoreDeletedPlaylists(deleteResults)
        }.getOrDefault(false)
        val undoMessage = if (undoSucceeded) {
            context.resources.getQuantityString(
                R.plurals.local_playlist_delete_undone,
                deletedCount,
                deletedCount,
                displayName
            )
        } else {
            context.getString(R.string.local_playlist_delete_undo_failed)
        }
        AppFeedback.show(context, undoMessage)
    }
}

internal fun CoroutineScope.showPlaylistDeleteFailure(
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    launch {
        snackbarHostState.showNeriSnackbar(context.getString(R.string.local_playlist_delete_failed))
    }
}

internal fun CoroutineScope.showPlaylistSongDeletedSnackbar(
    context: Context,
    snackbarHostState: SnackbarHostState,
    repository: LocalPlaylistRepository,
    deleteResults: List<LocalPlaylistSongDeleteResult>
) {
    if (deleteResults.isEmpty()) {
        showPlaylistSongDeleteFailure(context, snackbarHostState)
        return
    }
    val deletedCount = deleteResults.size
    val message = context.resources.getQuantityString(
        R.plurals.local_playlist_delete_songs_snackbar,
        deletedCount,
        deletedCount
    )
    launch {
        val result = snackbarHostState.showNeriSnackbar(
            message = message,
            actionLabel = context.getString(R.string.playlist_batch_export_undo),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        if (result != SnackbarResult.ActionPerformed) return@launch

        val undoSucceeded = runLocalPlaylistMutationSafely("undoPlaylistSongDelete") {
            repository.restoreDeletedSongs(deleteResults)
        }.getOrDefault(false)
        val undoMessage = if (undoSucceeded) {
            context.resources.getQuantityString(
                R.plurals.local_playlist_delete_songs_undone,
                deletedCount,
                deletedCount
            )
        } else {
            context.getString(R.string.local_playlist_delete_songs_undo_failed)
        }
        snackbarHostState.showNeriSnackbar(undoMessage)
    }
}

internal fun CoroutineScope.showPlaylistSongDeleteFailure(
    context: Context,
    snackbarHostState: SnackbarHostState
) {
    launch {
        snackbarHostState.showNeriSnackbar(
            context.getString(R.string.local_playlist_delete_songs_failed)
        )
    }
}
