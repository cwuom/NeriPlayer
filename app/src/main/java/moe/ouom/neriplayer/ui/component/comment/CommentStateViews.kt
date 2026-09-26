package moe.ouom.neriplayer.ui.component.comment

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

/**
 * 评论错误 -> 本地化文案资源。
 *
 * 状态层只传递语义化错误, 文案一律由 i18n 提供 (§38)。
 */
@StringRes
internal fun commentErrorTextRes(error: CommentError): Int = when (error) {
    CommentError.NETWORK -> R.string.comment_error_network
    CommentError.PERMISSION -> R.string.comment_error_permission
    CommentError.NOT_FOUND -> R.string.comment_error_not_found
    CommentError.CLOSED -> R.string.comment_error_closed
    CommentError.SERVER -> R.string.comment_error_server
    CommentError.API -> R.string.comment_error_unavailable
    CommentError.UNKNOWN -> R.string.comment_error_unknown
}

/**
 * 首屏加载中 (与项目既有 LoadingBlock 保持一致的视觉)。
 */
@Composable
internal fun CommentLoadingBlock() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.comment_loading))
    }
}

/**
 * 暂无评论。
 *
 * 与「加载失败」严格区分 (§32): 请求成功但列表为空才走这里。
 */
@Composable
internal fun CommentEmptyBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.comment_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 首屏加载失败 + 重新加载 (§33)。
 */
@Composable
internal fun CommentErrorBlock(error: CommentError, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(commentErrorTextRes(error)),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        HapticTextButton(onClick = onRetry) {
            Text(stringResource(R.string.comment_retry))
        }
    }
}

/**
 * 翻页加载中。
 */
@Composable
internal fun CommentLoadingMoreRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

/**
 * 翻页失败: 保留已经加载出来的评论, 只提示这一页失败 (§30)。
 */
@Composable
internal fun CommentLoadMoreErrorRow(error: CommentError, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(commentErrorTextRes(error)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        HapticTextButton(onClick = onRetry) {
            Text(stringResource(R.string.comment_retry))
        }
    }
}
