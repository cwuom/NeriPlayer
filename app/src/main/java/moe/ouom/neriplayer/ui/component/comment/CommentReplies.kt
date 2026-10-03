package moe.ouom.neriplayer.ui.component.comment

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentQuote
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget
import moe.ouom.neriplayer.data.model.comments.SongComment
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/** 楼中楼头像尺寸: 固定 24dp, 严格小于一级评论头像 (40dp); 预览与展开列表共用同一尺寸 */
private val ReplyAvatarSize = 24.dp

/**
 * 评论内容的长按菜单容器: 包裹 [content], 点击或长按弹出下拉菜单, 提供「复制正文」与「回复」。
 *
 * 复制走系统剪贴板, 复制的是 [comment] 的正文原文; 回复项在 [replyEnabled] 为 false 时置灰,
 * 触发回复时用 [rootId] 组装 [CommentReplyTarget], 保证楼中楼的回复始终挂在正确的根评论下。
 *
 * @param comment 该条评论, 用于取正文与组装回复目标
 * @param rootId 所属一级评论的 id
 * @param replyEnabled 回复菜单项是否可用
 * @param onReply 点击回复时回调回复目标
 * @param modifier 外部修饰符
 * @param shape 外层裁剪形状, 一级评论与楼中楼各自传入
 * @param content 被包裹的评论内容
 */
@Composable
internal fun CommentActionBox(
    comment: SongComment,
    rootId: String,
    replyEnabled: Boolean,
    onReply: (CommentReplyTarget) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable () -> Unit
) {
    var expanded by remember(comment.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val reply = { onReply(CommentReplyTarget(comment.id, rootId, comment.username)) }
    Box(modifier.clip(shape).combinedClickable(
        onClick = { expanded = true },
        onClickLabel = stringResource(CoreCommonR.string.comment_actions),
        onLongClickLabel = stringResource(CoreCommonR.string.comment_actions),
        onLongClick = { expanded = true }
    )) {
        content()
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = MaterialTheme.shapes.large) {
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.comment_copy)) },
                onClick = {
                    expanded = false
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("comment", comment.content))) }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(CoreCommonR.string.comment_reply)) },
                enabled = replyEnabled,
                onClick = { expanded = false; reply() }
            )
        }
    }
}

/**
 * 被引用评论的只读展示 ([quotes] 为空时不渲染任何内容)。
 *
 * 每条展示「回复 @某人」与该条原文; 原文为 null (已被删除) 时用「评论已删除」占位, 昵称为空时用匿名占位。
 */
@Composable
internal fun CommentQuotes(quotes: List<CommentQuote>) {
    if (quotes.isEmpty()) return
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quotes.forEach { quote ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(CoreCommonR.string.comment_reply_to, quote.username.ifBlank { stringResource(CoreCommonR.string.comment_anonymous_user) }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        quote.content ?: stringResource(CoreCommonR.string.comment_deleted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 楼中楼单条回复: 左侧小头像 + 昵称 / 正文 (内联表情) / 配图 / 引用内容 + 右对齐的小号点赞。
 *
 * 头像固定 24dp 以区别于一级评论, 没有头像地址时用同尺寸的占位图标保持左侧栏宽度恒定;
 * 长按菜单复用 [CommentActionBox], 点击配图在本组件内独立打开 [CommentImagePreviewDialog]。
 *
 * @param comment 该条回复
 * @param rootId 所属一级评论的 id
 * @param replyEnabled 回复是否可用
 * @param onReply 点击回复, 携带回复目标
 * @param modifier 外部修饰符
 * @param offlineMode 离线模式, 透传给头像与配图请求并禁用点赞
 * @param isLiking 本条的点赞请求是否在途
 * @param likeEnabled 点赞按钮是否可用
 * @param onLike 点击点赞 / 取消点赞
 */
@Composable
internal fun CommentReplyItem(
    comment: SongComment,
    rootId: String,
    replyEnabled: Boolean,
    onReply: (CommentReplyTarget) -> Unit,
    modifier: Modifier = Modifier,
    offlineMode: Boolean = false,
    isLiking: Boolean = false,
    likeEnabled: Boolean = true,
    onLike: () -> Unit = {}
) {
    val context = LocalContext.current
    // 楼中楼配图的放大预览 (与一级评论各自独立)
    var previewIndex by remember(comment.id) { mutableStateOf<Int?>(null) }
    val avatarUrl = comment.avatarUrl

    CommentActionBox(comment, rootId, replyEnabled, onReply, modifier, MaterialTheme.shapes.medium) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (avatarUrl.isNullOrBlank()) {
                    // 没有头像地址时用同一尺寸的占位, 保持左侧栏宽度恒定
                    Box(
                        modifier = Modifier.size(ReplyAvatarSize).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AsyncImage(
                        model = remember(context, avatarUrl, offlineMode) {
                            offlineCachedImageRequest(context, avatarUrl, sizePx = 48, offlineMode = offlineMode)
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(ReplyAvatarSize).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        comment.username.ifBlank { stringResource(CoreCommonR.string.comment_anonymous_user) },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    CommentRichText(
                        content = comment.content,
                        emotes = comment.emotes,
                        style = MaterialTheme.typography.bodyMedium,
                        offlineMode = offlineMode
                    )
                    if (comment.images.isNotEmpty()) {
                        CommentImageGrid(
                            images = comment.images,
                            offlineMode = offlineMode,
                            onImageClick = { index -> previewIndex = index },
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    CommentQuotes(comment.quotedComments)
                    // 楼中楼每条底部的小号点赞: 图标 16dp + 数字, 右对齐 (真机反馈 #3)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HapticTextButton(
                            onClick = onLike,
                            enabled = likeEnabled && !isLiking && !offlineMode,
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (comment.isLiked) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (comment.isLiked) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            if (isLiking) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = if (comment.isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = formatPlayCount(context, comment.likeCount),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
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
