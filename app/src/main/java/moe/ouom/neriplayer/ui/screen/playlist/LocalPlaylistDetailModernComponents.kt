package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

internal const val PLAYLIST_HEADER_KEY = "header"
internal const val PLAYLIST_ACTIONS_KEY = "playlist_actions"
internal const val LOCAL_PLAYLIST_HEADER_KEY = PLAYLIST_HEADER_KEY
internal const val LOCAL_PLAYLIST_ACTIONS_KEY = PLAYLIST_ACTIONS_KEY
internal const val LOCAL_PLAYLIST_METADATA_PROCESSING_KEY = "metadata_processing_card"
internal val PlaylistModernHeroHeight = 122.dp
internal val PlaylistModernHeroSearchHeight = 190.dp

private val PlaylistHeroCoverSize = 88.dp
private val PlaylistHeroCoverCornerRadius = 14.dp
private val PlaylistHeroSearchTopPadding = 14.dp
private val PlaylistDockedSearchTopPadding = 10.dp
private val PlaylistActionBarHeight = 44.dp
private val PlaylistCompactActionButtonSize = 40.dp
private val PlaylistSearchFieldShape = RoundedCornerShape(18.dp)
private val PlaylistActionSheetCornerRadius = 28.dp
private val PlaylistActionSheetShape = RoundedCornerShape(
    topStart = PlaylistActionSheetCornerRadius,
    topEnd = PlaylistActionSheetCornerRadius,
    bottomEnd = 0.dp,
    bottomStart = 0.dp
)
private const val PlaylistSheetLightAlpha = 0.18f
private const val PlaylistSheetDarkAlpha = 0.24f

internal val LOCAL_PLAYLIST_FIXED_ITEM_KEYS = setOf(
    LOCAL_PLAYLIST_HEADER_KEY,
    LOCAL_PLAYLIST_ACTIONS_KEY,
    LOCAL_PLAYLIST_METADATA_PROCESSING_KEY
)

internal fun resolveLocalPlaylistPlayingItemIndex(
    songIndex: Int,
    metadataProcessingVisible: Boolean
): Int {
    return resolveLocalPlaylistSongListIndex(songIndex, metadataProcessingVisible)
}

internal fun resolveLocalPlaylistSongListIndex(
    songIndex: Int,
    metadataProcessingVisible: Boolean
): Int {
    return resolvePlaylistSongItemIndex(
        songIndex = songIndex,
        fixedItemCount = 2 + if (metadataProcessingVisible) 1 else 0
    )
}

internal fun resolvePlaylistSongItemIndex(
    songIndex: Int,
    fixedItemCount: Int = 2
): Int {
    if (songIndex < 0) return -1
    return songIndex + fixedItemCount.coerceAtLeast(0)
}

internal fun resolvePlaylistPlaybackStartIndex(
    songCount: Int,
    shuffleEnabled: Boolean,
    randomIndex: Int
): Int {
    if (songCount <= 0) return -1
    return if (shuffleEnabled) randomIndex.coerceIn(0, songCount - 1) else 0
}

internal fun shouldEnableLocalPlaylistQuickExport(songCount: Int): Boolean {
    return songCount > 0
}

internal fun localPlaylistRepeatModeLabelRes(repeatMode: Int): Int {
    return playlistRepeatModeLabelRes(repeatMode)
}

internal fun playlistRepeatModeLabelRes(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_ALL -> R.string.playlist_mode_repeat_all
        Player.REPEAT_MODE_ONE -> R.string.playlist_mode_repeat_one
        else -> R.string.playlist_mode_repeat_off
    }
}

internal fun resolvePlaylistHeroBackgroundArgb(
    coverColorArgb: Int?,
    fallbackArgb: Int,
    isDarkTheme: Boolean
): Int {
    val source = coverColorArgb ?: fallbackArgb
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(source, hsl)
    val targetSaturation = (hsl[1] * 0.52f).coerceIn(0.12f, 0.42f)
    val targetLightness = if (isDarkTheme) 0.20f else 0.36f
    val tonal = ColorUtils.HSLToColor(floatArrayOf(hsl[0], targetSaturation, targetLightness))
    return ColorUtils.blendARGB(
        tonal,
        0xFF000000.toInt(),
        if (isDarkTheme) 0.18f else 0.08f
    )
}

internal fun resolvePlaylistHeroAccentArgb(
    coverColorArgb: Int?,
    fallbackArgb: Int,
    isDarkTheme: Boolean
): Int {
    val source = coverColorArgb ?: fallbackArgb
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(source, hsl)
    val targetSaturation = hsl[1].coerceIn(0.34f, 0.78f)
    val targetLightness = if (isDarkTheme) 0.62f else 0.48f
    return ColorUtils.HSLToColor(floatArrayOf(hsl[0], targetSaturation, targetLightness))
}

private data class PlaylistHeroVisualColors(
    val background: Color,
    val accent: Color,
    val readableAccent: Color,
    val controlContent: Color
)

private val LocalPlaylistHeroVisualColors = staticCompositionLocalOf<PlaylistHeroVisualColors?> {
    null
}

@Composable
private fun rememberResolvedPlaylistHeroVisualColors(
    coverUrl: String?,
    offlineMode: Boolean
): PlaylistHeroVisualColors {
    val context = LocalContext.current
    val isDarkTheme = playlistModernUsesDarkSurface()
    val normalizedCoverModel = normalizeLocalPlaylistHeaderCoverModel(coverUrl)
    val fallbackArgb = MaterialTheme.colorScheme.primary.toArgb()
    val cachedColorSample = remember(normalizedCoverModel) {
        CoverArtColorCache.peek(normalizedCoverModel)
    }
    val colorSampleState = remember(normalizedCoverModel) {
        mutableStateOf(cachedColorSample)
    }
    LaunchedEffect(context, normalizedCoverModel, offlineMode) {
        colorSampleState.value = CoverArtColorCache.getOrLoad(
            context = context,
            coverUrl = normalizedCoverModel,
            offlineMode = offlineMode
        )
    }
    val coverColorArgb = colorSampleState.value?.baseColorArgb
    val backgroundColor by animateColorAsState(
        targetValue = Color(
            resolvePlaylistHeroBackgroundArgb(
                coverColorArgb = coverColorArgb,
                fallbackArgb = fallbackArgb,
                isDarkTheme = isDarkTheme
            )
        ),
        label = "playlist-hero-background"
    )
    val accentColor by animateColorAsState(
        targetValue = Color(
            resolvePlaylistHeroAccentArgb(
                coverColorArgb = coverColorArgb,
                fallbackArgb = fallbackArgb,
                isDarkTheme = isDarkTheme
            )
        ),
        label = "playlist-hero-accent"
    )
    val readableAccentColor by animateColorAsState(
        targetValue = resolveReadablePlaylistAccentColor(accentColor, isDarkTheme),
        label = "playlist-readable-accent"
    )
    val controlContentColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.94f)
    } else {
        Color(0xFF191712)
    }
    return PlaylistHeroVisualColors(
        background = backgroundColor,
        accent = accentColor,
        readableAccent = readableAccentColor,
        controlContent = controlContentColor
    )
}

@Composable
private fun rememberPlaylistHeroVisualColors(
    coverUrl: String?,
    offlineMode: Boolean
): PlaylistHeroVisualColors {
    return LocalPlaylistHeroVisualColors.current
        ?: rememberResolvedPlaylistHeroVisualColors(
            coverUrl = coverUrl,
            offlineMode = offlineMode
        )
}

@Composable
internal fun PlaylistModernVisualColorsProvider(
    coverUrl: String?,
    offlineMode: Boolean,
    content: @Composable () -> Unit
) {
    val visualColors = rememberResolvedPlaylistHeroVisualColors(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    )
    CompositionLocalProvider(LocalPlaylistHeroVisualColors provides visualColors) {
        content()
    }
}

@Composable
internal fun rememberPlaylistModernHeroBackgroundColor(
    coverUrl: String?,
    offlineMode: Boolean
): Color {
    return rememberPlaylistHeroVisualColors(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    ).background
}

private fun resolveReadablePlaylistAccentColor(
    accentColor: Color,
    isDarkTheme: Boolean
): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(accentColor.toArgb(), hsl)
    hsl[1] = hsl[1].coerceIn(0.36f, 0.82f)
    hsl[2] = if (isDarkTheme) {
        hsl[2].coerceAtLeast(0.66f)
    } else {
        hsl[2].coerceAtMost(0.38f)
    }
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
private fun playlistModernSheetFallbackColor(hasCustomBackground: Boolean): Color {
    return if (playlistModernUsesDarkSurface()) {
        if (hasCustomBackground) {
            Color.Black.copy(alpha = PlaylistSheetDarkAlpha)
        } else {
            playlistModernListContainerColor()
        }
    } else {
        Color.White.copy(alpha = PlaylistSheetLightAlpha)
    }
}

@Composable
private fun playlistModernSheetTintColor(hasCustomBackground: Boolean): Color {
    return if (playlistModernUsesDarkSurface()) {
        if (hasCustomBackground) {
            Color.Black
        } else {
            playlistModernListContainerColor()
        }
    } else {
        Color.White
    }
}

@Composable
private fun playlistModernSheetContentColor(): Color {
    return if (playlistModernUsesDarkSurface()) {
        Color.White.copy(alpha = 0.92f)
    } else {
        Color(0xFF191712)
    }
}

@Composable
internal fun playlistModernCollapsedTopBarColor(): Color {
    return Color.Transparent
}

@Composable
internal fun playlistModernCollapsedTopBarContentColor(): Color {
    return playlistModernListPrimaryContentColor()
}

@Composable
internal fun playlistModernListPrimaryContentColor(): Color {
    return if (playlistModernUsesDarkSurface()) {
        Color.White.copy(alpha = 0.95f)
    } else {
        Color(0xFF17191F)
    }
}

@Composable
internal fun playlistModernListSecondaryContentColor(): Color {
    return if (playlistModernUsesDarkSurface()) {
        Color.White.copy(alpha = 0.72f)
    } else {
        Color(0xFF4C505B)
    }
}

@Composable
internal fun playlistModernListTertiaryContentColor(): Color {
    return if (playlistModernUsesDarkSurface()) {
        Color.White.copy(alpha = 0.62f)
    } else {
        Color(0xFF5E6270)
    }
}

@Composable
private fun playlistModernListContainerColor(): Color {
    return MaterialTheme.colorScheme.background
}

@Composable
private fun playlistModernUsesDarkSurface(): Boolean {
    return ColorUtils.calculateLuminance(
        playlistModernListContainerColor().toArgb()
    ) < 0.5
}

@Composable
internal fun PlaylistModernHeroHeader(
    displayName: String,
    coverUrl: String?,
    subtitle: String,
    offlineMode: Boolean,
    height: Dp,
    coverContentDescription: String = displayName,
    actions: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val normalizedCoverModel = normalizeLocalPlaylistHeaderCoverModel(coverUrl)
    val visualColors = rememberPlaylistHeroVisualColors(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(visualColors.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = normalizedCoverModel,
                        sizePx = 320,
                        allowHardware = false,
                        crossfade = true,
                        offlineMode = offlineMode
                    ),
                    contentDescription = coverContentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(PlaylistHeroCoverSize)
                        .clip(RoundedCornerShape(PlaylistHeroCoverCornerRadius))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.22f),
                                offset = Offset(1f, 1f),
                                blurRadius = 3f
                            )
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.76f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            CompositionLocalProvider(LocalPlaylistHeroVisualColors provides visualColors) {
                if (actions != null) {
                    Box(modifier = Modifier.padding(top = PlaylistHeroSearchTopPadding)) {
                        actions()
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocalPlaylistHeroHeader(
    displayName: String,
    headerCover: String?,
    totalDurationText: String,
    songCount: Int,
    offlineMode: Boolean,
    height: Dp,
    actions: (@Composable () -> Unit)? = null
) {
    PlaylistModernHeroHeader(
        displayName = displayName,
        coverUrl = headerCover,
        subtitle = stringResource(
            R.string.local_playlist_total_duration,
            totalDurationText,
            songCount
        ),
        offlineMode = offlineMode,
        height = height,
        actions = actions
    )
}

@Composable
internal fun PlaylistModernHeroSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val visualColors = LocalPlaylistHeroVisualColors.current
    val accentColor = visualColors?.accent ?: MaterialTheme.colorScheme.primary
    val focusModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier.fillMaxWidth(),
        shape = PlaylistSearchFieldShape,
        fallbackColor = Color.White.copy(alpha = 0.16f),
        tintColor = Color.White.copy(alpha = 0.28f)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .then(focusModifier),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null
                )
            },
            placeholder = {
                Text(
                    text = placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = PlaylistSearchFieldShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White.copy(alpha = 0.94f),
                cursorColor = accentColor,
                focusedBorderColor = Color.White.copy(alpha = 0.42f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedLeadingIconColor = Color.White.copy(alpha = 0.86f),
                unfocusedLeadingIconColor = Color.White.copy(alpha = 0.74f),
                focusedPlaceholderColor = Color.White.copy(alpha = 0.70f),
                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.62f)
            )
        )
    }
}

@Composable
internal fun PlaylistModernDockedSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val visualColors = LocalPlaylistHeroVisualColors.current
    val contentColor = playlistModernSheetContentColor()
    val accentColor = visualColors?.readableAccent ?: MaterialTheme.colorScheme.primary
    val focusModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                top = PlaylistDockedSearchTopPadding,
                end = 16.dp,
                bottom = 8.dp
            )
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.SemanticCard,
            modifier = Modifier.fillMaxWidth(),
            shape = PlaylistSearchFieldShape,
            fallbackColor = playlistModernSheetFallbackColor(hasCustomBackground = false),
            tintColor = playlistModernSheetTintColor(hasCustomBackground = false)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .then(focusModifier),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
                    )
                },
                placeholder = {
                    Text(
                        text = placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = PlaylistSearchFieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = contentColor,
                    unfocusedTextColor = contentColor.copy(alpha = 0.92f),
                    cursorColor = accentColor,
                    focusedBorderColor = accentColor.copy(alpha = 0.58f),
                    unfocusedBorderColor = contentColor.copy(alpha = 0.20f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedLeadingIconColor = contentColor.copy(alpha = 0.84f),
                    unfocusedLeadingIconColor = contentColor.copy(alpha = 0.68f),
                    focusedPlaceholderColor = contentColor.copy(alpha = 0.58f),
                    unfocusedPlaceholderColor = contentColor.copy(alpha = 0.50f)
                )
            )
        }
    }
}

@Composable
internal fun PlaylistModernActionSheet(
    coverUrl: String?,
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = PlaylistActionSheetShape,
    cornerGapHeight: Dp = PlaylistActionSheetCornerRadius,
    hasCustomBackground: Boolean = false,
    content: @Composable () -> Unit
) {
    val visualColors = rememberPlaylistHeroVisualColors(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    )
    val fallbackColor = playlistModernSheetFallbackColor(hasCustomBackground)
    val tintColor = playlistModernSheetTintColor(hasCustomBackground)
    val glassEnabled = hasCustomBackground || !playlistModernUsesDarkSurface()
    CompositionLocalProvider(LocalPlaylistHeroVisualColors provides visualColors) {
        Box(
            modifier = modifier
                .fillMaxWidth()
        ) {
            PlaylistActionSheetCornerGapLayer(
                shape = shape,
                color = visualColors.background,
                gapHeight = cornerGapHeight
            )
            AdvancedGlassSurface(
                role = AdvancedGlassRole.PlaylistSheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = shape,
                fallbackColor = fallbackColor,
                tintColor = tintColor,
                enabled = glassEnabled
            ) {
                content()
            }
        }
    }
}

@Composable
private fun BoxScope.PlaylistActionSheetCornerGapLayer(
    shape: Shape,
    color: Color,
    gapHeight: Dp
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .drawWithCache {
                val cornerGapHeightPx = gapHeight.toPx().coerceIn(0f, size.height)
                val gapPath = Path().apply {
                    addRect(Rect(0f, 0f, size.width, cornerGapHeightPx))
                }
                val sheetPath = Path().apply {
                    addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
                }
                val cornerGapPath = Path.combine(
                    operation = PathOperation.Difference,
                    path1 = gapPath,
                    path2 = sheetPath
                )
                onDrawBehind {
                    drawPath(
                        path = cornerGapPath,
                        color = color
                    )
                }
            }
    )
}

@Composable
internal fun PlaylistModernListItemSurface(
    coverUrl: String?,
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val visualColors = rememberPlaylistHeroVisualColors(coverUrl, offlineMode)
    CompositionLocalProvider(LocalPlaylistHeroVisualColors provides visualColors) {
        Box(
            modifier = modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
internal fun PlaylistModernPlaybackActions(
    songCount: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    modifier: Modifier = Modifier,
    exportEnabled: Boolean = shouldEnableLocalPlaylistQuickExport(songCount),
    onPlayInOrder: () -> Unit,
    onShufflePlay: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onExportToLocalPlaylist: () -> Unit
) {
    val canUseSongs = songCount > 0
    val visualColors = LocalPlaylistHeroVisualColors.current
    val accentColor = visualColors?.readableAccent ?: MaterialTheme.colorScheme.primary
    val onAccentColor = resolvePlaylistContentColor(accentColor)
    val controlContentColor = visualColors?.controlContent
        ?: MaterialTheme.colorScheme.onSurface
    val playLabel = if (shuffleEnabled) {
        stringResource(R.string.player_shuffle_play)
    } else {
        stringResource(R.string.player_play_all)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 8.dp, end = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HapticFilledIconButton(
            onClick = {
                if (shuffleEnabled) {
                    onShufflePlay()
                } else {
                    onPlayInOrder()
                }
            },
            enabled = canUseSongs,
            modifier = Modifier.size(PlaylistActionBarHeight),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accentColor,
                contentColor = onAccentColor
            )
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = playLabel,
                modifier = Modifier.size(26.dp)
            )
        }

        Text(
            text = playLabel,
            style = MaterialTheme.typography.titleMedium,
            color = controlContentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistCompactIconButton(
                imageVector = Icons.Outlined.Shuffle,
                contentDescription = if (shuffleEnabled) {
                    stringResource(R.string.playlist_mode_shuffle)
                } else {
                    stringResource(R.string.playlist_mode_order)
                },
                enabled = canUseSongs,
                active = shuffleEnabled,
                onClick = onToggleShuffle
            )
            PlaylistCompactIconButton(
                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Outlined.Repeat
                },
                contentDescription = stringResource(playlistRepeatModeLabelRes(repeatMode)),
                active = repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeatMode
            )
            PlaylistCompactIconButton(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = stringResource(R.string.playlist_export_to_local),
                enabled = canUseSongs && exportEnabled,
                onClick = onExportToLocalPlaylist,
            )
        }
    }
}

@Composable
private fun PlaylistCompactIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val visualColors = LocalPlaylistHeroVisualColors.current
    val containerColor = when {
        active -> visualColors?.accent?.copy(alpha = 0.24f)
            ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else -> (visualColors?.controlContent ?: MaterialTheme.colorScheme.onSurface)
            .copy(alpha = 0.09f)
    }
    val contentColor = when {
        !enabled -> (visualColors?.controlContent ?: MaterialTheme.colorScheme.onSurface)
            .copy(alpha = 0.32f)
        active -> visualColors?.readableAccent ?: MaterialTheme.colorScheme.primary
        else -> visualColors?.controlContent?.copy(alpha = 0.82f)
            ?: MaterialTheme.colorScheme.onSurfaceVariant
    }

    HapticIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(PlaylistCompactActionButtonSize)
            .clip(RoundedCornerShape(22.dp))
            .background(containerColor)
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun resolvePlaylistContentColor(backgroundColor: Color): Color {
    return if (ColorUtils.calculateLuminance(backgroundColor.toArgb()) > 0.48) {
        Color.Black
    } else {
        Color.White
    }
}

@Composable
internal fun LocalPlaylistPlaybackActions(
    songCount: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    modifier: Modifier = Modifier,
    onPlayInOrder: () -> Unit,
    onShufflePlay: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onExportToLocalPlaylist: () -> Unit
) {
    PlaylistModernPlaybackActions(
        songCount = songCount,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        modifier = modifier,
        onPlayInOrder = onPlayInOrder,
        onShufflePlay = onShufflePlay,
        onToggleShuffle = onToggleShuffle,
        onCycleRepeatMode = onCycleRepeatMode,
        onExportToLocalPlaylist = onExportToLocalPlaylist
    )
}
