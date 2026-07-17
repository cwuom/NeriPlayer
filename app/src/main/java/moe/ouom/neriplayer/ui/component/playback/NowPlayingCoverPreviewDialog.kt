package moe.ouom.neriplayer.ui.component.playback

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <https://www.gnu.org/licenses/>.
 */

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import kotlin.math.roundToInt

private const val CoverPreviewMinRequestSizePx = 512
private const val CoverPreviewMaxRequestSizePx = 2048
private val CoverPreviewArtworkShape = RoundedCornerShape(28.dp)

@Composable
private fun LightDialogSystemBarIcons() {
    val view = LocalView.current
    SideEffect {
        val dialogWindow = view.findDialogWindowProvider()?.window
            ?: return@SideEffect
        WindowInsetsControllerCompat(
            dialogWindow,
            dialogWindow.decorView
        ).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

private fun View.findDialogWindowProvider(): DialogWindowProvider? {
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) {
            return current
        }
        current = current.parent as? View
    }
    return null
}

@Composable
internal fun NowPlayingCoverPreviewDialog(
    coverUrl: String,
    songName: String,
    offlineMode: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val title = songName.ifBlank { stringResource(R.string.cover_preview_title) }
    var transform by remember(coverUrl) { mutableStateOf(CoverPreviewTransform()) }
    var intrinsicArtworkSize by remember(coverUrl) { mutableStateOf(Size.Unspecified) }
    val backdropModel = remember(context, coverUrl, offlineMode) {
        offlineCachedImageRequest(
            context = context,
            data = coverUrl,
            sizePx = CoverPreviewMinRequestSizePx,
            allowHardware = true,
            offlineMode = offlineMode
        )
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
        LightDialogSystemBarIcons()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
            contentColor = Color.White
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = backdropModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 0.30f
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.52f),
                                    Color.Black.copy(alpha = 0.72f),
                                    Color.Black.copy(alpha = 0.94f)
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = Color.Black.copy(alpha = 0.42f),
                        contentColor = Color.White,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shadowElevation = 10.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = 18.dp,
                                top = 8.dp,
                                end = 8.dp,
                                bottom = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            HapticFilledIconButton(
                                onClick = onDismiss,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.12f),
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.action_close)
                                )
                            }
                        }
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val artworkSize = minOf(maxWidth, maxHeight, 720.dp)
                        val artworkSizePx = with(LocalDensity.current) {
                            artworkSize.toPx().coerceAtLeast(1f)
                        }
                        val requestSizePx = artworkSizePx
                            .roundToInt()
                            .coerceIn(
                                CoverPreviewMinRequestSizePx,
                                CoverPreviewMaxRequestSizePx
                            )
                        val fittedArtworkSizePx = remember(
                            artworkSizePx,
                            intrinsicArtworkSize
                        ) {
                            fitCoverPreviewContentSize(
                                viewportSizePx = artworkSizePx,
                                intrinsicSizePx = intrinsicArtworkSize
                            )
                        }
                        val imageContentDescription = if (songName.isBlank()) {
                            stringResource(R.string.cover_preview_image_content_description)
                        } else {
                            stringResource(
                                R.string.cover_preview_image_content_description_named,
                                songName
                            )
                        }
                        val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                            transform = updateCoverPreviewTransform(
                                current = transform,
                                zoomChange = zoomChange,
                                panChange = panChange,
                                viewportSizePx = artworkSizePx,
                                fittedContentSizePx = fittedArtworkSizePx
                            )
                        }
                        LaunchedEffect(artworkSizePx, fittedArtworkSizePx) {
                            transform = updateCoverPreviewTransform(
                                current = transform,
                                zoomChange = 1f,
                                panChange = Offset.Zero,
                                viewportSizePx = artworkSizePx,
                                fittedContentSizePx = fittedArtworkSizePx
                            )
                        }

                        Surface(
                            modifier = Modifier
                                .size(artworkSize)
                                .pointerInput(coverUrl, artworkSizePx) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            transform = toggleCoverPreviewZoom(transform)
                                        }
                                    )
                                }
                                .transformable(
                                    state = transformableState,
                                    lockRotationOnZoomPan = true
                                ),
                            shape = CoverPreviewArtworkShape,
                            color = Color.Black.copy(alpha = 0.58f),
                            border = BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = 0.14f)
                            ),
                            shadowElevation = 28.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = Color.White.copy(alpha = 0.24f)
                                )
                                AsyncImage(
                                    model = remember(
                                        context,
                                        coverUrl,
                                        requestSizePx,
                                        offlineMode
                                    ) {
                                        offlineCachedImageRequest(
                                            context = context,
                                            data = coverUrl,
                                            sizePx = requestSizePx,
                                            allowHardware = true,
                                            offlineMode = offlineMode
                                        )
                                    },
                                    contentDescription = imageContentDescription,
                                    contentScale = ContentScale.Fit,
                                    onSuccess = { state ->
                                        intrinsicArtworkSize = state.painter.intrinsicSize
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = transform.scale
                                            scaleY = transform.scale
                                            translationX = transform.offset.x
                                            translationY = transform.offset.y
                                        }
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp),
                        shape = RoundedCornerShape(26.dp),
                        color = Color.Black.copy(alpha = 0.44f),
                        contentColor = Color.White,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shadowElevation = 12.dp
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val showZoomHint = maxWidth >= 390.dp
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 10.dp,
                                    vertical = 8.dp
                                ),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.11f),
                                    contentColor = Color.White
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.cover_preview_zoom_percent,
                                            (transform.scale * 100f).roundToInt()
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 8.dp
                                        )
                                    )
                                }

                                if (showZoomHint) {
                                    Text(
                                        text = stringResource(R.string.cover_preview_zoom_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.70f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }

                                HapticFilledIconButton(
                                    onClick = { transform = CoverPreviewTransform() },
                                    enabled = transform.scale > CoverPreviewMinScale,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.11f),
                                        contentColor = Color.White,
                                        disabledContainerColor = Color.White.copy(alpha = 0.06f),
                                        disabledContentColor = Color.White.copy(alpha = 0.30f)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.RestartAlt,
                                        contentDescription = stringResource(
                                            R.string.cover_preview_reset_zoom
                                        )
                                    )
                                }

                                HapticButton(
                                    onClick = onDownload,
                                    modifier = Modifier.heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color.Black
                                    ),
                                    contentPadding = PaddingValues(
                                        horizontal = 16.dp,
                                        vertical = 10.dp
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.action_download_cover))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
