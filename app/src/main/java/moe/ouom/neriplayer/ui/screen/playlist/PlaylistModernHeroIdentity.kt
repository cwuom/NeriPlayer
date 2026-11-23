package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.ui.theme.NeriDesign

/** Material: 左封面右文字, 标题大而粗, 元信息拆成一排可换行的胶囊 */
@Composable
internal fun PlaylistMaterialHeroIdentity(
    displayName: String,
    metaItems: List<PlaylistHeroMeta>,
    coverRequest: Any,
    coverContentDescription: String,
    coverSize: Dp,
    placeholderColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        PlaylistHeroCover(
            coverRequest = coverRequest,
            contentDescription = coverContentDescription,
            size = coverSize,
            shape = PlaylistMaterialHeroCoverShape,
            elevation = 12.dp,
            placeholderColor = placeholderColor
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    shadow = PlaylistHeroTitleShadow
                ),
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            PlaylistHeroMetaChips(items = metaItems, centered = false)
        }
    }
}

/** Clean: 居中的大封面加居中标题, 像一张专辑封套 */
@Composable
internal fun PlaylistCleanHeroIdentity(
    displayName: String,
    metaItems: List<PlaylistHeroMeta>,
    coverRequest: Any,
    coverContentDescription: String,
    coverSize: Dp,
    placeholderColor: Color,
    materialCover: Boolean = false
) {
    val coverShape = if (materialCover) PlaylistMaterialHeroCoverShape else PlaylistCleanHeroCoverShape
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PlaylistHeroCover(
            coverRequest = coverRequest,
            contentDescription = coverContentDescription,
            size = coverSize,
            shape = coverShape,
            elevation = 20.dp,
            placeholderColor = placeholderColor
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.SemiBold,
                shadow = PlaylistHeroTitleShadow
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (metaItems.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PlaylistHeroMetaChips(items = metaItems, centered = true)
        }
    }
}

/**
 * 封面下面先垫一块不透明的占位色和音符图标: 没有封面或还在加载时,
 * 阴影不会从透明区域透出来变成一圈脏色方框, 图片加载完直接盖住占位
 */
@Composable
private fun PlaylistHeroCover(
    coverRequest: Any,
    contentDescription: String,
    size: Dp,
    shape: Shape,
    elevation: Dp,
    placeholderColor: Color
) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(elevation, shape)
            .clip(shape)
            .background(placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(size * 0.32f)
        )
        AsyncImage(
            model = coverRequest,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().testTag(PlaylistHeroCoverTag)
        )
    }
}

internal const val PlaylistHeroCoverTag = "playlist-hero-cover"

/** 头部元信息的一枚胶囊; 带 [contentDescription] 时读屏只读它, 用于只显示数字的项 */
@Immutable
internal data class PlaylistHeroMeta(
    val text: String,
    val icon: ImageVector? = null,
    val contentDescription: String? = null
)

/** 没有结构化数据的来源按分隔点拆开, 每段一枚无图标胶囊 */
internal fun playlistHeroMetaFromSubtitle(subtitle: String): List<PlaylistHeroMeta> =
    subtitle.split('\u00B7')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { PlaylistHeroMeta(text = it) }

@Composable
internal fun PlaylistHeroMetaChips(
    items: List<PlaylistHeroMeta>,
    centered: Boolean,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val clean = NeriDesign.isClean
    val shape = if (clean) PlaylistCleanHeroMetaShape else CircleShape
    val container = Color.White.copy(alpha = if (clean) 0.12f else 0.16f)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            if (centered) Alignment.CenterHorizontally else Alignment.Start
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            val description = item.contentDescription
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(container)
                    .then(
                        if (description != null) {
                            Modifier.clearAndSetSemantics { contentDescription = description }
                        } else {
                            Modifier.semantics(mergeDescendants = true) {}
                        }
                    )
                    .padding(
                        start = if (item.icon != null) 10.dp else 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item.icon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.82f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.94f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private val PlaylistHeroTitleShadow = Shadow(
    color = Color.Black.copy(alpha = 0.22f),
    offset = Offset(1f, 1f),
    blurRadius = 3f
)
private val PlaylistMaterialHeroCoverShape = RoundedCornerShape(28.dp)
private val PlaylistCleanHeroCoverShape = RoundedCornerShape(14.dp)
private val PlaylistCleanHeroMetaShape = RoundedCornerShape(10.dp)
internal const val PlaylistWideHeroMinWidthDp = 600
internal const val PlaylistHeroCoverMaxHeightFraction = 0.26f
