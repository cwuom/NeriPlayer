package moe.ouom.neriplayer.ui.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.media3.common.Player
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.queue.heartbeat.HeartbeatMode
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.PreferredQualityKeys
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueDisplayState
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassSceneOpacity
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.screen.nowplaying.LocalNowPlayingPlaybackSource
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingPlaybackSource
import moe.ouom.neriplayer.ui.screen.nowplaying.NowPlayingScreen
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsLoadStages
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsSources
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsStages
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.biasCoverSeedTowardBlue
import moe.ouom.neriplayer.util.media.CoverArtColorCache

private val StartupPreviewLyricRes = listOf(
    AppR.string.onboarding_controls_preview_lyric_1,
    AppR.string.onboarding_controls_preview_lyric_2,
    AppR.string.onboarding_controls_preview_lyric_3,
    AppR.string.onboarding_controls_preview_lyric_4,
    AppR.string.onboarding_controls_preview_lyric_5,
    AppR.string.onboarding_controls_preview_lyric_6,
    AppR.string.onboarding_controls_preview_lyric_7,
    AppR.string.onboarding_controls_preview_lyric_8,
    AppR.string.onboarding_controls_preview_lyric_9,
    AppR.string.onboarding_controls_preview_lyric_10,
    AppR.string.onboarding_controls_preview_lyric_11,
    AppR.string.onboarding_controls_preview_lyric_12
)

@Composable
internal fun startupPreviewLyricTexts(): List<String> = StartupPreviewLyricRes.map { stringResource(it) }

internal const val STARTUP_PREVIEW_DURATION_MS = 222_000L
internal const val STARTUP_PREVIEW_START_MS = 78_000L
private const val STARTUP_PREVIEW_LINE_MS = 4_500L
private const val STARTUP_PREVIEW_LINE_LEAD_MS = 2_500L
private const val STARTUP_PREVIEW_COVER_FILE = "startup_preview_cover_v1.png"

internal const val StartupMiniPlayerTag = "startupMiniPlayer"
internal const val StartupNowPlayingTag = "startupNowPlaying"

/** 起播点落在第一句里, 打开预览时就能看到歌词在走 */
internal fun buildStartupPreviewLyrics(texts: List<String>): List<LyricEntry> {
    val firstStart = STARTUP_PREVIEW_START_MS - STARTUP_PREVIEW_LINE_LEAD_MS
    return texts.mapIndexed { index, text ->
        val start = firstStart + index * STARTUP_PREVIEW_LINE_MS
        LyricEntry(text = text, startTimeMs = start, endTimeMs = start + STARTUP_PREVIEW_LINE_MS)
    }
}

/** 模拟播放在歌词覆盖的区间内循环, 预览开多久都有歌词可看 */
internal fun advanceStartupPreviewPosition(
    positionMs: Long,
    deltaMs: Long,
    loopStartMs: Long,
    loopEndMs: Long
): Long {
    if (loopEndMs <= loopStartMs) return positionMs
    val next = positionMs + deltaMs.coerceAtLeast(0L)
    return when {
        next < loopStartMs -> loopStartMs
        next >= loopEndMs -> loopStartMs + (next - loopEndMs) % (loopEndMs - loopStartMs)
        else -> next
    }
}

@Stable
internal class StartupPreviewCover(val bitmap: ImageBitmap, val uri: String)

/** 示例封面在本地绘制并缓存成文件, 迷你播放器走和真实歌曲一样的图片加载链路 */
@Composable
internal fun rememberStartupPreviewCover(): StartupPreviewCover? {
    val context = LocalContext.current.applicationContext
    val cover by produceState<StartupPreviewCover?>(null, context) {
        value = withContext(Dispatchers.IO) { loadStartupPreviewCover(context) }
    }
    return cover
}

private fun loadStartupPreviewCover(context: Context): StartupPreviewCover? = runCatching {
    val bitmap = renderStartupPreviewCover(512)
    val file = File(context.cacheDir, STARTUP_PREVIEW_COVER_FILE)
    if (!file.exists()) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    StartupPreviewCover(bitmap.asImageBitmap(), Uri.fromFile(file).toString())
}.getOrNull()

/** 夜航: 夜空渐变, 一轮月亮和几颗星, 底部两道海浪 */
internal fun renderStartupPreviewCover(sizePx: Int): Bitmap {
    val size = sizePx.toFloat()
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(
        0f, 0f, size * 0.3f, size,
        intArrayOf(0xFF141A3D.toInt(), 0xFF3E3483.toInt(), 0xFFB65E7A.toInt(), 0xFFF2A07B.toInt()),
        floatArrayOf(0f, 0.45f, 0.78f, 1f),
        Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, size, size, paint)
    paint.shader = RadialGradient(
        size * 0.68f, size * 0.3f, size * 0.32f,
        intArrayOf(0x66FFE9C7, 0x00FFE9C7),
        null,
        Shader.TileMode.CLAMP
    )
    canvas.drawCircle(size * 0.68f, size * 0.3f, size * 0.32f, paint)
    paint.shader = null
    paint.color = 0xFFFFF1DA.toInt()
    canvas.drawCircle(size * 0.68f, size * 0.3f, size * 0.105f, paint)
    paint.color = 0xFF2F2B6E.toInt()
    canvas.drawCircle(size * 0.715f, size * 0.275f, size * 0.09f, paint)
    paint.color = 0xCCFFFFFF.toInt()
    listOf(0.18f to 0.16f, 0.32f to 0.3f, 0.12f to 0.42f, 0.44f to 0.12f, 0.86f to 0.52f, 0.54f to 0.46f)
        .forEachIndexed { index, (x, y) ->
            canvas.drawCircle(size * x, size * y, size * (if (index % 2 == 0) 0.008f else 0.005f), paint)
        }
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = size * 0.012f
    listOf(0.74f to 0x80FFFFFF.toInt(), 0.82f to 0x4DFFFFFF).forEach { (y, color) ->
        paint.color = color
        val path = android.graphics.Path()
        path.moveTo(-size * 0.05f, size * y)
        var x = -size * 0.05f
        while (x < size * 1.05f) {
            path.quadTo(x + size * 0.07f, size * (y - 0.035f), x + size * 0.14f, size * y)
            path.quadTo(x + size * 0.21f, size * (y + 0.035f), x + size * 0.28f, size * y)
            x += size * 0.28f
        }
        canvas.drawPath(path, paint)
    }
    return bitmap
}

private fun Modifier.startupBleedHorizontal(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + extra).coerceAtLeast(0),
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
        )
    )
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
        placeable.place(-extra / 2, 0)
    }
}

/**
 * 迷你播放器的玻璃要采样引导页内容, 本体不能放在内容采样层里面, 否则会把自己也模糊进去
 * 按钮区只放等高的占位, 本体画在采样层之外并对齐占位, 层级关系和真实应用一致
 */
@Stable
internal class StartupMiniPlayerAnchor {
    var slotInRoot by mutableStateOf(Rect.Zero)
    var originInRoot by mutableStateOf(Offset.Zero)
    var playerHeightPx by mutableIntStateOf(0)
}

private val StartupMiniPlayerFallbackHeight = 76.dp

@Composable
internal fun StartupMiniPlayerSlot(visible: Boolean, anchor: StartupMiniPlayerAnchor) {
    val density = LocalDensity.current
    val target = if (!visible) {
        0.dp
    } else if (anchor.playerHeightPx > 0) {
        with(density) { anchor.playerHeightPx.toDp() }
    } else {
        StartupMiniPlayerFallbackHeight
    }
    val height by animateDpAsState(target, spring(dampingRatio = 0.9f, stiffness = 380f), label = "startup_mini_slot")
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { anchor.slotInRoot = it.boundsInRoot() }
    )
}

/** 铺满采样层之外的一层, 把迷你播放器摆到占位的位置, 底边对齐占位底边 */
@Composable
internal fun StartupFloatingMiniPlayerLayer(
    visible: Boolean,
    expanded: Boolean,
    cover: StartupPreviewCover?,
    source: StartupPreviewPlaybackSource,
    anchor: StartupMiniPlayerAnchor,
    onExpand: () -> Unit
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { anchor.originInRoot = it.positionInRoot() }
    ) {
        val slot = anchor.slotInRoot
        if (slot.width <= 0f) return@Box
        StartupFloatingMiniPlayer(
            visible = visible,
            expanded = expanded,
            cover = cover,
            source = source,
            onExpand = onExpand,
            modifier = Modifier
                .width(with(density) { slot.width.toDp() })
                .onSizeChanged { anchor.playerHeightPx = it.height }
                .offset {
                    IntOffset(
                        (slot.left - anchor.originInRoot.x).roundToInt(),
                        (slot.bottom - anchor.originInRoot.y).roundToInt() - anchor.playerHeightPx
                    )
                }
        )
    }
}

@Composable
internal fun StartupFloatingMiniPlayer(
    visible: Boolean,
    expanded: Boolean,
    cover: StartupPreviewCover?,
    source: StartupPreviewPlaybackSource,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clean = NeriDesign.isClean
    val inset = if (clean) 16.dp else 8.dp
    val isPlaying by source.isPlayingFlow.collectAsState()
    // 展开和收起交给真实播放页承载层的容器变换, 这里和应用里的迷你播放器用同一套进出场
    AnimatedVisibility(
        visible = visible && !expanded,
        modifier = modifier,
        enter = slideInVertically(tween(durationMillis = 220, easing = FastOutSlowInEasing)) { it / 2 } +
            fadeIn(tween(durationMillis = 180)),
        exit = slideOutVertically(tween(durationMillis = 180, easing = FastOutSlowInEasing)) { it / 2 } +
            fadeOut(tween(durationMillis = 120))
    ) {
        val glassOpacity = rememberStartupGlassOpacity(enterMillis = 180, exitMillis = 120)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .startupBleedHorizontal(inset)
                .padding(bottom = 12.dp)
        ) {
            CompositionLocalProvider(LocalAdvancedGlassSceneOpacity provides glassOpacity) {
                NeriMiniPlayer(
                    title = stringResource(AppR.string.onboarding_controls_preview_song),
                    artist = stringResource(AppR.string.onboarding_controls_preview_artist),
                    coverUrl = cover?.uri,
                    coverIdentityKey = STARTUP_PREVIEW_COVER_FILE,
                    isPlaying = isPlaying,
                    modifier = Modifier.testTag(StartupMiniPlayerTag),
                    onPlayPause = source::togglePlayPause,
                    onPrevious = source::previous,
                    onNext = source::next,
                    onExpand = onExpand,
                    // Material 版本在应用里贴着底栏只圆上角, 悬浮在按钮上方时要四角都是圆角
                    containerShape = NeriDesign.tokens.miniPlayerShape
                )
            }
        }
    }
}

/** 示例歌曲的播放状态: 只在内存里推进进度, 不碰真实播放器, 队列, 历史和音频焦点 */
@Stable
internal class StartupPreviewPlaybackSource(
    song: SongItem,
    lyrics: List<LyricEntry>
) : NowPlayingPlaybackSource {
    private val loopStartMs = lyrics.firstOrNull()?.startTimeMs ?: STARTUP_PREVIEW_START_MS
    private val loopEndMs = lyrics.lastOrNull()?.endTimeMs ?: (STARTUP_PREVIEW_START_MS + 60_000L)
    private val song = MutableStateFlow(song)
    private val playing = MutableStateFlow(false)
    private val shuffle = MutableStateFlow(false)
    private val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    private val heartbeat = MutableStateFlow(HeartbeatMode.OFF)
    private val position = MutableStateFlow(STARTUP_PREVIEW_START_MS)
    private val queue = MutableStateFlow(listOf(song))

    override val currentSongFlow: StateFlow<SongItem?> = this.song
    override val isPlayingFlow: StateFlow<Boolean> = playing
    override val playbackControlPlayingFlow: StateFlow<Boolean> = playing
    override val audioRouteMuteSuppressedFlow: StateFlow<Boolean> = MutableStateFlow(false)
    override val usbExclusivePlaybackPreparingFlow: StateFlow<Boolean> = MutableStateFlow(false)
    override val shuffleModeFlow: StateFlow<Boolean> = shuffle
    override val repeatModeFlow: StateFlow<Int> = repeat
    override val heartbeatModeFlow: StateFlow<Int> = heartbeat
    override val playbackDurationFlow: StateFlow<Long> = MutableStateFlow(STARTUP_PREVIEW_DURATION_MS)
    override val playbackPositionFlow: StateFlow<Long> = position
    override val currentPlaybackAudioInfoFlow: StateFlow<PlaybackAudioInfo?> = MutableStateFlow(null)
    override val preferredQualityKeys: StateFlow<PreferredQualityKeys> = MutableStateFlow(PreferredQualityKeys())
    override val playbackSoundStateFlow: StateFlow<PlaybackSoundState> = MutableStateFlow(PlaybackSoundState())
    override val lyricsPreferenceRevisionFlow: StateFlow<Long> = MutableStateFlow(0L)
    override val currentQueueFlow: StateFlow<List<SongItem>> = queue
    override val currentQueueDisplayRevisionFlow: StateFlow<Long> = MutableStateFlow(0L)
    override val currentMediaUrlFlow: StateFlow<String?> = MutableStateFlow(null)
    override val playlistsFlow: StateFlow<List<LocalPlaylist>> = MutableStateFlow(emptyList())
    override val localPlaylistsReadyFlow: StateFlow<Boolean> = MutableStateFlow(false)
    override val sleepTimerStateFlow: StateFlow<SleepTimerState> = MutableStateFlow(SleepTimerState())
    override val preferWordTimedLyrics: Boolean = true
    override val defaultLyricSource: LyricSourcePreference = LyricSourcePreference.Automatic
    override val lyricsStages: NowPlayingLyricsStages = NowPlayingLyricsLoadStages(StartupPreviewLyricsSources(lyrics))
    override val secondaryActionsEnabled: Boolean = false

    val isPlaying: Boolean get() = playing.value

    fun updateSong(updated: SongItem) {
        song.value = updated
        queue.value = listOf(updated)
    }

    fun setPlaying(value: Boolean) {
        playing.value = value
    }

    fun advance(deltaMs: Long) {
        position.value = advanceStartupPreviewPosition(position.value, deltaMs, loopStartMs, loopEndMs)
    }

    override fun currentQueueDisplaySnapshot(): PlayerQueueDisplayState = PlayerQueueDisplayState.EMPTY
    override fun isBiliCcSubtitleLyricsActive(song: SongItem?): Boolean = false
    override fun togglePlayPause() {
        playing.value = !playing.value
    }

    // 示例只有一首歌, 上一首和下一首都回到第一句歌词
    override fun next() {
        position.value = loopStartMs
    }

    override fun previous() {
        position.value = loopStartMs
    }

    override fun setShuffle(enabled: Boolean) {
        shuffle.value = enabled
    }

    // 预览里只切换按钮外观, 不碰真实的心动推荐
    override fun setHeartbeatEnabled(enabled: Boolean) {
        heartbeat.value = if (enabled) HeartbeatMode.ON else HeartbeatMode.OFF
    }

    override fun cycleRepeatMode() {
        repeat.value = when (repeat.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun seekTo(positionMs: Long) {
        position.value = positionMs.coerceIn(0L, STARTUP_PREVIEW_DURATION_MS)
    }

    override fun toggleCurrentFavorite() = Unit
    override fun addCurrentToPlaylist(playlistId: Long) = Unit
    override fun changeCurrentPlaybackQuality(optionKey: String) = Unit
}

/** 示例歌词直接作为在线原文返回, 走和真实歌曲一样的加载流程 */
private class StartupPreviewLyricsSources(private val lyrics: List<LyricEntry>) : NowPlayingLyricsSources {
    override fun cachedOriginal(song: SongItem): List<LyricEntry> = lyrics
    override fun hasManagedDownload(song: SongItem): Boolean = false
    override fun scheduleManagedRefresh(context: Context) = Unit
    override fun fastDownloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle? = null
    override fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle? = null
    override fun inspectLocal(context: Context, song: SongItem, includeEmbedded: Boolean): LocalLyricsScanMetadata? = null
    override suspend fun preferred(song: SongItem, source: LyricSourcePreference): PreferredLyricSourceResult? = null
    override suspend fun neteaseOriginal(songId: Long): String = ""
    override suspend fun neteaseRomanized(songId: Long): String = ""
    override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> = lyrics
    override suspend fun onlineTranslated(song: SongItem): List<LyricEntry> = emptyList()
    override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> = emptyList()
}

internal const val STARTUP_PREVIEW_SONG_ID = -20_261_121L

@Composable
internal fun rememberStartupPreviewPlaybackSource(
    lyrics: List<LyricEntry>,
    cover: StartupPreviewCover?
): StartupPreviewPlaybackSource {
    val title = stringResource(AppR.string.onboarding_controls_preview_song)
    val artist = stringResource(AppR.string.onboarding_controls_preview_artist)
    val song = remember(title, artist, cover?.uri) {
        SongItem(
            id = STARTUP_PREVIEW_SONG_ID,
            name = title,
            artist = artist,
            album = "",
            albumId = 0L,
            durationMs = STARTUP_PREVIEW_DURATION_MS,
            coverUrl = cover?.uri
        )
    }
    val source = remember(lyrics) { StartupPreviewPlaybackSource(song, lyrics) }
    LaunchedEffect(source, song) { source.updateSong(song) }
    val playing by source.isPlayingFlow.collectAsState()
    LaunchedEffect(source, playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            withFrameMillis { now ->
                source.advance(now - last)
                last = now
            }
        }
    }
    return source
}

/** 播放页的取色与真实应用一致: 从封面采样主色, 再向蓝色略微偏移 */
@Composable
internal fun rememberStartupPreviewCoverSeed(cover: StartupPreviewCover?): String? {
    val context = LocalContext.current
    val seed by produceState<String?>(null, cover?.uri) {
        val uri = cover?.uri ?: return@produceState
        value = CoverArtColorCache.preload(context, uri, false)?.seedHex?.let(::biasCoverSeedTowardBlue)
    }
    return seed
}

/** 真实的播放页承载层和播放页本体, 只把播放状态换成示例歌曲 */
@Composable
internal fun StartupNowPlayingPreviewHost(
    visible: Boolean,
    showLyrics: Boolean,
    onShowLyricsChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    source: StartupPreviewPlaybackSource,
    cover: StartupPreviewCover?,
    theme: NowPlayingOverlayTheme,
    background: NowPlayingOverlayBackground,
    lyrics: StartupPreviewLyricPreferences,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit
) {
    val song by source.currentSongFlow.collectAsState()
    // 封面归属按歌曲自身的 visual key 校验, 与应用里的播放页完全一致
    val songKey = song?.playbackVisualKey()
    LaunchedEffect(visible) { if (visible) source.setPlaying(true) }
    AppNowPlayingOverlay(
        visible = visible,
        cover = NowPlayingOverlayCover(
            url = cover?.uri,
            songKey = songKey,
            song = song,
            assetRefreshKey = 0
        ),
        queueFlow = source.currentQueueFlow,
        theme = theme,
        background = background,
        onVisibilityChanged = {},
        onClose = onClose
    ) {
        CompositionLocalProvider(LocalNowPlayingPlaybackSource provides source) {
            Box(modifier = Modifier.fillMaxSize().testTag(StartupNowPlayingTag)) {
                NowPlayingScreen(
                    onNavigateUp = onClose,
                    showLyricsScreen = showLyrics,
                    onShowLyricsScreenChange = onShowLyricsChange,
                    onEnterAlbum = {},
                    lyricBlurEnabled = lyrics.blurEnabled,
                    lyricBlurAmount = lyrics.blurAmount,
                    lyricFontScales = lyrics.fontScales,
                    onLyricFontScaleChange = onLyricFontScaleChange,
                    advancedLyricsEnabled = lyrics.advancedEnabled,
                    showCoverSourceBadge = false,
                    showLyricTranslation = lyrics.showTranslation,
                    resolvedCoverUrl = cover?.uri,
                    visualCoverUrl = cover?.uri,
                    playbackSongKey = songKey,
                    visualCoverSongKey = songKey
                )
            }
        }
    }
}

@Immutable
internal data class StartupPreviewLyricPreferences(
    val blurEnabled: Boolean,
    val blurAmount: Float,
    val fontScales: LyricFontScales,
    val advancedEnabled: Boolean,
    val showTranslation: Boolean
)

/**
 * 玻璃模糊由宿主统一绘制, 不跟随 AnimatedVisibility 的淡入淡出
 * 把进出场透明度乘到玻璃层上, 模糊和内容一起出现和消失
 */
@Composable
internal fun AnimatedVisibilityScope.rememberStartupGlassOpacity(
    enterMillis: Int,
    exitMillis: Int,
    enterDelayMillis: Int = 0
): () -> Float {
    val alpha = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.Visible) {
                tween(durationMillis = enterMillis, delayMillis = enterDelayMillis)
            } else {
                tween(durationMillis = exitMillis)
            }
        },
        label = "startup_glass_opacity"
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
    val parent = LocalAdvancedGlassSceneOpacity.current
    return remember(parent, alpha) { { parent() * alpha.value } }
}
