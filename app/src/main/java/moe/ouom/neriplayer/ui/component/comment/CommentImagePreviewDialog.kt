package moe.ouom.neriplayer.ui.component.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.comments.CommentImage
import moe.ouom.neriplayer.ui.component.overlay.ApplyOverlayWindowNavigationBarPolicy
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/** 预览大图请求的像素边长。 */
private const val CommentImagePreviewRequestSizePx = 1440

/** 预览缩放的上下限, 以及双击切换到的倍率。 */
private const val CommentImagePreviewMinScale = 1f
private const val CommentImagePreviewMaxScale = 4f
private const val CommentImagePreviewDoubleTapScale = 2.5f

/**
 * 评论配图的全屏预览对话框。
 *
 * 左右翻页浏览 [images], 支持双击在 1x / 2.5x 之间切换、放大后双指缩放 (1x..4x) 与拖动平移,
 * 右上角按钮或返回键关闭。
 *
 * 手势说明: 未放大时的单指拖动不消费事件, 交给 [HorizontalPager] 翻页; 双指捏合或已经放大时
 * 才消费事件做缩放 / 平移 (1x..4x), 同时通过 `userScrollEnabled` 暂停翻页, 避免手势互相争抢。
 *
 * @param images 命中的评论配图列表
 * @param initialIndex 进入预览时展示的下标, 越界时收敛到合法范围
 * @param offlineMode 是否处于离线模式, 透传给 Coil 请求以禁用网络缓存
 * @param onDismiss 关闭预览
 */
@Composable
internal fun CommentImagePreviewDialog(
    images: List<CommentImage>,
    initialIndex: Int,
    offlineMode: Boolean,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return

    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size }
    )
    var scale by remember { mutableFloatStateOf(CommentImagePreviewMinScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 翻页后复位变换, 避免把上一张的缩放/位移带到下一张。
    LaunchedEffect(pagerState.currentPage, images.size) {
        scale = CommentImagePreviewMinScale
        offset = Offset.Zero
    }

    // 缩放 / 平移的统一入口: 缩放收敛到 1x..4x, 平移只在放大后生效, 未放大时始终居中
    val applyTransform: (Float, Offset) -> Unit = { zoomChange, panChange ->
        val nextScale = (scale * zoomChange)
            .coerceIn(CommentImagePreviewMinScale, CommentImagePreviewMaxScale)
        scale = nextScale
        offset = if (nextScale > CommentImagePreviewMinScale) offset + panChange else Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ApplyOverlayWindowNavigationBarPolicy()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale <= CommentImagePreviewMinScale
            ) { page ->
                val image = images[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(image.url) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > CommentImagePreviewMinScale) {
                                        scale = CommentImagePreviewMinScale
                                        offset = Offset.Zero
                                    } else {
                                        scale = CommentImagePreviewDoubleTapScale
                                    }
                                }
                            )
                        }
                        .pointerInput(image.url) {
                            // 手势分流: 未放大时的单指拖动完全不消费, 让 [HorizontalPager] 正常翻页;
                            // 双指捏合或已经放大后才消费事件并做缩放 / 平移。
                            // (此前面板只在 scale > 1 时才挂 transformable, 导致未放大时永远无法捏合)
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2 || scale > CommentImagePreviewMinScale) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()
                                        if (zoomChange != 1f || panChange != Offset.Zero) {
                                            applyTransform(zoomChange, panChange)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                                // 手势结束时若已回到未放大状态, 复位位移以保持一致居中
                                if (scale <= CommentImagePreviewMinScale) offset = Offset.Zero
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = remember(context, image.url, offlineMode) {
                            offlineCachedImageRequest(
                                context = context,
                                data = image.url,
                                sizePx = CommentImagePreviewRequestSizePx,
                                offlineMode = offlineMode
                            )
                        },
                        contentDescription = stringResource(
                            CoreCommonR.string.comment_image_content_description
                        ),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                }
            }

            if (images.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(bottom = 20.dp)
                )
            }

            HapticIconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(CoreCommonR.string.comment_image_preview_close),
                    tint = Color.White
                )
            }
        }
    }
}
