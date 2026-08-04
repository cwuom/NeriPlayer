package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.util.media.normalizeCoverArtColorCacheKey
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.util.search.SearchTextMatcher

internal const val PLAYLIST_HEADER_KEY = "header"
internal const val PLAYLIST_ACTIONS_KEY = "playlist_actions"
internal const val LOCAL_PLAYLIST_HEADER_KEY = PLAYLIST_HEADER_KEY
internal const val LOCAL_PLAYLIST_ACTIONS_KEY = PLAYLIST_ACTIONS_KEY
internal const val LOCAL_PLAYLIST_METADATA_PROCESSING_KEY = "metadata_processing_card"
internal val PlaylistModernHeroHeight = 122.dp
internal val PlaylistModernHeroSearchHeight = 190.dp
internal val PlaylistModernHeroSearchFieldOffset = 106.dp
private const val PlaylistHeroLightFallbackSeedArgb = 0xFF5F6875.toInt()
private const val PlaylistHeroDarkFallbackSeedArgb = 0xFF303846.toInt()

internal fun shouldRequestPlaylistSearchFocus(
    showSearch: Boolean,
    selectionMode: Boolean,
    autoShowKeyboard: Boolean
): Boolean {
    return showSearch && !selectionMode && autoShowKeyboard
}

internal fun shouldTransferPlaylistSearchFocus(
    showSearch: Boolean,
    selectionMode: Boolean,
    searchFieldComposed: Boolean,
    searchInputFocused: Boolean,
    searchQuery: String
): Boolean {
    return showSearch &&
        !selectionMode &&
        searchFieldComposed &&
        (searchInputFocused || searchQuery.isNotBlank())
}

internal fun shouldShowPlaylistSearch(
    showSearch: Boolean,
    selectionMode: Boolean
): Boolean {
    return showSearch && !selectionMode
}

internal fun resolvePlaylistSearchFieldOffsetPx(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    expandedOffsetPx: Int
): Int {
    if (firstVisibleItemIndex > 0) return 0
    return (expandedOffsetPx - firstVisibleItemScrollOffsetPx).coerceAtLeast(0)
}

internal fun resolvePlaylistHeroFallbackSeedArgb(isDarkTheme: Boolean): Int {
    return if (isDarkTheme) {
        PlaylistHeroDarkFallbackSeedArgb
    } else {
        PlaylistHeroLightFallbackSeedArgb
    }
}

internal fun shouldComposePlaylistSearchSlot(
    searchVisible: Boolean,
    visibilityProgress: Float
): Boolean {
    return searchVisible || visibilityProgress > 0.001f
}

@Composable
internal fun playlistModernSearchVisibilityProgress(
    searchVisible: Boolean,
    label: String
): Float {
    val progress by animateFloatAsState(
        targetValue = if (searchVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = label
    )
    return progress.coerceIn(0f, 1f)
}

internal fun resolvePlaylistEasedProgress(progress: Float): Float {
    return FastOutSlowInEasing.transform(progress.coerceIn(0f, 1f))
}

internal fun resolvePlaylistDockedSearchSlotProgress(
    searchVisibilityProgress: Float,
    dockedRevealProgress: Float
): Float {
    return searchVisibilityProgress.coerceIn(0f, 1f) *
        resolvePlaylistEasedProgress(dockedRevealProgress)
}

internal fun resolvePlaylistHeaderSearchAlpha(
    searchVisibilityProgress: Float,
    chromeCollapseProgress: Float
): Float {
    return resolvePlaylistEasedProgress(searchVisibilityProgress) *
        (1f - resolvePlaylistEasedProgress(chromeCollapseProgress))
}

internal fun resolvePlaylistSearchDockedProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    expandedOffsetPx: Int
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (expandedOffsetPx <= 0) return 0f
    return (firstVisibleItemScrollOffsetPx.toFloat() / expandedOffsetPx)
        .coerceIn(0f, 1f)
}

internal fun resolvePlaylistDockedSearchRevealProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    revealDistancePx: Int
): Float {
    if (firstVisibleItemIndex <= 1) return 0f
    if (firstVisibleItemIndex > 2) return 1f
    if (revealDistancePx <= 0) return 1f
    return (firstVisibleItemScrollOffsetPx.toFloat() / revealDistancePx)
        .coerceIn(0f, 1f)
}

internal fun resolvePlaylistDockedSearchGlassColor(
    playlistColor: Color,
    isDarkSurface: Boolean
): Color {
    val playlistLuminance = playlistColor.luminance()
    return when {
        isDarkSurface && playlistLuminance > 0.58f -> Color.White.copy(alpha = 0.14f)
        isDarkSurface -> Color.Black.copy(alpha = 0.28f)
        else -> Color.White.copy(alpha = if (playlistLuminance < 0.34f) 0.42f else 0.50f)
    }
}

@Composable
internal fun playlistModernDockedSearchGlassColor(
    playlistColor: Color
): Color {
    return resolvePlaylistDockedSearchGlassColor(
        playlistColor = playlistColor,
        isDarkSurface = playlistModernUsesDarkSurface()
    )
}

internal fun resolvePlaylistSearchListTopPaddingPx(
    searchVisible: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    revealDistancePx: Int,
    dockedSlotHeightPx: Int
): Int {
    if (!searchVisible) return 0
    val safeDockedSlotHeight = dockedSlotHeightPx.coerceAtLeast(0)
    if (firstVisibleItemIndex <= 1) return 0
    if (firstVisibleItemIndex > 2) return safeDockedSlotHeight
    if (revealDistancePx <= 0) return safeDockedSlotHeight
    val progress = (firstVisibleItemScrollOffsetPx.toFloat() / revealDistancePx)
        .coerceIn(0f, 1f)
    return (safeDockedSlotHeight * progress).toInt()
}

internal fun resolvePlaylistSearchInputSyncValue(
    inputValue: TextFieldValue,
    lastSynchronizedQuery: String,
    query: String
): TextFieldValue? {
    return when {
        inputValue.composition == null && inputValue.text == lastSynchronizedQuery -> TextFieldValue(
            text = query,
            selection = TextRange(query.length)
        )
        inputValue.composition == null &&
            query.isBlank() &&
            inputValue.text.isBlank() -> TextFieldValue(
            text = query,
            selection = TextRange.Zero
        )
        else -> null
    }
}

@Composable
internal fun rememberPlaylistSearchInputState(
    query: String,
    onQueryChange: (String) -> Unit,
    delayMillis: Long = 80L
): MutableState<TextFieldValue> {
    val inputState = remember {
        mutableStateOf(
            TextFieldValue(
                text = query,
                selection = TextRange(query.length)
            )
        )
    }
    val lastSynchronizedQueryState = remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        resolvePlaylistSearchInputSyncValue(
            inputValue = inputState.value,
            lastSynchronizedQuery = lastSynchronizedQueryState.value,
            query = query
        )?.let { synchronizedValue ->
            inputState.value = synchronizedValue
        }
        lastSynchronizedQueryState.value = query
    }
    LaunchedEffect(inputState.value) {
        val pendingQuery = inputState.value.text
        if (pendingQuery == query) return@LaunchedEffect
        delay(delayMillis)
        if (inputState.value.text == pendingQuery) {
            onQueryChange(pendingQuery)
        }
    }
    return inputState
}

internal fun shouldBuildPlaylistSearchIndex(
    searchVisible: Boolean,
    query: String
): Boolean = searchVisible || query.isNotBlank()

@Composable
internal fun <T> rememberPlaylistSearchResults(
    query: String,
    items: List<T>,
    tokens: (T) -> Iterable<Any?>,
    buildIndex: Boolean = true
): List<T> {
    if (!buildIndex) return items

    val indexState = produceState<SearchTextMatcher.Index<T>?>(
        initialValue = null,
        key1 = items
    ) {
        value = withContext(Dispatchers.Default) {
            SearchTextMatcher.index(items, tokens)
        }
    }
    val displayedItems by produceState(
        initialValue = items,
        key1 = items,
        key2 = indexState.value,
        key3 = query
    ) {
        val index = indexState.value
        value = if (index == null) {
            items
        } else {
            withContext(Dispatchers.Default) {
                index.filterAndRank(query)
            }
        }
    }
    return displayedItems
}

private val PlaylistHeroCoverSize = 88.dp
private val PlaylistHeroCoverCornerRadius = 14.dp
private val PlaylistHeroSearchTopPadding = 14.dp
private val PlaylistDockedSearchTopPadding = 10.dp
private val PlaylistDockedSearchBottomPadding = 8.dp
private val PlaylistSearchFieldMinHeight = 60.dp
internal val PlaylistModernDockedSearchSlotHeight =
    PlaylistDockedSearchTopPadding +
        PlaylistSearchFieldMinHeight +
        PlaylistDockedSearchBottomPadding
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

internal fun resolvePlaylistDetailCoverUrl(
    headerCoverUrl: String?,
    fallbackCoverUrl: String?
): String? {
    return normalizePlaylistDetailCoverUrl(headerCoverUrl)
        ?: normalizePlaylistDetailCoverUrl(fallbackCoverUrl)
}

private fun normalizePlaylistDetailCoverUrl(coverUrl: String?): String? {
    return coverUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
}

internal fun resolvePlaylistChromeCollapseProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    expandedHeroHeightPx: Int
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (expandedHeroHeightPx <= 0) return 0f
    return (firstVisibleItemScrollOffsetPx.toFloat() / expandedHeroHeightPx)
        .coerceIn(0f, 1f)
}

internal fun interpolatePlaylistDp(
    start: Dp,
    end: Dp,
    fraction: Float
): Dp {
    val progress = fraction.coerceIn(0f, 1f)
    return start + (end - start) * progress
}

internal fun interpolatePlaylistColor(
    start: Color,
    end: Color,
    fraction: Float
): Color {
    return Color(
        ColorUtils.blendARGB(
            start.toArgb(),
            end.toArgb(),
            fraction.coerceIn(0f, 1f)
        )
    )
}

private data class PlaylistHeroVisualColors(
    val background: Color,
    val accent: Color,
    val readableAccent: Color,
    val controlContent: Color
)

private data class PlaylistSearchGlassStyle(
    val fallbackColor: Color,
    val tintColor: Color,
    val contentColor: Color,
    val accentColor: Color,
    val focusedBorderColor: Color,
    val unfocusedBorderColor: Color
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
    val colorCacheKey = normalizeCoverArtColorCacheKey(normalizedCoverModel)
        ?: normalizedCoverModel
    val fallbackArgb = resolvePlaylistHeroFallbackSeedArgb(isDarkTheme)
    val cachedColorSample = remember(colorCacheKey) {
        CoverArtColorCache.peek(normalizedCoverModel)
    }
    val colorSampleState = remember {
        mutableStateOf(cachedColorSample)
    }
    val hasCoverModel = !coverUrl.isNullOrBlank()
    LaunchedEffect(context, colorCacheKey, offlineMode) {
        if (!hasCoverModel) {
            colorSampleState.value = null
            return@LaunchedEffect
        }
        colorSampleState.value = CoverArtColorCache.getOrLoad(
            context = context,
            coverUrl = normalizedCoverModel,
            offlineMode = offlineMode
        )
    }
    val coverColorArgb = if (hasCoverModel) {
        (cachedColorSample ?: colorSampleState.value)?.baseColorArgb
    } else {
        null
    }
    val backgroundColor by animateColorAsState(
        targetValue = Color(
            resolvePlaylistHeroBackgroundArgb(
                coverColorArgb = coverColorArgb,
                fallbackArgb = fallbackArgb,
                isDarkTheme = isDarkTheme
            )
        ),
        animationSpec = tween(360, easing = FastOutSlowInEasing),
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
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "playlist-hero-accent"
    )
    val readableAccentColor by animateColorAsState(
        targetValue = resolveReadablePlaylistAccentColor(accentColor, isDarkTheme),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
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
    val visualColors = LocalPlaylistHeroVisualColors.current
        ?: rememberResolvedPlaylistHeroVisualColors(
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
private fun resolvePlaylistSearchGlassStyle(
    glassColor: Color?,
    playlistColor: Color?,
    isDarkSurface: Boolean,
    progress: Float,
    fallbackContentColor: Color,
    fallbackAccentColor: Color
): PlaylistSearchGlassStyle {
    if (glassColor == null) {
        return PlaylistSearchGlassStyle(
            fallbackColor = interpolatePlaylistColor(
                start = Color.White.copy(alpha = 0.16f),
                end = playlistModernSheetFallbackColor(hasCustomBackground = false),
                fraction = progress
            ),
            tintColor = interpolatePlaylistColor(
                start = Color.White.copy(alpha = 0.28f),
                end = playlistModernSheetTintColor(hasCustomBackground = false),
                fraction = progress
            ),
            contentColor = fallbackContentColor,
            accentColor = fallbackAccentColor,
            focusedBorderColor = interpolatePlaylistColor(
                start = Color.White.copy(alpha = 0.42f),
                end = fallbackAccentColor.copy(alpha = 0.58f),
                fraction = progress
            ),
            unfocusedBorderColor = interpolatePlaylistColor(
                start = Color.White.copy(alpha = 0.18f),
                end = fallbackContentColor.copy(alpha = 0.20f),
                fraction = progress
            )
        )
    }

    val usesDarkContent = glassColor.luminance() > 0.48f
    val contentColor = if (usesDarkContent) {
        Color(0xFF22252D)
    } else {
        Color.White.copy(alpha = 0.94f)
    }
    val playlistTint = playlistColor?.copy(alpha = if (isDarkSurface) 0.16f else 0.10f)
        ?: glassColor
    return PlaylistSearchGlassStyle(
        fallbackColor = glassColor,
        tintColor = interpolatePlaylistColor(
            start = glassColor,
            end = playlistTint,
            fraction = 0.22f
        ),
        contentColor = contentColor,
        accentColor = contentColor.copy(alpha = 0.92f),
        focusedBorderColor = contentColor.copy(alpha = if (usesDarkContent) 0.20f else 0.18f),
        unfocusedBorderColor = contentColor.copy(alpha = if (usesDarkContent) 0.12f else 0.10f)
    )
}

@Composable
internal fun PlaylistModernStableSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester?,
    dockedProgress: Float,
    modifier: Modifier = Modifier,
    glassColor: Color? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    inputState: MutableState<TextFieldValue>? = null
) {
    val resolvedInputState = inputState ?: rememberPlaylistSearchInputState(
        query = query,
        onQueryChange = onQueryChange
    )
    val progress = dockedProgress.coerceIn(0f, 1f)
    val visualColors = LocalPlaylistHeroVisualColors.current
    val fallbackContentColor = interpolatePlaylistColor(
        start = Color.White,
        end = playlistModernSheetContentColor(),
        fraction = progress
    )
    val fallbackAccentColor = interpolatePlaylistColor(
        start = visualColors?.accent ?: MaterialTheme.colorScheme.primary,
        end = visualColors?.readableAccent ?: MaterialTheme.colorScheme.primary,
        fraction = progress
    )
    val glassStyle = resolvePlaylistSearchGlassStyle(
        glassColor = glassColor,
        playlistColor = visualColors?.background,
        isDarkSurface = playlistModernUsesDarkSurface(),
        progress = progress,
        fallbackContentColor = fallbackContentColor,
        fallbackAccentColor = fallbackAccentColor
    )
    val contentColor = glassStyle.contentColor
    val accentColor = glassStyle.accentColor
    val fieldModifier = modifier
        .fillMaxWidth()
        .padding(
            start = interpolatePlaylistDp(20.dp, 16.dp, progress),
            top = interpolatePlaylistDp(0.dp, PlaylistDockedSearchTopPadding, progress),
            end = interpolatePlaylistDp(20.dp, 16.dp, progress),
            bottom = interpolatePlaylistDp(0.dp, PlaylistDockedSearchBottomPadding, progress)
        )
    val searchFieldContent: @Composable () -> Unit = {
        OutlinedTextField(
            value = resolvedInputState.value,
            onValueChange = { resolvedInputState.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(PlaylistSearchFieldMinHeight)
                .let { baseModifier ->
                    if (focusRequester == null) {
                        baseModifier
                    } else {
                        baseModifier.focusRequester(focusRequester)
                    }
                }
                .onFocusChanged { state -> onFocusChanged?.invoke(state.isFocused) },
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
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = PlaylistSearchFieldShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = contentColor,
                unfocusedTextColor = contentColor.copy(alpha = 0.94f),
                cursorColor = accentColor,
                focusedBorderColor = glassStyle.focusedBorderColor,
                unfocusedBorderColor = glassStyle.unfocusedBorderColor,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedLeadingIconColor = contentColor.copy(alpha = 0.86f),
                unfocusedLeadingIconColor = contentColor.copy(alpha = 0.74f),
                focusedPlaceholderColor = contentColor.copy(alpha = 0.70f),
                unfocusedPlaceholderColor = contentColor.copy(alpha = 0.62f)
            )
        )
    }
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = fieldModifier,
        shape = PlaylistSearchFieldShape,
        fallbackColor = glassStyle.fallbackColor,
        tintColor = glassStyle.tintColor
    ) {
        searchFieldContent()
    }
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
internal fun playlistModernExpandedTopBarColor(
    playlistColor: Color
): Color {
    return resolvePlaylistTranslucentTopBarColor(
        playlistColor = playlistColor,
        collapseProgress = 0f
    )
}

@Composable
internal fun playlistModernCollapsedTopBarColor(
    playlistColor: Color? = null
): Color {
    return playlistColor?.let {
        resolvePlaylistTranslucentTopBarColor(
            playlistColor = it,
            collapseProgress = 1f
        )
    } ?: Color.Transparent
}

@Composable
internal fun playlistModernCollapsedTopBarContentColor(
    playlistColor: Color? = null
): Color {
    if (playlistColor == null) return playlistModernListPrimaryContentColor()
    return resolvePlaylistSolidTopBarContentColor(playlistColor)
}

internal fun resolvePlaylistSolidTopBarContentColor(
    playlistColor: Color
): Color {
    return if (playlistColor.luminance() > 0.48f) {
        Color(0xFF17191F)
    } else {
        Color.White.copy(alpha = 0.95f)
    }
}

internal fun resolvePlaylistTranslucentTopBarColor(
    playlistColor: Color,
    collapseProgress: Float
): Color {
    val progress = collapseProgress.coerceIn(0f, 1f)
    val alpha = 1f - progress
    return playlistColor.copy(alpha = alpha)
}

internal fun resolvePlaylistSelectionTopBarColor(
    playlistColor: Color,
    collapseProgress: Float
): Color {
    val progress = collapseProgress.coerceIn(0f, 1f)
    return if (progress < 1f) playlistColor else Color.Transparent
}

internal fun resolvePlaylistSelectionTopBarContentColor(
    playlistColor: Color,
    collapsedContentColor: Color,
    collapseProgress: Float
): Color {
    val progress = collapseProgress.coerceIn(0f, 1f)
    return if (progress < 1f) {
        resolvePlaylistSolidTopBarContentColor(playlistColor)
    } else {
        collapsedContentColor
    }
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
    playCount: Long,
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
            songCount,
            formatPlayCount(LocalContext.current, playCount)
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
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    inputState: MutableState<TextFieldValue>? = null
) {
    val resolvedInputState = inputState ?: rememberPlaylistSearchInputState(
        query = query,
        onQueryChange = onQueryChange
    )
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
            value = resolvedInputState.value,
            onValueChange = { resolvedInputState.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(PlaylistSearchFieldMinHeight)
                .then(focusModifier)
                .onFocusChanged { state -> onFocusChanged?.invoke(state.isFocused) },
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
            textStyle = MaterialTheme.typography.bodyLarge,
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
    focusRequester: FocusRequester? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    inputState: MutableState<TextFieldValue>? = null
) {
    val resolvedInputState = inputState ?: rememberPlaylistSearchInputState(
        query = query,
        onQueryChange = onQueryChange
    )
    val visualColors = LocalPlaylistHeroVisualColors.current
    val glassColor = playlistModernDockedSearchGlassColor(
        playlistColor = visualColors?.background ?: MaterialTheme.colorScheme.primary
    )
    val glassStyle = resolvePlaylistSearchGlassStyle(
        glassColor = glassColor,
        playlistColor = visualColors?.background,
        isDarkSurface = playlistModernUsesDarkSurface(),
        progress = 1f,
        fallbackContentColor = playlistModernSheetContentColor(),
        fallbackAccentColor = visualColors?.readableAccent ?: MaterialTheme.colorScheme.primary
    )
    val contentColor = glassStyle.contentColor
    val accentColor = glassStyle.accentColor
    val focusModifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                top = PlaylistDockedSearchTopPadding,
                end = 16.dp,
                bottom = PlaylistDockedSearchBottomPadding
            )
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.SemanticCard,
            modifier = Modifier.fillMaxWidth(),
            shape = PlaylistSearchFieldShape,
            fallbackColor = glassStyle.fallbackColor,
            tintColor = glassStyle.tintColor
        ) {
            OutlinedTextField(
                value = resolvedInputState.value,
                onValueChange = { resolvedInputState.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlaylistSearchFieldMinHeight)
                    .then(focusModifier)
                    .onFocusChanged { state -> onFocusChanged?.invoke(state.isFocused) },
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
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = PlaylistSearchFieldShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = contentColor,
                    unfocusedTextColor = contentColor.copy(alpha = 0.92f),
                    cursorColor = accentColor,
                    focusedBorderColor = glassStyle.focusedBorderColor,
                    unfocusedBorderColor = glassStyle.unfocusedBorderColor,
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
internal fun PlaylistModernDockedSearchSlot(
    revealProgress: Float,
    coverUrl: String?,
    offlineMode: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    dockedProgress: Float = revealProgress,
    inputState: MutableState<TextFieldValue>? = null
) {
    val slotProgress = revealProgress.coerceIn(0f, 1f)
    val slotAlpha = resolvePlaylistEasedProgress(slotProgress)
    if (!shouldComposePlaylistSearchSlot(
            searchVisible = slotProgress > 0.001f,
            visibilityProgress = slotProgress
        )
    ) {
        return
    }
    val density = LocalDensity.current
    PlaylistModernVisualColorsProvider(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    ) {
        val visualColors = LocalPlaylistHeroVisualColors.current
        val glassColor = playlistModernDockedSearchGlassColor(
            playlistColor = visualColors?.background ?: MaterialTheme.colorScheme.primary
        )
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(
                    interpolatePlaylistDp(
                        start = 0.dp,
                        end = PlaylistModernDockedSearchSlotHeight,
                        fraction = slotProgress
                    )
                )
                .clipToBounds()
                .graphicsLayer {
                    alpha = slotAlpha
                }
        ) {
            PlaylistModernStableSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = placeholder,
                focusRequester = focusRequester,
                onFocusChanged = onFocusChanged,
                dockedProgress = dockedProgress,
                glassColor = glassColor,
                modifier = Modifier.graphicsLayer {
                    translationY = with(density) {
                        ((1f - slotAlpha) * -8.dp.toPx())
                    }
                },
                inputState = inputState
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
