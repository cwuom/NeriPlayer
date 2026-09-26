package moe.ouom.neriplayer.ui.component.comment

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.CommentQuote
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.core.comment.model.SongComment

@Composable
internal fun CommentActionBox(
    comment: SongComment,
    rootId: String,
    replyEnabled: Boolean,
    onReply: (CommentReplyTarget) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val reply = { onReply(CommentReplyTarget(comment.id, rootId, comment.username)) }
    Box(modifier.combinedClickable(
        onClick = { if (replyEnabled) reply() },
        onClickLabel = stringResource(R.string.comment_reply),
        onLongClickLabel = stringResource(R.string.comment_actions),
        onLongClick = { expanded = true }
    )) {
        content()
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.comment_copy)) },
                onClick = {
                    expanded = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("comment", comment.content))) }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.comment_reply)) },
                enabled = replyEnabled,
                onClick = { expanded = false; reply() }
            )
        }
    }
}

@Composable
internal fun CommentQuotes(quotes: List<CommentQuote>) {
    if (quotes.isEmpty()) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quotes.forEach { quote ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.comment_reply_to, quote.username.ifBlank { stringResource(R.string.comment_anonymous_user) }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        quote.content ?: stringResource(R.string.comment_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun CommentReplyItem(
    comment: SongComment,
    rootId: String,
    replyEnabled: Boolean,
    onReply: (CommentReplyTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    CommentActionBox(comment, rootId, replyEnabled, onReply, modifier) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    comment.username.ifBlank { stringResource(R.string.comment_anonymous_user) },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(comment.content, style = MaterialTheme.typography.bodyMedium)
                CommentQuotes(comment.quotedComments)
            }
        }
    }
}
