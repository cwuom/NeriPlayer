package moe.ouom.neriplayer.ui.screen.nowplaying

import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.identity.stableKey

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.bilibili.skip.resolver.resolveBiliVideoSkipTargetOptions
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTargetOption
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.core.player.playback.BiliVideoSkipPlaybackController
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.platform.youtube.media.isYouTubeMusicSong
import moe.ouom.neriplayer.data.model.bilibili.skip.BiliVideoSkipTarget
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScalePage
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScaleTarget
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricFontScales
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.playback.PlaybackSoundSheet
import moe.ouom.neriplayer.ui.component.playback.SongMetadataSearchContent
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.NeriOverlaySnackbarHost
import moe.ouom.neriplayer.ui.screen.debug.ListenTogetherRoomPanel
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.BiliVideoSkipIntervalsContent
import moe.ouom.neriplayer.ui.screen.nowplaying.actions.MoreOptionsMainContent
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongInfoSheet
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.isCompactEditSongLandscape
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricBehaviorSheet
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricFontSizeSheet
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary

private val NowPlayingFeedbackExtraBottomPadding = 24.dp
private val EditSongInfoFeedbackControlClearance = 72.dp

internal class NowPlayingAudioDeviceOwner(
    private val audioManager: AudioManager,
    private val context: Context
) {
    var deviceInfo by mutableStateOf(getCurrentAudioDevice(audioManager, context))
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    private fun refresh() {
        deviceInfo = getCurrentAudioDevice(audioManager, context)
    }

    fun register() {
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }
}

@Composable
fun rememberAudioDeviceInfo(): Pair<String, ImageVector> {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val owner = remember(context, audioManager) { NowPlayingAudioDeviceOwner(audioManager, context) }
    DisposableEffect(owner) {
        owner.register()
        onDispose(owner::unregister)
    }
    return owner.deviceInfo
}

fun getCurrentAudioDevice(audioManager: AudioManager, context: Context): Pair<String, ImageVector> {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val bluetooth = findBluetoothAudioDevice(devices)
    return if (bluetooth != null) bluetoothAudioOutput(bluetooth, context)
    else wiredOrSpeakerAudioOutput(devices, context)
}

private fun findBluetoothAudioDevice(devices: Array<AudioDeviceInfo>): AudioDeviceInfo? =
    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }

private fun bluetoothAudioOutput(device: AudioDeviceInfo, context: Context): Pair<String, ImageVector> =
    Pair(readBluetoothDeviceName(device, context), Icons.Default.Headset)

private fun readBluetoothDeviceName(device: AudioDeviceInfo, context: Context): String = try {
    device.productName.toString().ifBlank { context.getString(CoreCommonR.string.nowplaying_bluetooth_device) }
} catch (_: SecurityException) {
    context.getString(CoreCommonR.string.nowplaying_bluetooth_device)
}

private fun wiredOrSpeakerAudioOutput(
    devices: Array<AudioDeviceInfo>,
    context: Context
): Pair<String, ImageVector> {
    val hasWiredHeadset = devices.any {
        it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    }
    return if (hasWiredHeadset) {
        Pair(context.getString(CoreCommonR.string.nowplaying_wired_headset), Icons.Default.Headset)
    } else {
        Pair(context.getString(CoreCommonR.string.nowplaying_phone_speaker), Icons.Default.SpeakerGroup)
    }
}

internal fun isNeteaseArtistNavigationSource(song: SongItem): Boolean {
    if (shouldRejectNeteaseArtistSource(song)) return false
    if (hasExplicitNeteaseArtistSource(song)) return true
    return !song.isLocalSong() && hasNeteaseArtistHints(song)
}

private fun shouldRejectNeteaseArtistSource(song: SongItem): Boolean {
    return hasForeignArtistChannel(song.channelId) ||
        song.album.startsWith(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true) ||
        isYouTubeMusicSong(song)
}

private fun hasForeignArtistChannel(channelId: String?): Boolean =
    channelId?.trim()?.takeIf(String::isNotBlank)?.equals("netease", ignoreCase = true) == false

private fun hasExplicitNeteaseArtistSource(song: SongItem): Boolean =
    song.channelId.equals("netease", ignoreCase = true) ||
        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG, ignoreCase = true) ||
        song.mediaUri?.contains("music.163.com", ignoreCase = true) == true ||
        isManagedNeteaseSong(song)

private fun isManagedNeteaseSong(song: SongItem): Boolean =
    song.id > 0L && listOfNotNull(song.localFileName, song.localFilePath, song.mediaUri)
        .any(::containsManagedNeteaseFilename)

private fun containsManagedNeteaseFilename(reference: String): Boolean =
    reference.contains("netease -", ignoreCase = true) ||
        reference.contains("netease%20-", ignoreCase = true)

private fun hasNeteaseArtistHints(song: SongItem): Boolean =
    hasNamedNeteaseArtist(song) || hasNeteaseCoverHost(song)

private fun hasNamedNeteaseArtist(song: SongItem): Boolean =
    song.neteaseArtists.orEmpty().any(::isNamedNeteaseArtist)

private fun isNamedNeteaseArtist(artist: NeteaseArtistSummary): Boolean =
    artist.id > 0L && artist.name.isNotBlank()

private fun hasNeteaseCoverHost(song: SongItem): Boolean =
    listOfNotNull(song.coverUrl, song.originalCoverUrl, song.customCoverUrl)
        .any { it.contains("music.126.net", ignoreCase = true) }

internal fun isBiliUploaderNavigationSource(song: SongItem): Boolean {
    return song.id > 0L && song.album.startsWith(
        PlayerManager.BILI_SOURCE_TAG,
        ignoreCase = true
    )
}

internal fun isYouTubeMusicArtistNavigationSource(song: SongItem): Boolean {
    return song.artist.isNotBlank() && (
        song.channelId.equals("youtubeMusic", ignoreCase = true) || isYouTubeMusicSong(song)
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NeteaseArtistPickerSheet(
    artists: List<NeteaseArtistSummary>,
    onDismiss: () -> Unit,
    onSelect: (NeteaseArtistSummary) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .bottomSheetScrollGuard()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(CoreCommonR.string.artist_choose_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            artists.forEach { artist ->
                NeteaseArtistPickerRow(artist, onSelect)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YouTubeMusicCreatorPickerSheet(
    creators: List<YouTubeMusicCreatorSummary>,
    onDismiss: () -> Unit,
    onSelect: (YouTubeMusicCreatorSummary) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .bottomSheetScrollGuard()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(CoreCommonR.string.youtube_creator_choose_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            creators.forEach { creator ->
                YouTubeCreatorPickerRow(creator, onSelect)
            }
        }
    }
}

@Composable
private fun NeteaseArtistPickerRow(
    artist: NeteaseArtistSummary,
    onSelect: (NeteaseArtistSummary) -> Unit
) {
    ArtistPickerListItem(artist.name, null, Modifier.artistSelectionModifier(artist, onSelect))
}

@Composable
private fun YouTubeCreatorPickerRow(
    creator: YouTubeMusicCreatorSummary,
    onSelect: (YouTubeMusicCreatorSummary) -> Unit
) {
    ArtistPickerListItem(
        creator.title,
        creatorSupportingContent(creator.subtitle),
        Modifier.artistSelectionModifier(creator, onSelect)
    )
}

private fun <T> Modifier.artistSelectionModifier(value: T, onSelect: (T) -> Unit): Modifier =
    clickable { onSelect(value) }

private fun creatorSupportingContent(subtitle: String): (@Composable () -> Unit)? =
    if (subtitle.isBlank()) null else { { Text(subtitle) } }

@Composable
private fun ArtistPickerListItem(
    title: String,
    supportingContent: (@Composable () -> Unit)?,
    modifier: Modifier
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier
    )
}

internal enum class MoreOptionsPage {
    MAIN,
    SEARCH,
    LYRIC_BEHAVIOR,
    FONT_SIZE,
    EDIT_INFO,
    BILI_VIDEO_SKIP,
    LISTEN_TOGETHER,
    PLAYBACK_SOUND
}

internal fun resolveMoreOptionsInitialPage(startWithLyricBehavior: Boolean): MoreOptionsPage =
    if (startWithLyricBehavior) MoreOptionsPage.LYRIC_BEHAVIOR else MoreOptionsPage.MAIN

internal class MoreOptionsBiliTargetOwner(
    private val resolveTarget: (SongItem) -> BiliVideoSkipTarget?
) {
    private var song: SongItem? = null
    private var generation: Long? = null
    private var target: BiliVideoSkipTarget? = null

    fun resolve(song: SongItem, generation: Long): BiliVideoSkipTarget? {
        if (this.song != song || this.generation != generation) {
            this.song = song
            this.generation = generation
            target = resolveTarget(song)
        }
        return target
    }
}

internal class MoreOptionsSheetOwner(
    private val scope: CoroutineScope,
    private val hide: suspend () -> Unit,
    private val expand: suspend () -> Unit,
    private val initialPage: MoreOptionsPage = MoreOptionsPage.MAIN,
    private val onDismiss: () -> Unit
) {
    val biliTargetOwner = MoreOptionsBiliTargetOwner { song ->
        BiliVideoSkipPlaybackController.activeTargetFor(song)
    }
    var page by mutableStateOf(initialPage)
        private set
    var isDismissing by mutableStateOf(false)
        private set
    var isEditSongSaving by mutableStateOf(false)
        private set
    var sheetImeVisible by mutableStateOf(false)
        private set

    val sheetGesturesEnabled: Boolean
        get() = page != MoreOptionsPage.LISTEN_TOGETHER && !isEditSongSaving

    val onBack: () -> Unit = ::back
    val onDismissRequest: () -> Unit = ::dismiss
    val onSavingChanged: (Boolean) -> Unit = ::setEditSaving

    fun open(target: MoreOptionsPage) {
        if (!isEditSongSaving) page = target
    }

    fun back() {
        if (isEditSongSaving) return
        if (page == initialPage && initialPage != MoreOptionsPage.MAIN) dismiss()
        else page = MoreOptionsPage.MAIN
    }

    fun setEditSaving(saving: Boolean) {
        isEditSongSaving = saving
    }

    fun updateSheetImeVisibility(visible: Boolean) {
        sheetImeVisible = visible
    }

    fun windowPresentation(compactLandscape: Boolean): MoreOptionsSheetPresentation =
        resolveMoreOptionsSheetPresentation(page, compactLandscape, sheetImeVisible, sheetGesturesEnabled)

    fun expansionEffect(presentation: MoreOptionsSheetPresentation): suspend CoroutineScope.() -> Unit = {
        if (presentation.expandToFit) expand()
    }

    fun dismiss() = dismiss({})

    fun dismiss(afterHidden: () -> Unit) {
        if (isDismissing || isEditSongSaving) return
        isDismissing = true
        scope.launch {
            try {
                hide()
                afterHidden()
            } finally {
                try {
                    onDismiss()
                } finally {
                    isDismissing = false
                }
            }
        }
    }
}

private class MoreOptionsSearchActions(
    private val viewModel: NowPlayingViewModel,
    private val song: SongItem,
    private val owner: MoreOptionsSheetOwner
) {
    val onSongSelected: (SongSearchInfo) -> Unit = { result ->
        owner.dismiss { viewModel.onSongSelected(song, result) }
    }
    val onDone: () -> Unit = { owner.open(MoreOptionsPage.MAIN) }
}

private class MoreOptionsBiliSkipActions(
    private val song: SongItem,
    private val owner: MoreOptionsSheetOwner,
    private val biliClient: BiliClient
) {
    val loadTargetOptions: suspend () -> List<BiliVideoSkipTargetOption> = {
        resolveBiliVideoSkipTargetOptions(song, biliClient)
    }
    val onTogglePlayback: () -> Unit = PlayerManager::togglePlayPauseWithoutFade
    val onSeekToPlaybackPosition: (Long) -> Unit = { PlayerManager.seekTo(it) }
    val onDismiss: () -> Unit = { owner.open(MoreOptionsPage.MAIN) }
}

private class MoreOptionsPlaybackSoundActions(
    private val viewModel: NowPlayingViewModel,
    private val owner: MoreOptionsSheetOwner
) {
    val onSpeedChange: (Float, Boolean) -> Unit = { value, persist ->
        viewModel.setPlaybackSpeed(value, persist)
    }
    val onPitchChange: (Float, Boolean) -> Unit = { value, persist ->
        viewModel.setPlaybackPitch(value, persist)
    }
    val onLoudnessGainChange: (Int, Boolean) -> Unit = { value, persist ->
        viewModel.setPlaybackLoudnessGain(value, persist)
    }
    val onEqualizerEnabledChange: (Boolean) -> Unit = viewModel::setPlaybackEqualizerEnabled
    val onPresetSelected: (String) -> Unit = viewModel::selectPlaybackEqualizerPreset
    val onBandLevelChange: (Int, Int, Boolean) -> Unit = { index, value, persist ->
        viewModel.updatePlaybackEqualizerBandLevel(index, value, persist)
    }
    val onReset: () -> Unit = viewModel::resetPlaybackSoundSettings
    val onDismiss: () -> Unit = { owner.open(MoreOptionsPage.MAIN) }
}

private class MoreOptionsFontScaleActions(
    lyricTarget: LyricFontScaleTarget,
    translationTarget: LyricFontScaleTarget,
    onChange: (LyricFontScaleTarget, Float) -> Unit,
    owner: MoreOptionsSheetOwner
) {
    val onLyricScaleCommit: (Float) -> Unit = { scale -> onChange(lyricTarget, scale) }
    val onTranslationScaleCommit: (Float) -> Unit = { scale -> onChange(translationTarget, scale) }
    val onDismiss: () -> Unit = { owner.open(MoreOptionsPage.MAIN) }
}

private class MoreOptionsMainActions(
    private val owner: MoreOptionsSheetOwner,
    private val originalSong: SongItem,
    private val onShowSongDetails: (SongItem) -> Unit,
    private val onShowQualitySwitch: () -> Unit,
    private val onEnterAlbum: (AlbumSummary) -> Unit,
    private val onNavigateUp: () -> Unit
) {
    val onOpenSearch: () -> Unit = { owner.open(MoreOptionsPage.SEARCH) }
    val onOpenEditInfo: () -> Unit = { owner.open(MoreOptionsPage.EDIT_INFO) }
    val onOpenPlaybackSound: () -> Unit = { owner.open(MoreOptionsPage.PLAYBACK_SOUND) }
    val onOpenLyricBehavior: () -> Unit = { owner.open(MoreOptionsPage.LYRIC_BEHAVIOR) }
    val onOpenFontSize: () -> Unit = { owner.open(MoreOptionsPage.FONT_SIZE) }
    val onOpenBiliVideoSkip: () -> Unit = { owner.open(MoreOptionsPage.BILI_VIDEO_SKIP) }
    val onOpenListenTogether: () -> Unit = { owner.open(MoreOptionsPage.LISTEN_TOGETHER) }
    val onShowDetails: () -> Unit = { owner.dismiss { onShowSongDetails(originalSong) } }
    val onShowQuality: () -> Unit = { owner.dismiss(onShowQualitySwitch) }
    val onAlbum: (AlbumSummary) -> Unit = { album ->
        owner.dismiss {
            onEnterAlbum(album)
            onNavigateUp()
        }
    }
    val onDismissSheet: (() -> Unit) -> Unit = owner::dismiss
}

internal fun resolveMoreOptionsSong(currentSong: SongItem?, originalSong: SongItem): SongItem =
    currentSong?.takeIf { it.sameIdentityAs(originalSong) } ?: originalSong

@OptIn(ExperimentalMaterial3Api::class)
private class MoreOptionsSheetSession(
    val sheetState: SheetState,
    val owner: MoreOptionsSheetOwner
)

class MoreOptionsLyricContent(
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry>,
    val romanizedLyrics: List<LyricEntry> = emptyList(),
    val hasTranslation: Boolean = true,
    val hasPhonetic: Boolean = false
)

class MoreOptionsFontSettings(
    val page: LyricFontScalePage,
    val scales: LyricFontScales,
    val onChange: (LyricFontScaleTarget, Float) -> Unit
)

class MoreOptionsSheetNavigation(
    val onDismiss: () -> Unit,
    val onShowSongDetails: (SongItem) -> Unit,
    val onEnterAlbum: (AlbumSummary) -> Unit,
    val onNavigateUp: () -> Unit,
    val onShowQualitySwitch: () -> Unit = {},
    val startWithLyricBehavior: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberMoreOptionsSheetSession(
    navigation: MoreOptionsSheetNavigation
): MoreOptionsSheetSession {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val latestOnDismiss = rememberUpdatedState(navigation.onDismiss)
    return remember(sheetState, coroutineScope) {
        MoreOptionsSheetSession(
            sheetState,
            MoreOptionsSheetOwner(
                coroutineScope, sheetState::hide, sheetState::expand,
                initialPage = resolveMoreOptionsInitialPage(navigation.startWithLyricBehavior)
            ) { latestOnDismiss.value() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@NonRestartableComposable
fun MoreOptionsSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    lyricContent: MoreOptionsLyricContent,
    navigation: MoreOptionsSheetNavigation,
    snackbarHostState: SnackbarHostState,
    fontSettings: MoreOptionsFontSettings,
    biliClient: BiliClient,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    offlineMode: Boolean
) {
    MoreOptionsSheetContent(
        viewModel, originalSong, queue, lyricContent, navigation,
        snackbarHostState, fontSettings, biliClient, currentPlaybackAudioInfo, offlineMode
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreOptionsSheetContent(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    lyricContent: MoreOptionsLyricContent,
    navigation: MoreOptionsSheetNavigation,
    snackbarHostState: SnackbarHostState,
    fontSettings: MoreOptionsFontSettings,
    biliClient: BiliClient,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    offlineMode: Boolean
) {
    val session = rememberMoreOptionsSheetSession(navigation)
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val actualSong = resolveMoreOptionsSong(currentSong, originalSong)
    val isLocalSong = actualSong.isLocalSong()
    val lyricFontScaleTarget = fontSettings.scales.lyricTargetFor(fontSettings.page)
    val translationFontScaleTarget = fontSettings.scales.translationTargetFor(fontSettings.page)
    val currentLyricFontScale = fontSettings.scales.scaleFor(lyricFontScaleTarget)
    val currentTranslationFontScale = fontSettings.scales.scaleFor(translationFontScaleTarget)

    val mainActions = MoreOptionsMainActions(
        session.owner, originalSong, navigation.onShowSongDetails,
        navigation.onShowQualitySwitch, navigation.onEnterAlbum, navigation.onNavigateUp
    )

    MoreOptionsSheetSurface(session.owner, session.sheetState, snackbarHostState) {
        MoreOptionsAnimatedPage(session.owner) { targetState ->
            MoreOptionsPages(
                targetState, session.owner, viewModel, originalSong, actualSong, queue,
                isLocalSong, lyricContent.lyrics, lyricContent.translatedLyrics,
                lyricContent.romanizedLyrics, lyricContent.hasTranslation,
                lyricContent.hasPhonetic, currentLyricFontScale,
                currentTranslationFontScale, lyricFontScaleTarget, translationFontScaleTarget,
                fontSettings.onChange, biliClient, currentPlaybackAudioInfo, snackbarHostState,
                offlineMode, mainActions
            )
        }
    }
}

@Composable
private fun MoreOptionsPages(
    targetState: MoreOptionsPage,
    owner: MoreOptionsSheetOwner,
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    actualSong: SongItem,
    queue: List<SongItem>,
    isLocalSong: Boolean,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    displayedRomanizedLyrics: List<LyricEntry>,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    currentLyricFontScale: Float,
    currentTranslationFontScale: Float,
    lyricFontScaleTarget: LyricFontScaleTarget,
    translationFontScaleTarget: LyricFontScaleTarget,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    biliClient: BiliClient,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean,
    mainActions: MoreOptionsMainActions
) {
    MoreOptionsMainPage(
        targetState, viewModel, originalSong, queue, isLocalSong,
        currentLyricFontScale, currentTranslationFontScale,
        currentPlaybackAudioInfo, snackbarHostState, owner, mainActions
    )
    MoreOptionsListenTogetherPage(targetState)
    MoreOptionsBiliVideoSkipPage(targetState, actualSong, owner, biliClient)
    MoreOptionsSearchPage(targetState, viewModel, actualSong, offlineMode, owner)
    MoreOptionsLyricBehaviorPage(
        targetState, originalSong, hasTranslationLyrics, hasPhoneticLyrics, owner
    )
    MoreOptionsFontSizePage(
        targetState, currentLyricFontScale, currentTranslationFontScale,
        lyricFontScaleTarget, translationFontScaleTarget, onLyricFontScaleChange, owner
    )
    MoreOptionsEditInfoPage(
        targetState, viewModel, actualSong, displayedLyrics, displayedTranslatedLyrics,
        displayedRomanizedLyrics, snackbarHostState, offlineMode, owner
    )
    MoreOptionsPlaybackSoundPage(targetState, viewModel, owner)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoreOptionsSheetSurface(
    owner: MoreOptionsSheetOwner,
    sheetState: SheetState,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit
) {
    val presentation = owner.windowPresentation(isCompactEditSongLandscape())
    LaunchedEffect(presentation.expandToFit, sheetState, block = owner.expansionEffect(presentation))
    ModalBottomSheet(
        onDismissRequest = owner.onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = presentation.gesturesEnabled,
        dragHandle = if (presentation.showDragHandle) {
            { BottomSheetDefaults.DragHandle() }
        } else null,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // Sheet 使用独立窗口，键盘可见性应从它自己的 composition 读取
        val imeVisible = WindowInsets.isImeVisible
        SideEffect { owner.updateSheetImeVisibility(imeVisible) }
        MoreOptionsBackHandlers(owner)
        MoreOptionsSheetBody(owner.page, snackbarHostState, content)
    }
}

internal data class MoreOptionsSheetPresentation(
    val expandToFit: Boolean,
    val showDragHandle: Boolean,
    val gesturesEnabled: Boolean
)

internal fun resolveMoreOptionsSheetPresentation(
    page: MoreOptionsPage,
    compactLandscape: Boolean,
    imeVisible: Boolean,
    ownerGesturesEnabled: Boolean
): MoreOptionsSheetPresentation {
    val compactEditSheet = isCompactMoreOptionsEditSheet(page, compactLandscape)
    return MoreOptionsSheetPresentation(
        expandToFit = compactEditSheet,
        showDragHandle = !shouldHideCompactEditSongHandle(compactEditSheet, imeVisible),
        gesturesEnabled = ownerGesturesEnabled && !compactEditSheet
    )
}

internal fun isCompactMoreOptionsEditSheet(page: MoreOptionsPage, compactLandscape: Boolean): Boolean =
    compactLandscape && page == MoreOptionsPage.EDIT_INFO

internal fun shouldHideCompactEditSongHandle(compactEditSheet: Boolean, imeVisible: Boolean): Boolean =
    compactEditSheet && imeVisible

@Composable
private fun MoreOptionsBackHandlers(owner: MoreOptionsSheetOwner) {
    MoreOptionsSubpageBackHandler(owner)
    MoreOptionsMainBackHandler(owner)
}

@Composable
private fun MoreOptionsSubpageBackHandler(owner: MoreOptionsSheetOwner) {
    BackHandler(enabled = owner.page != MoreOptionsPage.MAIN, onBack = owner.onBack)
}

@Composable
private fun MoreOptionsMainBackHandler(owner: MoreOptionsSheetOwner) {
    BackHandler(enabled = owner.page == MoreOptionsPage.MAIN, onBack = owner.onDismissRequest)
}

@Composable
private fun MoreOptionsSheetBody(
    page: MoreOptionsPage,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        content()
        MoreOptionsFeedbackHost(page, snackbarHostState)
    }
}

@Composable
private fun BoxScope.MoreOptionsFeedbackHost(page: MoreOptionsPage, snackbarHostState: SnackbarHostState) {
    NeriOverlaySnackbarHost(
        hostState = snackbarHostState,
        bottomPadding = LocalMiniPlayerHeight.current + NowPlayingFeedbackExtraBottomPadding +
            if (page == MoreOptionsPage.EDIT_INFO) EditSongInfoFeedbackControlClearance else 0.dp
    )
}

@Composable
private fun MoreOptionsAnimatedPage(
    owner: MoreOptionsSheetOwner,
    content: @Composable (MoreOptionsPage) -> Unit
) {
    AnimatedContent(
        targetState = owner.page,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                scaleIn(initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)))
                .togetherWith(fadeOut(animationSpec = tween(90)))
        },
        label = "more_options_sheet_content",
        content = { targetState -> content(targetState) }
    )
}

@Composable
private fun MoreOptionsListenTogetherPage(
    targetState: MoreOptionsPage
) {
    if (targetState != MoreOptionsPage.LISTEN_TOGETHER) return
    val listenTogetherScrollState = rememberScrollState()
    Column(
        Modifier
            .fillMaxWidth()
            .bottomSheetScrollGuard()
            .verticalScroll(listenTogetherScrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        ListenTogetherRoomPanel(
            modifier = Modifier.fillMaxWidth(),
            showBaseUrlEditor = false
        )
    }
}

@Composable
private fun MoreOptionsBiliVideoSkipPage(
    targetState: MoreOptionsPage,
    actualSong: SongItem,
    owner: MoreOptionsSheetOwner,
    biliClient: BiliClient
) {
    if (targetState != MoreOptionsPage.BILI_VIDEO_SKIP) return
    MoreOptionsBiliVideoSkipState(actualSong, owner, biliClient)
}

@Composable
private fun MoreOptionsBiliVideoSkipState(
    actualSong: SongItem,
    owner: MoreOptionsSheetOwner,
    biliClient: BiliClient
) {
    val currentPosition by PlayerManager.playbackPositionFlow
        .collectAsStateWithLifecycle()
    MoreOptionsBiliVideoSkipPlayingState(actualSong, owner, biliClient, currentPosition)
}

@Composable
private fun MoreOptionsBiliVideoSkipPlayingState(
    actualSong: SongItem,
    owner: MoreOptionsSheetOwner,
    biliClient: BiliClient,
    currentPosition: Long
) {
    val isPlaying by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    MoreOptionsBiliVideoSkipTargetState(actualSong, owner, biliClient, currentPosition, isPlaying)
}

@Composable
private fun MoreOptionsBiliVideoSkipTargetState(
    actualSong: SongItem,
    owner: MoreOptionsSheetOwner,
    biliClient: BiliClient,
    currentPosition: Long,
    isPlaying: Boolean
) {
    val activeBiliTargetGeneration by BiliVideoSkipPlaybackController
        .activeTrackGeneration
        .collectAsStateWithLifecycle()
    MoreOptionsBiliVideoSkipResolvedTarget(
        actualSong, owner, biliClient, currentPosition, isPlaying, activeBiliTargetGeneration
    )
}

@Composable
private fun MoreOptionsBiliVideoSkipResolvedTarget(
    actualSong: SongItem,
    owner: MoreOptionsSheetOwner,
    biliClient: BiliClient,
    currentPosition: Long,
    isPlaying: Boolean,
    activeBiliTargetGeneration: Long
) {
    val currentBiliTarget = owner.biliTargetOwner.resolve(actualSong, activeBiliTargetGeneration)
    MoreOptionsBiliVideoSkipTargetContent(
        actualSong, owner, biliClient, currentPosition, isPlaying, currentBiliTarget
    )
}

@Composable
private fun MoreOptionsBiliVideoSkipTargetContent(
    actualSong: SongItem,
    owner: MoreOptionsSheetOwner,
    biliClient: BiliClient,
    currentPosition: Long,
    isPlaying: Boolean,
    currentBiliTarget: BiliVideoSkipTarget?
) {
    val actions = MoreOptionsBiliSkipActions(actualSong, owner, biliClient)
    MoreOptionsBiliVideoSkipContent(actualSong, currentPosition, isPlaying, currentBiliTarget, actions)
}

@Composable
private fun MoreOptionsBiliVideoSkipContent(
    actualSong: SongItem,
    currentPosition: Long,
    isPlaying: Boolean,
    currentBiliTarget: BiliVideoSkipTarget?,
    actions: MoreOptionsBiliSkipActions
) {
    BiliVideoSkipIntervalsContent(
        title = stringResource(CoreCommonR.string.bili_video_skip_title),
        targetResolverKey = actualSong.stableKey(),
        loadTargetOptions = actions.loadTargetOptions,
        initialTarget = currentBiliTarget,
        currentPlaybackPositionMs = currentPosition,
        currentPlaybackTarget = currentBiliTarget,
        currentPlaybackIsPlaying = isPlaying,
        onTogglePlayback = actions.onTogglePlayback,
        onSeekToPlaybackPosition = actions.onSeekToPlaybackPosition,
        onDismiss = actions.onDismiss
    )
}

@Composable
private fun MoreOptionsSearchPage(
    targetState: MoreOptionsPage,
    viewModel: NowPlayingViewModel,
    actualSong: SongItem,
    offlineMode: Boolean,
    owner: MoreOptionsSheetOwner
) {
    if (targetState != MoreOptionsPage.SEARCH) return
    MoreOptionsSearchContent(viewModel, actualSong, offlineMode, owner)
}

@Composable
private fun MoreOptionsSearchContent(
    viewModel: NowPlayingViewModel,
    actualSong: SongItem,
    offlineMode: Boolean,
    owner: MoreOptionsSheetOwner
) {
    val actions = MoreOptionsSearchActions(viewModel, actualSong, owner)
    SongMetadataSearchContent(
        viewModel = viewModel,
        song = actualSong,
        offlineMode = offlineMode,
        enabled = !owner.isDismissing,
        onSongSelected = actions.onSongSelected,
        onDone = actions.onDone
    )
}

@Composable
private fun MoreOptionsLyricBehaviorPage(
    targetState: MoreOptionsPage,
    originalSong: SongItem,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    owner: MoreOptionsSheetOwner
) {
    if (targetState != MoreOptionsPage.LYRIC_BEHAVIOR) return
    MoreOptionsLyricBehaviorContent(originalSong, hasTranslationLyrics, hasPhoneticLyrics, owner)
}

@Composable
private fun MoreOptionsLyricBehaviorContent(
    originalSong: SongItem,
    hasTranslationLyrics: Boolean,
    hasPhoneticLyrics: Boolean,
    owner: MoreOptionsSheetOwner
) {
    LyricBehaviorSheet(
        song = originalSong,
        hasTranslationLyrics = hasTranslationLyrics,
        hasPhoneticLyrics = hasPhoneticLyrics,
        onDismiss = owner.onBack
    )
}

@Composable
private fun MoreOptionsFontSizePage(
    targetState: MoreOptionsPage,
    currentLyricFontScale: Float,
    currentTranslationFontScale: Float,
    lyricFontScaleTarget: LyricFontScaleTarget,
    translationFontScaleTarget: LyricFontScaleTarget,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    owner: MoreOptionsSheetOwner
) {
    if (targetState != MoreOptionsPage.FONT_SIZE) return
    MoreOptionsFontSizeContent(
        currentLyricFontScale, currentTranslationFontScale, lyricFontScaleTarget,
        translationFontScaleTarget, onLyricFontScaleChange, owner
    )
}

@Composable
private fun MoreOptionsFontSizeContent(
    currentLyricFontScale: Float,
    currentTranslationFontScale: Float,
    lyricFontScaleTarget: LyricFontScaleTarget,
    translationFontScaleTarget: LyricFontScaleTarget,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    owner: MoreOptionsSheetOwner
) {
    val actions = MoreOptionsFontScaleActions(
        lyricFontScaleTarget, translationFontScaleTarget, onLyricFontScaleChange, owner
    )
    LyricFontSizeSheet(
        currentLyricScale = currentLyricFontScale,
        currentTranslationScale = currentTranslationFontScale,
        onLyricScaleCommit = actions.onLyricScaleCommit,
        onTranslationScaleCommit = actions.onTranslationScaleCommit,
        onDismiss = actions.onDismiss
    )
}

@Composable
private fun MoreOptionsEditInfoPage(
    targetState: MoreOptionsPage,
    viewModel: NowPlayingViewModel,
    actualSong: SongItem,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    displayedRomanizedLyrics: List<LyricEntry>,
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean,
    owner: MoreOptionsSheetOwner
) {
    if (targetState != MoreOptionsPage.EDIT_INFO) return
    MoreOptionsEditInfoContent(
        viewModel, actualSong, displayedLyrics, displayedTranslatedLyrics,
        displayedRomanizedLyrics, snackbarHostState, offlineMode, owner
    )
}

@Composable
private fun MoreOptionsEditInfoContent(
    viewModel: NowPlayingViewModel,
    actualSong: SongItem,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    displayedRomanizedLyrics: List<LyricEntry>,
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean,
    owner: MoreOptionsSheetOwner
) {
    EditSongInfoSheet(
        viewModel = viewModel,
        originalSong = actualSong,
        displayedLyrics = displayedLyrics,
        displayedTranslatedLyrics = displayedTranslatedLyrics,
        displayedRomanizedLyrics = displayedRomanizedLyrics,
        onDismiss = owner.onBack,
        onSavingChanged = owner.onSavingChanged,
        snackbarHostState = snackbarHostState,
        offlineMode = offlineMode
    )
}

@Composable
private fun MoreOptionsPlaybackSoundPage(
    targetState: MoreOptionsPage,
    viewModel: NowPlayingViewModel,
    owner: MoreOptionsSheetOwner
) {
    if (targetState != MoreOptionsPage.PLAYBACK_SOUND) return
    MoreOptionsPlaybackSoundState(viewModel, owner)
}

@Composable
private fun MoreOptionsPlaybackSoundState(
    viewModel: NowPlayingViewModel,
    owner: MoreOptionsSheetOwner
) {
    val playbackSoundState by PlayerManager.playbackSoundStateFlow.collectAsStateWithLifecycle()
    val actions = MoreOptionsPlaybackSoundActions(viewModel, owner)
    MoreOptionsPlaybackSoundContent(playbackSoundState, actions)
}

@Composable
private fun MoreOptionsPlaybackSoundContent(
    state: PlaybackSoundState,
    actions: MoreOptionsPlaybackSoundActions
) {
    PlaybackSoundSheet(
        state = state,
        onSpeedChange = actions.onSpeedChange,
        onPitchChange = actions.onPitchChange,
        onLoudnessGainChange = actions.onLoudnessGainChange,
        onEqualizerEnabledChange = actions.onEqualizerEnabledChange,
        onPresetSelected = actions.onPresetSelected,
        onBandLevelChange = actions.onBandLevelChange,
        onReset = actions.onReset,
        onDismiss = actions.onDismiss
    )
}

@Composable
private fun MoreOptionsMainPage(
    targetState: MoreOptionsPage,
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    isLocalSong: Boolean,
    lyricFontScale: Float,
    translationFontScale: Float,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    snackbarHostState: SnackbarHostState,
    owner: MoreOptionsSheetOwner,
    actions: MoreOptionsMainActions
) {
    if (targetState != MoreOptionsPage.MAIN) return
    MoreOptionsMainPageContent(
        viewModel, originalSong, queue, isLocalSong, lyricFontScale,
        translationFontScale, currentPlaybackAudioInfo, snackbarHostState, owner, actions
    )
}

@Composable
private fun MoreOptionsMainPageContent(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    queue: List<SongItem>,
    isLocalSong: Boolean,
    lyricFontScale: Float,
    translationFontScale: Float,
    currentPlaybackAudioInfo: PlaybackAudioInfo?,
    snackbarHostState: SnackbarHostState,
    owner: MoreOptionsSheetOwner,
    actions: MoreOptionsMainActions
) {
    MoreOptionsMainContent(
        viewModel = viewModel,
        originalSong = originalSong,
        queue = queue,
        isLocalSong = isLocalSong,
        lyricFontScale = lyricFontScale,
        translationFontScale = translationFontScale,
        currentPlaybackAudioInfo = currentPlaybackAudioInfo,
        isDismissing = owner.isDismissing,
        snackbarHostState = snackbarHostState,
        onOpenSearch = actions.onOpenSearch,
        onOpenEditInfo = actions.onOpenEditInfo,
        onOpenPlaybackSound = actions.onOpenPlaybackSound,
        onOpenLyricBehavior = actions.onOpenLyricBehavior,
        onOpenFontSize = actions.onOpenFontSize,
        onOpenBiliVideoSkip = actions.onOpenBiliVideoSkip,
        onOpenListenTogether = actions.onOpenListenTogether,
        onShowSongDetails = actions.onShowDetails,
        onShowQualitySwitch = actions.onShowQuality,
        onEnterAlbum = actions.onAlbum,
        onDismissSheet = actions.onDismissSheet
    )
}
