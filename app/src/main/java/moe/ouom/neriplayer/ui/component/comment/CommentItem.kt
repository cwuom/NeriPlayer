package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.SongComment
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatDate
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@Composable
internal fun CommentItem(
    comment: SongComment,
    floor: Int,
    offlineMode: Boolean,
    isLiking: Boolean,
    likeEnabled: Boolean,
    onLike: () -> Unit,
    onReply: (CommentReplyTarget) -> Unit,
    onToggleReplies: () -> Unit,
    repliesExpanded: Boolean,
    hasReplyThread: Boolean,
    replyEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val anonymous = stringResource(R.string.comment_anonymous_user)
    val username = comment.username.trim().ifBlank { anonymous }
    val timeText = comment.createTime?.takeIf { it > 0L }?.let { formatDate(it) }
    val likeAction = stringResource(if (comment.isLiked) R.string.comment_unlike else R.string.comment_like)
    val likeState = stringResource(if (comment.isLiked) R.string.comment_liked else R.string.comment_not_liked)

    CommentActionBox(comment, comment.id, replyEnabled, onReply, modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = remember(context, comment.avatarUrl, offlineMode) {
                            offlineCachedImageRequest(context, comment.avatarUrl, sizePx = 96, offlineMode = offlineMode)
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = username,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (timeText != null) {
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    comment.userLevel?.let { level ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = stringResource(R.string.comment_user_level_format, level),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(R.string.comment_floor_format, floor),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                CommentQuotes(comment.quotedComments)
                if (!hasReplyThread) {
                    comment.previewReplies.take(3).forEach { reply ->
                        CommentReplyItem(reply, comment.id, replyEnabled, onReply)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((comment.replyCount ?: 0L) > 0L || comment.previewReplies.isNotEmpty() || hasReplyThread) {
                        HapticTextButton(onClick = onToggleReplies, modifier = Modifier.weight(1f), enabled = hasReplyThread || !offlineMode) {
                            Text(
                                text = if (repliesExpanded) stringResource(R.string.comment_collapse_replies)
                                    else stringResource(R.string.comment_reply_count_format,
                                        formatPlayCount(context, comment.replyCount ?: comment.previewReplies.size.toLong())),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        HapticTextButton(
                            onClick = { onReply(CommentReplyTarget(comment.id, comment.id, username)) },
                            enabled = replyEnabled,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.comment_reply))
                        }
                    }
                    HapticTextButton(
                        onClick = onLike,
                        enabled = likeEnabled && !isLiking && !offlineMode,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = likeAction
                            stateDescription = likeState
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (comment.isLiked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (comment.isLiked) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        if (isLiking) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = if (comment.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(formatPlayCount(context, comment.likeCount), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
