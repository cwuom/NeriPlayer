package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentError
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.ui.component.comment.CommentItem
import moe.ouom.neriplayer.ui.component.comment.CommentLoadingBlock
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentUiState
import moe.ouom.neriplayer.ui.viewmodel.CommentViewModel

private const val COMMENT_PREVIEW_LIMIT = 3

/**
 * 模块六 评论预览（仅网易云）。
 *
 * 预览内支持点赞，回复和楼中楼入口留在完整评论面板
 * 使用独立 key 的 [CommentViewModel]，与 [moe.ouom.neriplayer.ui.component.comment.CommentSheet]
 * 共用实例会让一方离开组合时清掉另一方状态。
 */
@Composable
internal fun SongEncyclopediaCommentPreview(
    source: CommentSource,
    offlineMode: Boolean,
    onOpenAll: () -> Unit
) {
    val commentViewModel: CommentViewModel = viewModel(key = "encyclopedia_comment_preview")
    val ui by commentViewModel.uiState.collectAsStateWithLifecycle()
    val currentSource = remember(source) { source }

    LaunchedEffect(currentSource) {
        commentViewModel.onSourceChanged(currentSource)
    }
    DisposableEffect(commentViewModel) {
        onDispose { commentViewModel.onSheetHidden() }
    }

    SongEncyclopediaSectionCard(
        titleRes = CoreCommonR.string.encyclopedia_section_comments,
        icon = Icons.Outlined.Forum
    ) {
        when (ui.status) {
            CommentListStatus.IDLE,
            CommentListStatus.LOADING -> CommentLoadingBlock()

            CommentListStatus.EMPTY -> {
                SongEncyclopediaActionSpacer()
                SongEncyclopediaSectionAction(
                    icon = Icons.Outlined.Forum,
                    text = stringResource(CoreCommonR.string.encyclopedia_comment_show_all),
                    onClick = onOpenAll
                )
            }

            CommentListStatus.ERROR -> Column(modifier = Modifier.fillMaxWidth()) {
                SongEncyclopediaRetryAction(onRetry = { commentViewModel.retry() })
                SongEncyclopediaActionSpacer()
                SongEncyclopediaSectionAction(
                    icon = Icons.Outlined.Forum,
                    text = stringResource(CoreCommonR.string.encyclopedia_comment_show_all),
                    onClick = onOpenAll
                )
            }

            CommentListStatus.SUCCESS -> {
                ui.total?.let { total ->
                    Text(
                        text = stringResource(CoreCommonR.string.comment_total_format, total.toString()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 只有 3 条预览，用普通 Column 即可。
                // 外层是 Column(Modifier.verticalScroll())，此处若用 LazyColumn 会因无限高度约束崩溃。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ui.comments.take(COMMENT_PREVIEW_LIMIT).forEachIndexed { index, comment ->
                        key(comment.id) {
                            CommentItem(
                                comment = comment,
                                floor = index + 1,
                                offlineMode = offlineMode,
                                isLiking = comment.id in ui.likingIds,
                                likeEnabled = ui.previewLikesEnabled,
                                onLike = { commentViewModel.toggleLike(comment.id) },
                                onReply = {},
                                onToggleReplies = {},
                                repliesExpanded = false,
                                hasReplyThread = false,
                                replyEnabled = false,
                                likingIds = ui.likingIds,
                                onLikeReply = commentViewModel::toggleLike,
                                showReplyActions = false
                            )
                        }
                    }
                }
                SongEncyclopediaCommentLikeError(ui, commentViewModel::dismissLikeError)
                SongEncyclopediaActionSpacer()
                SongEncyclopediaSectionAction(
                    icon = Icons.Outlined.Forum,
                    text = stringResource(CoreCommonR.string.encyclopedia_comment_show_all),
                    onClick = onOpenAll
                )
            }
        }
    }
}

private val CommentUiState.previewLikesEnabled: Boolean
    get() = !isRefreshing && !isCheckingCache && !isLoadingMore && !isSending && pendingSort == null

@Composable
private fun SongEncyclopediaCommentLikeError(ui: CommentUiState, onDismiss: () -> Unit) {
    val error = ui.likeError ?: return
    val message = stringResource(
        when {
            error == CommentError.PERMISSION -> CoreCommonR.string.comment_like_login_required
            ui.source?.platform == CommentPlatform.NETEASE && ui.likeErrorCode == 250 ->
                CoreCommonR.string.comment_like_verification
            else -> CoreCommonR.string.comment_like_failed
        }
    )
    val failureText = ui.likeErrorCode?.let {
        stringResource(CoreCommonR.string.comment_error_code_format, message, it)
    } ?: message
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = failureText,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        HapticIconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, stringResource(CoreCommonR.string.comment_dismiss_error))
        }
    }
}
