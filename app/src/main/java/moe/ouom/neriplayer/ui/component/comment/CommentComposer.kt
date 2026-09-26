package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.core.comment.model.commentLengthLimit
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState

@Composable
internal fun CommentComposer(
    ui: CommentUiState,
    offlineMode: Boolean,
    onDraft: (String) -> Unit,
    onReply: (CommentReplyTarget?) -> Unit,
    onSend: () -> Unit
) {
    val source = ui.source ?: return
    val limit = source.platform.commentLengthLimit()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(ui.replyTarget) {
        if (ui.replyTarget != null) focusRequester.requestFocus()
    }
    LaunchedEffect(ui.sendSucceeded) {
        if (ui.sendSucceeded) focusManager.clearFocus()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ui.replyTarget?.let { target ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.comment_reply_to, target.username.ifBlank { stringResource(R.string.comment_anonymous_user) }),
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
                HapticIconButton(onClick = { onReply(null) }, enabled = !ui.isSending) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.comment_cancel_reply))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("comment-draft"),
                enabled = !ui.isSending,
                placeholder = { Text(stringResource(R.string.comment_write_hint)) },
                maxLines = 3,
                isError = ui.draft.length > limit,
                supportingText = if (ui.draft.isNotEmpty()) {
                    { Text(stringResource(R.string.comment_length_format, ui.draft.length, limit)) }
                } else null
            )
            HapticTextButton(
                onClick = onSend,
                enabled = !offlineMode && !ui.isSending && !ui.isRefreshing && !ui.isLoadingMore &&
                    ui.pendingSort == null && ui.likingIds.isEmpty() &&
                    ui.status in setOf(CommentListStatus.SUCCESS, CommentListStatus.EMPTY) &&
                    ui.draft.isNotBlank() && ui.draft.length <= limit,
                modifier = Modifier.testTag("comment-send")
            ) {
                if (ui.isSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.comment_send))
            }
        }
        val message = when {
            ui.sendError == CommentError.PERMISSION -> stringResource(R.string.comment_send_login_required)
            ui.sendError == CommentError.NETWORK -> stringResource(R.string.comment_send_uncertain)
            ui.sendError != null -> stringResource(R.string.comment_send_failed)
            ui.sendSucceeded -> stringResource(R.string.comment_send_success)
            else -> null
        }
        if (message != null) {
            Text(
                text = ui.sendErrorCode?.let { code -> stringResource(R.string.comment_error_code_format, message, code) } ?: message,
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.sendError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}
