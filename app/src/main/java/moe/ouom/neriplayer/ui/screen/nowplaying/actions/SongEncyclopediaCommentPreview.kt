package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.ui.component.comment.CommentItem
import moe.ouom.neriplayer.ui.viewmodel.CommentListStatus
import moe.ouom.neriplayer.ui.viewmodel.CommentViewModel

private const val COMMENT_PREVIEW_LIMIT = 3

/**
 * 模块六 评论预览（仅网易云）。
 *
 * 只读预览：点赞 / 回复 / 楼中楼全部禁用，避免在百科页里产生副作用操作。
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
    val context = LocalContext.current
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
            CommentListStatus.LOADING -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            CommentListStatus.EMPTY -> {
                SongEncyclopediaEmptyHint(CoreCommonR.string.comment_empty)
                SongEncyclopediaCommentShowAll(onOpenAll)
            }

            CommentListStatus.ERROR -> Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(CoreCommonR.string.encyclopedia_comment_preview_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SongEncyclopediaExpandAction(expanded = false, onToggle = { commentViewModel.retry() })
                    SongEncyclopediaCommentShowAll(onOpenAll)
                }
            }

            CommentListStatus.SUCCESS -> {
                ui.total?.let { total ->
                    Text(
                        text = context.getString(CoreCommonR.string.comment_total_format, total.toString()),
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
                                isLiking = false,
                                likeEnabled = false,
                                onLike = {},
                                onReply = {},
                                onToggleReplies = {},
                                repliesExpanded = false,
                                hasReplyThread = false,
                                replyEnabled = false
                            )
                        }
                    }
                }
                SongEncyclopediaCommentShowAll(onOpenAll)
            }
        }
    }
}

/** 「查看全部评论」入口，复用百科统一的文字按钮样式。 */
@Composable
private fun SongEncyclopediaCommentShowAll(onClick: () -> Unit) {
    Text(
        text = stringResource(CoreCommonR.string.encyclopedia_comment_show_all),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .encyclopediaClickable(onClick)
            .padding(vertical = 8.dp)
    )
}
