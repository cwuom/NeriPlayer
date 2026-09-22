package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.comment.model.SongComment
import moe.ouom.neriplayer.util.format.formatDate
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/**
 * 单条评论。
 *
 * 展示: 头像 / 用户名 / 用户等级(可选) / 正文 / 发布时间 / 点赞数 / 回复数(可选)。
 * 图片加载复用项目既有的 Coil 与离线缓存请求 (§27/§28), 不引入任何新的图片库。
 */
@Composable
internal fun CommentItem(
    comment: SongComment,
    offlineMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val anonymous = stringResource(R.string.comment_anonymous_user)
    val username = comment.username.trim().ifBlank { anonymous }
    val timeText = comment.createTime
        ?.takeIf { it > 0L }
        ?.let { formatDate(it) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = remember(context, comment.avatarUrl, offlineMode) {
                offlineCachedImageRequest(
                    context = context,
                    data = comment.avatarUrl,
                    sizePx = 96,
                    offlineMode = offlineMode
                )
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = username,
                    style = MaterialTheme.typography.titleSmall
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                comment.userLevel?.let { level ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.comment_user_level_format, level),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = comment.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                timeText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.comment_like_count_format,
                        formatPlayCount(context, comment.likeCount)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                comment.replyCount?.takeIf { it > 0L }?.let { replies ->
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(
                            R.string.comment_reply_count_format,
                            formatPlayCount(context, replies)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
