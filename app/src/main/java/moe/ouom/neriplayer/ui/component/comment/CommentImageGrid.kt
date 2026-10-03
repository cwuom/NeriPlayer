package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentImage
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/** 缩略图请求的像素边长, 兼顾清晰度与内存占用。 */
private const val CommentImageThumbRequestSizePx = 480

/** 单图展示时占父容器宽度的比例。 */
private const val CommentImageSingleWidthFraction = 0.72f

/** 手写网格的列数与最多展示的图片数量。 */
private const val CommentImageGridColumns = 3
private const val CommentImageGridMaxVisible = 9

/** 单图展示时的最大高度。 */
private val CommentImageSingleMaxHeight = 220.dp

/** 未知尺寸图片的兜底宽高比下限, 避免除零把展示高度算成无穷大。 */
private const val CommentImageMinAspectRatio = 0.01f

/** 网格内部间距。 */
private val CommentImageGridSpacing = 4.dp

/**
 * 评论配图网格。
 *
 * [images] 为空时不渲染任何内容; 单图按平台给出的宽高比展示 (高度不超过 220dp),
 * 多图使用手写的 3 列网格 (最多展示 9 张), 刻意不使用 `LazyVerticalGrid` / `LazyRow`
 * 等可滚动容器, 以免嵌在评论列表的 `LazyColumn` 内产生嵌套滚动。
 *
 * @param images 命中的评论配图列表
 * @param offlineMode 是否处于离线模式, 透传给 Coil 请求以禁用网络缓存
 * @param onImageClick 点击某张图时回调它在整个 [images] 列表里的下标
 * @param modifier 外部修饰符
 */
@Composable
internal fun CommentImageGrid(
    images: List<CommentImage>,
    offlineMode: Boolean,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    if (images.size == 1) {
        val image = images.first()
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth(CommentImageSingleWidthFraction)
                .clip(MaterialTheme.shapes.medium)
                .clickable { onImageClick(0) }
        ) {
            // 按可用宽度显式算出展示高度: 长图 (宽高比很小) 收敛到 220dp 并用 Crop 裁剪。
            // 不再依赖 aspectRatio + heightIn 的约束协商, 避免图片撑出上限后与下方正文重叠 (真机反馈 #4)
            val thumbHeight = minOf(
                maxWidth / image.aspectRatio.coerceAtLeast(CommentImageMinAspectRatio),
                CommentImageSingleMaxHeight
            )
            CommentImageThumb(
                image = image,
                offlineMode = offlineMode,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight)
            )
        }
        return
    }

    val visibleImages = images.take(CommentImageGridMaxVisible)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CommentImageGridSpacing)
    ) {
        visibleImages.chunked(CommentImageGridColumns).forEachIndexed { rowIndex, rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CommentImageGridSpacing)
            ) {
                rowImages.forEachIndexed { columnIndex, image ->
                    val imageIndex = rowIndex * CommentImageGridColumns + columnIndex
                    CommentImageThumb(
                        image = image,
                        offlineMode = offlineMode,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onImageClick(imageIndex) }
                    )
                }
                // 最后一行不足 3 张时用等宽占位补齐, 保证每格边长一致。
                repeat(CommentImageGridColumns - rowImages.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 网格/单图里的一张缩略图, 走离线缓存图片请求。 */
@Composable
private fun CommentImageThumb(
    image: CommentImage,
    offlineMode: Boolean,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(context, image.url, offlineMode) {
            offlineCachedImageRequest(
                context = context,
                data = image.url,
                sizePx = CommentImageThumbRequestSizePx,
                offlineMode = offlineMode
            )
        },
        contentDescription = stringResource(CoreCommonR.string.comment_image_content_description),
        contentScale = contentScale,
        modifier = modifier
    )
}
