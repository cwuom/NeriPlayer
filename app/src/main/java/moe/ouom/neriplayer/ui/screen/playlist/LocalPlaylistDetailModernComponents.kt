package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.setValue
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollBackdrop
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassOverscrollBackdrop
import moe.ouom.neriplayer.ui.effect.glass.drawAdvancedGlassOverscrollBackdrop
import moe.ouom.neriplayer.util.format.formatPlayCount
import moe.ouom.neriplayer.data.model.artwork.CoverArtColorSample
import moe.ouom.neriplayer.util.media.CoverArtColorCache
import moe.ouom.neriplayer.util.media.normalizeCoverArtColorCacheKey
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt

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
        // 调用端统一应用缓动曲线，避免弹簧进度再次缓动后在中段突然加速
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
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

@Stable
internal class PlaylistSearchUiState(
    val visible: MutableState<Boolean>,
    val query: MutableState<String>
)

@Composable
internal fun rememberPlaylistSearchUiState(): PlaylistSearchUiState {
    val visible = rememberSaveable { mutableStateOf(false) }
    val query = rememberSaveable { mutableStateOf("") }
    return remember(visible, query) { PlaylistSearchUiState(visible, query) }
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
    LaunchedEffect(query, block = syncPlaylistSearchInput(inputState, lastSynchronizedQueryState, query))
    LaunchedEffect(
        inputState.value,
        block = debouncePlaylistSearchQuery(inputState, query, delayMillis, onQueryChange)
    )
    return inputState
}

private fun syncPlaylistSearchInput(
    inputState: MutableState<TextFieldValue>,
    lastSynchronizedQueryState: MutableState<String>,
    query: String
): suspend CoroutineScope.() -> Unit = {
    resolvePlaylistSearchInputSyncValue(
        inputValue = inputState.value,
        lastSynchronizedQuery = lastSynchronizedQueryState.value,
        query = query
    )?.let { synchronizedValue ->
        inputState.value = synchronizedValue
    }
    lastSynchronizedQueryState.value = query
}

private fun debouncePlaylistSearchQuery(
    inputState: MutableState<TextFieldValue>,
    query: String,
    delayMillis: Long,
    onQueryChange: (String) -> Unit
): suspend CoroutineScope.() -> Unit = {
    val pendingQuery = inputState.value.text
    if (pendingQuery != query) {
        delay(delayMillis.milliseconds)
        if (inputState.value.text == pendingQuery) {
            onQueryChange(pendingQuery)
        }
    }
}

internal fun shouldBuildPlaylistSearchIndex(
    searchVisible: Boolean,
    query: String
): Boolean = searchVisible || query.isNotBlank()

@Composable
private fun playlistSearchLocaleTags(): String {
    val configuration = Configuration(LocalConfiguration.current)
    configuration.setLocales(LocalResources.current.configuration.locales)
    return configuration.locales.toLanguageTags()
}

@Composable
internal fun rememberPlaylistSearchContext(): Context {
    val context = LocalContext.current
    val localeTags = playlistSearchLocaleTags()
    return remember(context, localeTags) {
        val configuration = Configuration().apply {
            setLocales(LocaleList.forLanguageTags(localeTags))
        }
        context.createConfigurationContext(configuration)
    }
}

private class PlaylistSearchIndexState<T>(
    val owner: Any,
    val index: SearchTextMatcher.Index<T>
)

private class PlaylistSearchResultState<T>(
    val index: PlaylistSearchIndexState<T>,
    val query: String,
    val items: List<T>
)

@Composable
internal fun <T> rememberPlaylistSearchResults(
    query: String,
    items: List<T>,
    tokens: (T) -> Iterable<Any?>,
    buildIndex: Boolean = true
): List<T> {
    if (!buildIndex) return items

    val localeTags = playlistSearchLocaleTags()
    val indexOwner = remember(items, localeTags) { Any() }
    val preparedIndex by produceState<PlaylistSearchIndexState<T>?>(null, indexOwner) {
        value = withContext(Dispatchers.Default) {
            PlaylistSearchIndexState(indexOwner, SearchTextMatcher.index(items, tokens))
        }
    }
    val activeIndex = preparedIndex?.takeIf { it.owner === indexOwner }
    val displayedItems by produceState<PlaylistSearchResultState<T>?>(
        initialValue = null,
        key1 = activeIndex,
        key2 = query
    ) {
        value = activeIndex?.let { index ->
            PlaylistSearchResultState(index, query, rankPlaylistSearchResults(items, index.index, query))
        }
    }
    if (query.isBlank()) return items
    return displayedItems?.takeIf { it.index === activeIndex && it.query == query }?.items.orEmpty()
}

private suspend fun <T> rankPlaylistSearchResults(
    items: List<T>,
    index: SearchTextMatcher.Index<T>?,
    query: String
): List<T> = if (index == null) items else withContext(Dispatchers.Default) { index.filterAndRank(query) }

private val PlaylistHeroSearchTopPadding = 22.dp
private val PlaylistDockedSearchTopPadding = 10.dp
private val PlaylistDockedSearchBottomPadding = 8.dp
private val PlaylistSearchFieldMinHeight = 60.dp
internal val PlaylistModernDockedSearchSlotHeight =
    PlaylistDockedSearchTopPadding +
        PlaylistSearchFieldMinHeight +
        PlaylistDockedSearchBottomPadding
private val PlaylistActionBarHeight = 48.dp
private val PlaylistPlayPillMaxWidth = 320.dp
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
        Player.REPEAT_MODE_ALL -> CoreCommonR.string.playlist_mode_repeat_all
        Player.REPEAT_MODE_ONE -> CoreCommonR.string.playlist_mode_repeat_one
        else -> CoreCommonR.string.playlist_mode_repeat_off
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

private fun PlaylistHeroVisualColors?.accentOr(fallback: Color): Color = this?.accent ?: fallback

private fun PlaylistHeroVisualColors?.readableAccentOr(fallback: Color): Color =
    this?.readableAccent ?: fallback

private fun PlaylistHeroVisualColors?.backgroundOr(fallback: Color): Color = this?.background ?: fallback

private fun Modifier.focusRequesterIfPresent(focusRequester: FocusRequester?): Modifier =
    if (focusRequester == null) this else focusRequester(focusRequester)

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
    LaunchedEffect(
        context,
        colorCacheKey,
        offlineMode,
        block = loadPlaylistHeroColorSample(
            context = context,
            coverModel = normalizedCoverModel,
            hasCoverModel = hasCoverModel,
            offlineMode = offlineMode,
            colorSampleState = colorSampleState
        )
    )
    val coverColorArgb = resolvePlaylistHeroCoverColorArgb(
        hasCoverModel = hasCoverModel,
        cachedSample = cachedColorSample,
        loadedSample = colorSampleState
    )
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
    return PlaylistHeroVisualColors(
        background = backgroundColor,
        accent = accentColor,
        readableAccent = readableAccentColor,
        controlContent = playlistHeroControlContentColor(isDarkTheme)
    )
}

private fun loadPlaylistHeroColorSample(
    context: Context,
    coverModel: String,
    hasCoverModel: Boolean,
    offlineMode: Boolean,
    colorSampleState: MutableState<CoverArtColorSample?>
): suspend CoroutineScope.() -> Unit = {
    colorSampleState.value = if (hasCoverModel) {
        CoverArtColorCache.getOrLoad(
            context = context,
            coverUrl = coverModel,
            offlineMode = offlineMode
        )
    } else {
        null
    }
}

internal fun resolvePlaylistHeroCoverColorArgb(
    hasCoverModel: Boolean,
    cachedSample: CoverArtColorSample?,
    loadedSample: State<CoverArtColorSample?>
): Int? = if (hasCoverModel) (cachedSample ?: loadedSample.value)?.baseColorArgb else null

internal fun playlistHeroControlContentColor(isDarkTheme: Boolean): Color = if (isDarkTheme) {
    Color.White.copy(alpha = 0.94f)
} else {
    Color(0xFF191712)
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
    val overscrollOffset = remember { mutableFloatStateOf(0f) }
    val overscrollBackdrop = remember(overscrollOffset, visualColors.background) {
        AdvancedGlassOverscrollBackdrop(
            color = visualColors.background,
            offsetY = overscrollOffset
        )
    }
    Box(
        modifier = Modifier
            .drawAdvancedGlassOverscrollBackdrop(overscrollBackdrop)
    ) {
        CompositionLocalProvider(
            LocalPlaylistHeroVisualColors provides visualColors,
            LocalAdvancedGlassOverscrollBackdrop provides overscrollBackdrop
        ) {
            content()
        }
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
        start = visualColors.accentOr(MaterialTheme.colorScheme.primary),
        end = visualColors.readableAccentOr(MaterialTheme.colorScheme.primary),
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
                .focusRequesterIfPresent(focusRequester)
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
    metaItems: List<PlaylistHeroMeta>? = null,
    coverContentDescription: String = displayName,
    actionsRevealProgress: Float = 1f,
    actions: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val resolvedMetaItems = metaItems ?: remember(subtitle) { playlistHeroMetaFromSubtitle(subtitle) }
    val normalizedCoverModel = normalizeLocalPlaylistHeaderCoverModel(coverUrl)
    val visualColors = rememberPlaylistHeroVisualColors(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    )

    // 背景图模式也用实色头部, 封面色和标题不受背景图干扰
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .background(visualColors.background)
    ) {
        val clean = NeriDesign.isClean
        val configuration = LocalConfiguration.current
        val inPane = LocalPlaylistHeroInPane.current
        val coverPlaceholderColor = lerp(visualColors.background, Color.White, 0.12f)
        val wide = !inPane && configuration.screenWidthDp >= PlaylistWideHeroMinWidthDp
        // 横屏平板高度有限, 封面按屏高封顶, 首屏仍能露出播放按钮和第一首歌
        val maxCoverByHeight = (configuration.screenHeightDp * PlaylistHeroCoverMaxHeightFraction).dp
        val heroCoverRequest = remember(context, normalizedCoverModel, offlineMode) {
            offlineCachedImageRequest(
                context = context,
                data = normalizedCoverModel,
                sizePx = 480,
                allowHardware = false,
                crossfade = true,
                offlineMode = offlineMode
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = PlaylistWideContentMaxWidth)
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    // 侧栏卡片里封面四周留白一致, 顶部不再贴着卡片上沿
                    top = if (inPane) 20.dp else 8.dp,
                    end = 20.dp,
                    // 侧栏里操作区和头部同色相连, 只留胶囊和按钮之间的正常间距
                    bottom = if (inPane) 4.dp else if (clean) 24.dp else 22.dp
                )
        ) {
            if (inPane) {
                // 平板侧栏里竖排: 封面铺满侧栏宽度, 标题在下, 两种风格只差封面圆角
                PlaylistCleanHeroIdentity(
                    displayName = displayName,
                    metaItems = resolvedMetaItems,
                    placeholderColor = coverPlaceholderColor,
                    coverRequest = heroCoverRequest,
                    coverContentDescription = coverContentDescription,
                    coverSize = PlaylistPaneCoverSize
                        .coerceAtMost((configuration.screenHeightDp * PlaylistPaneCoverMaxHeightFraction).dp),
                    materialCover = !clean
                )
            } else if (clean) {
                PlaylistCleanHeroIdentity(
                    displayName = displayName,
                    metaItems = resolvedMetaItems,
                    placeholderColor = coverPlaceholderColor,
                    coverRequest = heroCoverRequest,
                    coverContentDescription = coverContentDescription,
                    coverSize = (if (wide) 220.dp else 188.dp).coerceAtMost(maxCoverByHeight)
                )
            } else {
                PlaylistMaterialHeroIdentity(
                    displayName = displayName,
                    metaItems = resolvedMetaItems,
                    placeholderColor = coverPlaceholderColor,
                    coverRequest = heroCoverRequest,
                    coverContentDescription = coverContentDescription,
                    coverSize = (if (wide) 168.dp else 124.dp).coerceAtMost(maxCoverByHeight)
                )
            }
            CompositionLocalProvider(LocalPlaylistHeroVisualColors provides visualColors) {
                if (actions != null) {
                    Box(
                        modifier = Modifier
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                // 完整测量输入框，只展开槽位高度，避免首帧撑满自适应头部
                                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                                val revealedHeight = (placeable.height * actionsRevealProgress.coerceIn(0f, 1f)).roundToInt()
                                layout(placeable.width, constraints.constrainHeight(revealedHeight)) {
                                    placeable.placeRelative(0, 0)
                                }
                            }
                            .padding(top = PlaylistHeroSearchTopPadding)
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}

/** 本地歌单头部: 时长, 首数, 播放量各一枚胶囊; 播放量只显示数字, 读屏读完整说法 */
@Composable
internal fun localPlaylistHeroMeta(
    totalDurationText: String,
    songCount: Int,
    playCountText: String
): List<PlaylistHeroMeta> {
    val songCountText = pluralStringResource(CoreCommonR.plurals.playlist_meta_song_count, songCount, songCount)
    val playCountDescription = stringResource(CoreCommonR.string.playlist_meta_play_count, playCountText)
    return remember(totalDurationText, songCountText, playCountText, playCountDescription) {
        listOf(
            PlaylistHeroMeta(text = totalDurationText, icon = Icons.Rounded.Schedule),
            PlaylistHeroMeta(text = songCountText, icon = Icons.Rounded.MusicNote),
            PlaylistHeroMeta(
                text = playCountText,
                icon = Icons.Rounded.Headphones,
                contentDescription = playCountDescription
            )
        )
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
    actionsRevealProgress: Float = 1f,
    actions: (@Composable () -> Unit)? = null
) {
    val playCountText = formatPlayCount(LocalContext.current, playCount)
    PlaylistModernHeroHeader(
        displayName = displayName,
        coverUrl = headerCover,
        subtitle = stringResource(
            CoreCommonR.string.local_playlist_total_duration,
            totalDurationText,
            songCount,
            playCountText
        ),
        offlineMode = offlineMode,
        height = height,
        metaItems = localPlaylistHeroMeta(totalDurationText, songCount, playCountText),
        actionsRevealProgress = actionsRevealProgress,
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
    val accentColor = visualColors.accentOr(MaterialTheme.colorScheme.primary)
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
                .focusRequesterIfPresent(focusRequester)
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
        playlistColor = visualColors.backgroundOr(MaterialTheme.colorScheme.primary)
    )
    val glassStyle = resolvePlaylistSearchGlassStyle(
        glassColor = glassColor,
        playlistColor = visualColors?.background,
        isDarkSurface = playlistModernUsesDarkSurface(),
        progress = 1f,
        fallbackContentColor = playlistModernSheetContentColor(),
        fallbackAccentColor = visualColors.readableAccentOr(MaterialTheme.colorScheme.primary)
    )
    val contentColor = glassStyle.contentColor
    val accentColor = glassStyle.accentColor

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
                    .focusRequesterIfPresent(focusRequester)
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
    dockedProgress: Float,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    inputState: MutableState<TextFieldValue>? = null
) {
    val slotProgress = revealProgress.coerceIn(0f, 1f)
    val slotAlpha = resolvePlaylistEasedProgress(slotProgress)
    if (!shouldComposePlaylistSearchSlot(searchVisible = false, visibilityProgress = slotProgress)) {
        return
    }
    val density = LocalDensity.current
    PlaylistModernVisualColorsProvider(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    ) {
        val visualColors = LocalPlaylistHeroVisualColors.current
        val glassColor = playlistModernDockedSearchGlassColor(
            playlistColor = visualColors.backgroundOr(MaterialTheme.colorScheme.primary)
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
    if (LocalPlaylistHeroInPane.current) {
        // 平板侧栏: 操作区直接铺在头部色上, 和封面标题连成一张卡, 不再叠一层带圆角的玻璃 sheet
        val onHero = visualColors.copy(controlContent = Color.White)
        CompositionLocalProvider(LocalPlaylistHeroVisualColors provides onHero) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .background(visualColors.background)
                    .padding(bottom = 10.dp)
            ) {
                content()
            }
        }
        return
    }
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
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(modifier = Modifier.widthIn(max = PlaylistWideContentMaxWidth).fillMaxWidth()) {
                        content()
                    }
                }
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
        // 宽屏上背景照常铺满, 行内容限宽居中, 避免时长和菜单被甩到屏幕最右
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = PlaylistWideContentMaxWidth)
                    .fillMaxWidth(),
                content = content
            )
        }
    }
}

/** Content column of playlist details on wide windows; phones are narrower and never hit it */
internal val PlaylistWideContentMaxWidth = 880.dp

/**
 * 歌单操作栏: 左侧切换随机模式, 中间一枚带文字的主播放胶囊跟随模式, 右侧多选和更多;
 * Material 用胶囊和圆钮, Clean 用小圆角矩形, 循环模式和导出都收进更多菜单
 */
@Composable
internal fun PlaylistModernPlaybackActions(
    songCount: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    modifier: Modifier = Modifier,
    onPlayInOrder: () -> Unit,
    onShufflePlay: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onExportToLocalPlaylist: () -> Unit,
    onEnterSelection: (() -> Unit)? = null
) {
    val clean = NeriDesign.isClean
    val canUseSongs = songCount > 0
    val visualColors = LocalPlaylistHeroVisualColors.current
    val onHero = LocalPlaylistHeroInPane.current
    // 侧栏里按钮落在深色头部上, 封面取色的强调色常和底色太近, 主按钮改用白底头部色字
    val accentColor = if (onHero) {
        Color.White
    } else {
        visualColors.readableAccentOr(MaterialTheme.colorScheme.primary)
    }
    val onAccentColor = if (onHero) {
        visualColors.backgroundOr(Color.Black)
    } else {
        resolvePlaylistContentColor(accentColor)
    }
    val shape = if (clean) PlaylistCleanActionShape else CircleShape
    var showMenu by remember { mutableStateOf(false) }

    PlaylistPlaybackBarLayout(
        spacing = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 8.dp),
        leading = {
            PlaylistGroupIconButton(
                imageVector = Icons.Outlined.Shuffle,
                contentDescription = stringResource(playlistShuffleModeLabelRes(shuffleEnabled)),
                enabled = canUseSongs,
                active = shuffleEnabled,
                shape = shape,
                onClick = onToggleShuffle
            )
        },
        pill = {
            PlaylistPlayButton(
                label = stringResource(playlistPlayLabelRes(shuffleEnabled)),
                icon = if (shuffleEnabled) Icons.Outlined.Shuffle else Icons.Filled.PlayArrow,
                enabled = canUseSongs,
                containerColor = accentColor,
                contentColor = onAccentColor,
                shape = shape,
                onClick = if (shuffleEnabled) onShufflePlay else onPlayInOrder
            )
        },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onEnterSelection != null) {
                    PlaylistGroupIconButton(
                        imageVector = Icons.Outlined.Checklist,
                        contentDescription = stringResource(CoreCommonR.string.playlist_multi_select),
                        enabled = canUseSongs,
                        shape = shape,
                        onClick = onEnterSelection
                    )
                }
                Box {
                    PlaylistGroupIconButton(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = stringResource(CoreCommonR.string.playlist_playback_more_options),
                        shape = shape,
                        onClick = { showMenu = true }
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(playlistRepeatModeLabelRes(repeatMode))) },
                            leadingIcon = { Icon(playlistRepeatModeIcon(repeatMode), contentDescription = null) },
                            // 循环模式连续切换时保持菜单展开, 文字直接反映当前档位
                            onClick = onCycleRepeatMode
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(CoreCommonR.string.playlist_export_to_local)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
                            },
                            enabled = shouldEnableLocalPlaylistQuickExport(songCount),
                            onClick = {
                                showMenu = false
                                onExportToLocalPlaylist()
                            }
                        )
                    }
                }
            }
        }
    )
}

/** 主按钮放得下文字时排成一行; 大字号或窄宽度下主按钮独占一行, 次要按钮换到下一行 */
@Composable
private fun PlaylistPlaybackBarLayout(
    spacing: Dp,
    modifier: Modifier,
    leading: @Composable () -> Unit,
    pill: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Layout(
        contents = listOf(leading, pill, trailing),
        modifier = modifier
    ) { (leadingMeasurables, pillMeasurables, trailingMeasurables), constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceables = leadingMeasurables.map { it.measure(loose) }
        val trailingPlaceables = trailingMeasurables.map { it.measure(loose) }
        val leadingWidth = leadingPlaceables.sumOf { it.width }
        val trailingWidth = trailingPlaceables.sumOf { it.width }
        val sideHeight = (leadingPlaceables + trailingPlaceables).maxOfOrNull { it.height } ?: 0
        val pillNeeded = pillMeasurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val sideWidth = leadingWidth + trailingWidth + gap * 2
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else sideWidth + pillNeeded
        // 平板单列时可用宽度很大, 主按钮封顶, 整组靠起始边排列
        val inlinePillWidth = (width - sideWidth).coerceAtMost(PlaylistPlayPillMaxWidth.roundToPx())
        val inline = inlinePillWidth > 0 && pillNeeded <= inlinePillWidth
        val pillWidth = if (inline) inlinePillWidth else width
        val pillPlaceables = pillMeasurables.map { it.measure(Constraints.fixedWidth(pillWidth)) }
        val pillHeight = pillPlaceables.maxOfOrNull { it.height } ?: 0
        val height = if (inline) maxOf(sideHeight, pillHeight) else pillHeight + gap + sideHeight
        layout(width, height) {
            if (inline) {
                var x = 0
                leadingPlaceables.forEach { it.placeRelative(x, (height - it.height) / 2); x += it.width }
                x += gap
                pillPlaceables.forEach { it.placeRelative(x, (height - it.height) / 2) }
                x += pillWidth + gap
                trailingPlaceables.forEach { it.placeRelative(x, (height - it.height) / 2); x += it.width }
            } else {
                pillPlaceables.forEach { it.placeRelative(0, 0) }
                val rowTop = pillHeight + gap
                var x = 0
                leadingPlaceables.forEach {
                    it.placeRelative(x, rowTop + (sideHeight - it.height) / 2)
                    x += it.width
                }
                var trailingX = width - trailingWidth
                trailingPlaceables.forEach {
                    it.placeRelative(trailingX, rowTop + (sideHeight - it.height) / 2)
                    trailingX += it.width
                }
            }
        }
    }
}

/** 主播放胶囊: 图标加一个短标签, 标签过长时省略而不挤压两侧按钮 */
@Composable
private fun PlaylistPlayButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .height(PlaylistActionBarHeight)
            .clip(shape)
            .background(if (enabled) containerColor else containerColor.copy(alpha = containerColor.alpha * 0.4f))
            .clickable(enabled = enabled, role = Role.Button) {
                context.performHapticFeedback()
                onClick()
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlaylistGroupIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    active: Boolean = false,
    shape: Shape = CircleShape,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val visualColors = LocalPlaylistHeroVisualColors.current
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = visualColors.compactIconContentColor(colorScheme, enabled, active)
    val container = visualColors.compactIconContainerColor(colorScheme, active, LocalPlaylistHeroInPane.current)

    // 不用 IconButton: 它会把触控区撑到 48dp 并画出超出底色的水波纹, 相邻按钮按下时互相压住
    Box(
        modifier = Modifier
            .size(PlaylistActionBarHeight)
            .clip(shape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button) {
                context.performHapticFeedback()
                onClick()
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

private val PlaylistCleanActionShape = RoundedCornerShape(14.dp)

private fun PlaylistHeroVisualColors?.compactIconContainerColor(
    colorScheme: ColorScheme,
    active: Boolean,
    onHero: Boolean
): Color = when {
    this == null && active -> colorScheme.primary.copy(alpha = 0.18f)
    this == null -> colorScheme.onSurface.copy(alpha = 0.09f)
    active -> accent.copy(alpha = 0.24f)
    onHero -> Color.White.copy(alpha = 0.14f)
    else -> controlContent.copy(alpha = 0.09f)
}

private fun PlaylistHeroVisualColors?.compactIconContentColor(
    colorScheme: ColorScheme,
    enabled: Boolean,
    active: Boolean
): Color {
    if (this == null) {
        return when {
            !enabled -> colorScheme.onSurface.copy(alpha = 0.32f)
            active -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        }
    }
    return when {
        !enabled -> controlContent.copy(alpha = 0.32f)
        active -> readableAccent
        else -> controlContent.copy(alpha = 0.82f)
    }
}

internal fun playlistPlayLabelRes(shuffleEnabled: Boolean): Int = if (shuffleEnabled) {
    CoreCommonR.string.player_shuffle_play
} else {
    CoreCommonR.string.player_play_all
}

internal fun playlistShuffleModeLabelRes(shuffleEnabled: Boolean): Int = if (shuffleEnabled) {
    CoreCommonR.string.playlist_mode_shuffle
} else {
    CoreCommonR.string.playlist_mode_order
}

internal fun playlistRepeatModeIcon(repeatMode: Int): ImageVector =
    if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Outlined.Repeat

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
    onExportToLocalPlaylist: () -> Unit,
    onEnterSelection: (() -> Unit)? = null
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
        onExportToLocalPlaylist = onExportToLocalPlaylist,
        onEnterSelection = onEnterSelection
    )
}
