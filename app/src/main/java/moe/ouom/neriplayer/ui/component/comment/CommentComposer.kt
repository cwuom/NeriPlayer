package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.commentLengthLimit
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState

@Composable
internal fun CommentComposer(
    ui: CommentUiState,
    offlineMode: Boolean,
    onDraft: (String) -> Unit,
    onReply: (CommentReplyTarget?) -> Unit,
    onSend: () -> Unit,
    focusRequest: Int = 0,
    compact: Boolean = false
) {
    val source = ui.source ?: return
    val limit = source.platform.commentLengthLimit()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val handledFocusRequest = remember(ui.source) { mutableIntStateOf(0) }
    LaunchedEffect(ui.source) {
        focusManager.clearFocus(force = true)
    }
    LaunchedEffect(focusRequest, ui.replyTarget, windowFocused) {
        if (focusRequest > handledFocusRequest.intValue && ui.replyTarget != null && windowFocused) {
            withFrameNanos { }
            if (windowInfo.isWindowFocused) {
                focusRequester.requestFocus()
                handledFocusRequest.intValue = focusRequest
            }
        }
    }
    LaunchedEffect(ui.sendSucceeded) {
        if (ui.sendSucceeded) focusManager.clearFocus()
    }
    Column(Modifier.fillMaxWidth().testTag("comment-composer")
        .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ui.replyTarget?.let { target ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(CoreCommonR.string.comment_reply_to, target.username.ifBlank { stringResource(CoreCommonR.string.comment_anonymous_user) }),
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
                HapticIconButton(onClick = { onReply(null) }, enabled = !ui.isSending) {
                    Icon(Icons.Outlined.Close, stringResource(CoreCommonR.string.comment_cancel_reply))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("comment-draft"),
                enabled = !ui.isSending,
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = {
                    Text(stringResource(CoreCommonR.string.comment_write_hint),
                        maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                },
                maxLines = if (compact) 1 else 3,
                isError = ui.draft.length > limit,
                trailingIcon = if (compact && ui.draft.isNotEmpty()) {
                    {
                        Text(stringResource(CoreCommonR.string.comment_length_format, ui.draft.length, limit),
                            Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall,
                            color = if (ui.draft.length > limit) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else null,
                supportingText = if (!compact && ui.draft.isNotEmpty()) {
                    { Text(stringResource(CoreCommonR.string.comment_length_format, ui.draft.length, limit)) }
                } else null
            )
            HapticIconButton(
                onClick = onSend,
                enabled = !offlineMode && !ui.isSending && !ui.isRefreshing && !ui.isCheckingCache && !ui.isLoadingMore &&
                    ui.pendingSort == null && ui.likingIds.isEmpty() &&
                    ui.status in setOf(CommentListStatus.SUCCESS, CommentListStatus.EMPTY) &&
                    ui.draft.isNotBlank() && ui.draft.length <= limit,
                modifier = Modifier.testTag("comment-send")
            ) {
                if (ui.isSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Filled.Send, stringResource(CoreCommonR.string.comment_send))
            }
        }
        val message = when {
            ui.sendError == CommentError.PERMISSION -> stringResource(CoreCommonR.string.comment_send_login_required)
            ui.sendError == CommentError.NETWORK -> stringResource(CoreCommonR.string.comment_send_uncertain)
            ui.sendError != null -> stringResource(CoreCommonR.string.comment_send_failed)
            ui.sendSucceeded -> stringResource(CoreCommonR.string.comment_send_success)
            else -> null
        }
        if (message != null) {
            Text(
                text = ui.sendErrorCode?.let { code -> stringResource(CoreCommonR.string.comment_error_code_format, message, code) } ?: message,
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.sendError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}
