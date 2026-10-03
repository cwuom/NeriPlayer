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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatDate
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/**
 * 一级评论底部动作按钮的统一尺寸: 高度 / contentPadding / 字体三者一致,
 * 避免「回复」与「点赞」两个按钮高低、字号不一致 (真机反馈 #2)。
 */
private val CommentActionMinHeight = 40.dp
private val CommentActionContentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/**
 * 一级评论卡片: 头像 / 昵称 / 时间 / 等级 / 楼层 + 正文 (内联表情与配图) + 引用内容 + 底部动作栏。
 *
 * 没有楼中楼数据时平铺最多 3 条预览回复; 点击配图在本组件内打开 [CommentImagePreviewDialog];
 * 复制 / 回复菜单由 [CommentActionBox] 提供, 回复与点赞按钮是否可用完全由调用方通过
 * [replyEnabled] / [likeEnabled] 决定。
 *
 * @param comment 该楼的一级评论
 * @param floor 楼层号, 从 1 开始展示
 * @param offlineMode 离线模式, 透传给头像与配图请求, 并禁用点赞
 * @param isLiking 本条评论的点赞请求是否在途
 * @param likeEnabled 点赞按钮是否可用
 * @param onLike 点击点赞 / 取消点赞
 * @param onReply 点击回复, 携带回复目标
 * @param onToggleReplies 展开 / 收起楼中楼
 * @param repliesExpanded 楼中楼当前是否展开
 * @param hasReplyThread 是否已有楼中楼数据, 为 true 时不再平铺预览回复
 * @param replyEnabled 回复按钮是否可用
 * @param likingIds 点赞在途的评论 id 集合, 用于楼中楼点赞按钮的加载态
 * @param onLikeReply 点击楼中楼点赞, 参数是该回复的评论 id
 * @param modifier 外部修饰符
 */
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
    likingIds: Set<String> = emptySet(),
    onLikeReply: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val anonymous = stringResource(CoreCommonR.string.comment_anonymous_user)
    val username = comment.username.trim().ifBlank { anonymous }
    val timeText = comment.createTime?.takeIf { it > 0L }?.let { formatDate(it) }
    val likeAction = stringResource(if (comment.isLiked) CoreCommonR.string.comment_unlike else CoreCommonR.string.comment_like)
    val likeState = stringResource(if (comment.isLiked) CoreCommonR.string.comment_liked else CoreCommonR.string.comment_not_liked)
    // 当前放大的配图下标, null 表示未打开预览
    var previewIndex by remember(comment.id) { mutableStateOf<Int?>(null) }

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
                                text = stringResource(CoreCommonR.string.comment_user_level_format, level),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = stringResource(CoreCommonR.string.comment_floor_format, floor),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CommentRichText(
                    content = comment.content,
                    emotes = comment.emotes,
                    style = MaterialTheme.typography.bodyLarge,
                    offlineMode = offlineMode
                )
                if (comment.images.isNotEmpty()) {
                    CommentImageGrid(
                        images = comment.images,
                        offlineMode = offlineMode,
                        onImageClick = { index -> previewIndex = index }
                    )
                }
                CommentQuotes(comment.quotedComments)
                if (!hasReplyThread) {
                    comment.previewReplies.take(3).forEach { reply ->
                        CommentReplyItem(
                            comment = reply,
                            rootId = comment.id,
                            replyEnabled = replyEnabled,
                            onReply = onReply,
                            offlineMode = offlineMode,
                            isLiking = reply.id in likingIds,
                            likeEnabled = likeEnabled && !offlineMode,
                            onLike = { onLikeReply(reply.id) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 「回复 / N 条回复」与「点赞」相邻靠右成组: 左侧留白, 两者固定间隔 8dp (真机反馈 #2)
                    Spacer(Modifier.weight(1f))
                    if ((comment.replyCount ?: 0L) > 0L || comment.previewReplies.isNotEmpty() || hasReplyThread) {
                        // 外框与点赞按钮保持一致: 原来没有底色, 看起来不像可点的按钮
                        HapticTextButton(
                            onClick = onToggleReplies,
                            enabled = hasReplyThread || !offlineMode,
                            modifier = Modifier.heightIn(min = CommentActionMinHeight),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = CommentActionContentPadding
                        ) {
                            Text(
                                text = if (repliesExpanded) stringResource(CoreCommonR.string.comment_collapse_replies)
                                    else stringResource(CoreCommonR.string.comment_reply_count_format,
                                        formatPlayCount(context, comment.replyCount ?: comment.previewReplies.size.toLong())),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    } else {
                        HapticTextButton(
                            onClick = { onReply(CommentReplyTarget(comment.id, comment.id, username)) },
                            enabled = replyEnabled,
                            modifier = Modifier.heightIn(min = CommentActionMinHeight),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = CommentActionContentPadding
                        ) {
                            Text(stringResource(CoreCommonR.string.comment_reply), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    HapticTextButton(
                        onClick = onLike,
                        enabled = likeEnabled && !isLiking && !offlineMode,
                        modifier = Modifier.heightIn(min = CommentActionMinHeight).semantics {
                            contentDescription = likeAction
                            stateDescription = likeState
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (comment.isLiked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (comment.isLiked) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = CommentActionContentPadding
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
    previewIndex?.let { index ->
        CommentImagePreviewDialog(
            images = comment.images,
            initialIndex = index,
            offlineMode = offlineMode,
            onDismiss = { previewIndex = null }
        )
    }
}
