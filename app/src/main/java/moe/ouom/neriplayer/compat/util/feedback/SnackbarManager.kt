package moe.ouom.neriplayer.util

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope

@Suppress("unused")
object SnackbarManager {
    fun showSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        moe.ouom.neriplayer.ui.feedback.SnackbarManager.showSnackbar(
            scope = scope,
            snackbarHostState = snackbarHostState,
            message = message,
            duration = duration
        )
    }

    fun showSnackbarWithAction(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onActionPerformed: () -> Unit = {}
    ) {
        moe.ouom.neriplayer.ui.feedback.SnackbarManager.showSnackbarWithAction(
            scope = scope,
            snackbarHostState = snackbarHostState,
            message = message,
            actionLabel = actionLabel,
            duration = duration,
            onActionPerformed = onActionPerformed
        )
    }

    fun showLongSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String
    ) {
        moe.ouom.neriplayer.ui.feedback.SnackbarManager.showLongSnackbar(
            scope = scope,
            snackbarHostState = snackbarHostState,
            message = message
        )
    }

    fun showIndefiniteSnackbar(
        scope: CoroutineScope,
        snackbarHostState: SnackbarHostState,
        message: String,
        actionLabel: String,
        onActionPerformed: () -> Unit = {}
    ) {
        moe.ouom.neriplayer.ui.feedback.SnackbarManager.showIndefiniteSnackbar(
            scope = scope,
            snackbarHostState = snackbarHostState,
            message = message,
            actionLabel = actionLabel,
            onActionPerformed = onActionPerformed
        )
    }
}
