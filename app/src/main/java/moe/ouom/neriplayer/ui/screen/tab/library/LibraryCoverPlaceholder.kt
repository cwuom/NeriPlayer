package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import androidx.compose.ui.graphics.Color
import moe.ouom.neriplayer.util.media.fastScrollableImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

/** Which tonal family a placeholder tile draws from */
internal enum class LibraryCoverTone { Neutral, Accent, Favorite }

/**
 * Stand-in artwork for playlists without a cover. Same footprint and radius as a real cover so
 * rows stay aligned; the analytic rounded shape keeps scrolling lists on the fast clip path
 */
@Composable
internal fun LibraryCoverPlaceholder(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Rounded.QueueMusic,
    tone: LibraryCoverTone = LibraryCoverTone.Neutral,
    size: Dp = 56.dp
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val (container, content) = when (tone) {
        LibraryCoverTone.Accent -> if (clean) {
            colors.primary.copy(alpha = 0.14f) to colors.primary
        } else {
            colors.primaryContainer to colors.onPrimaryContainer
        }
        // 红心收藏沿用错误色系, 跟随主题明暗而不是写死粉色
        LibraryCoverTone.Favorite -> if (clean) {
            colors.error.copy(alpha = 0.12f) to colors.error
        } else {
            colors.errorContainer to colors.onErrorContainer
        }
        LibraryCoverTone.Neutral -> if (clean) {
            colors.onSurface.copy(alpha = 0.06f) to colors.onSurfaceVariant
        } else {
            colors.secondaryContainer to colors.onSecondaryContainer
        }
    }
    val shape = neriRoundedShape(8.dp)
    val glyph: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.46f)
        )
    }
    // 简洁风格有背景图时新建入口走毛玻璃, 和同屏的分段标签一致; 只给这一块, 长列表里的占位封面不开
    if (clean && tone == LibraryCoverTone.Accent && LocalHasCustomBackground.current) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = modifier
                .size(size)
                .clip(shape),
            shape = shape,
            fallbackColor = colors.surfaceContainerLow.copy(alpha = 0.5f),
            tintColor = colors.surfaceContainerLow
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(container),
                contentAlignment = Alignment.Center
            ) { glyph() }
        }
        return
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center
    ) { glyph() }
}

/** Playlist row artwork with a size-bounded request so long lists decode small bitmaps */
@Composable
internal fun LibraryPlaylistCoverImage(
    cover: String,
    offlineMode: Boolean,
    size: Dp = 56.dp
) {
    val context = LocalContext.current
    val request = remember(context, cover, offlineMode) {
        fastScrollableImageRequest(
            context = context,
            data = cover,
            sizePx = 160,
            offlineMode = offlineMode
        )
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(neriRoundedShape(8.dp))
    )
}
