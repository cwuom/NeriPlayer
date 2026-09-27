package moe.ouom.neriplayer.ui.screen.tab

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab/SettingsScreen
 * Created: 2025/8/8
 */

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.model.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadDirectoryChangeDecision
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationWorker
import moe.ouom.neriplayer.core.download.storage.migration.recovery.ManagedDownloadMigrationCheckpointStore
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.progress.mergeMigrationProgressFloor
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressCheckpointIds
import moe.ouom.neriplayer.core.download.storage.migration.progress.selectActiveMigrationWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.progress.selectMigrationProgressCheckpoint
import moe.ouom.neriplayer.core.download.storage.migration.progress.shouldPreserveMigrationUiAfterWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.progress.shouldResumePersistedMigrationAfterWorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.migrationProgressFromWorkData
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthState
import moe.ouom.neriplayer.data.settings.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.LyricFontScaleTarget
import moe.ouom.neriplayer.data.settings.LyricFontScales
import moe.ouom.neriplayer.data.settings.ThemeMode
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsListItem
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsMetadata
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsScopes
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchItems
import moe.ouom.neriplayer.data.settings.normalizeMobileDataBiliAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeMobileDataNeteaseAudioQuality
import moe.ouom.neriplayer.data.settings.normalizeMobileDataYouTubeAudioQuality
import moe.ouom.neriplayer.data.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.storage.StorageUsageSummary
import moe.ouom.neriplayer.data.storage.analyzeStorageUsage
import moe.ouom.neriplayer.ui.component.settings.LanguageSettingItem
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.util.time.elapsedMillisSince
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.screen.tab.settings.about.SettingsAboutContent
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.LoginSuccessDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsBiliAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsNeteaseAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAuthDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.component.LazyAnimatedVisibility
import moe.ouom.neriplayer.ui.screen.tab.settings.component.PlaybackServiceIdleShutdownSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsAudioQualitySection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsBackupRestoreSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsDownloadSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsDownloadQualityFollowPlaybackCard
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsLyricsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsMotionSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsPlaybackSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsStorageCacheSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.StorageCacheDetailsContent
import moe.ouom.neriplayer.ui.screen.tab.settings.component.SettingsTrafficManagementSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeSeedListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.UsbExclusiveSettingsSection
import moe.ouom.neriplayer.ui.screen.tab.settings.component.YouTubePlaybackSourceSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsGitHubDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsPreferenceDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.dialog.SettingsWebDavDialogs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHeader
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsHomeScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsPageGroupCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsResponsiveDetailScaffold
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionIntro
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsHomePageGroups
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsSearchEntry
import moe.ouom.neriplayer.ui.screen.tab.settings.page.backTargetPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.buildSettingsSearchEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.resolveSettingsSearchHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.page.miuixSettingsSectionCardItem
import moe.ouom.neriplayer.ui.screen.tab.settings.page.searchSettingsEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsSearchScrollAnchor
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.screen.tab.settings.state.formatSyncTime
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.util.currentWindowWidthDp
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.BiliAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.YouTubeAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseAuthViewModel
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val MIGRATION_CHECKPOINT_RETRY_DELAY_MS = 1_000L
private const val MIGRATION_SNAPSHOT_READ_RETRY_LIMIT = 3

private data class PendingDownloadDirectoryChange(
    val previousUri: String?,
    val targetUri: String?,
    val targetSummary: String,
    val releaseTargetPermissionOnCancel: Boolean,
    val targetNonEmpty: Boolean = false
) {
    val shouldReleasePreviousPermission: Boolean
        get() = !previousUri.isNullOrBlank() &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(previousUri, targetUri)

    val shouldShowTargetConflictWarning: Boolean
        get() = targetNonEmpty && !targetUri.isNullOrBlank()
}

private enum class DownloadDirectoryPreparationResult {
    KEEP_PERSISTED_PERMISSION,
    RELEASE_PERSISTED_PERMISSION
}

/**
 * 共享处理横幅已经接管目录操作时，设置页不再弹出第二个模态窗口
 */
internal data class DownloadDirectoryProcessingPresentation(
    val showPreparation: Boolean,
    val showMigration: Boolean,
    val usesSharedProcessing: Boolean
)

internal fun resolveDownloadDirectoryProcessingPresentation(
    isPreparing: Boolean,
    isMigrating: Boolean,
    processingState: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): DownloadDirectoryProcessingPresentation {
    val usesSharedProcessing = processingState != ManagedLibraryProcessingState.Idle
    val hasMigrationWork = isMigrating || migrationProgress != null
    return DownloadDirectoryProcessingPresentation(
        showPreparation = isPreparing && !usesSharedProcessing && !hasMigrationWork,
        showMigration = hasMigrationWork && !usesSharedProcessing,
        usesSharedProcessing = usesSharedProcessing
    )
}

private data class PersistedMigrationUiSnapshot(
    val activeWorkId: String?,
    val activeWorkState: WorkInfo.State?,
    val progress: ManagedDownloadStorage.MigrationProgress?,
    val requestAutoResume: Boolean,
    val hasPersistedRequest: Boolean,
    val journalPhase: ManagedMigrationReplacementJournalPhase?,
    val checkpointReadFailed: Boolean
) {
    val shouldPreserveUi: Boolean
        get() = shouldPreserveMigrationUiAfterWorkInfo(
            workInfoState = activeWorkState,
            requestAutoResume = requestAutoResume,
            journalPhase = journalPhase,
            hasPersistedRequest = hasPersistedRequest,
            checkpointReadFailed = checkpointReadFailed
        )

    val shouldResume: Boolean
        get() = shouldResumePersistedMigrationAfterWorkInfo(
            workInfoState = activeWorkState,
            requestAutoResume = requestAutoResume,
            journalPhase = journalPhase,
            hasPersistedRequest = hasPersistedRequest,
            checkpointReadFailed = checkpointReadFailed
        )
}

private data class PersistedMigrationSnapshotApplyResult(
    val preservedUi: Boolean,
    val attemptedAutoResume: Boolean
)

internal fun shouldRetryMigrationSnapshotRead(
    consecutiveFailures: Int,
    retryLimit: Int = MIGRATION_SNAPSHOT_READ_RETRY_LIMIT
): Boolean = consecutiveFailures < retryLimit

internal fun shouldAttemptMigrationAutoResume(
    shouldResume: Boolean,
    autoResumeAttempted: Boolean
): Boolean = shouldResume && !autoResumeAttempted

internal fun shouldStopMigrationRecoveryAfterNoProgress(
    shouldPreserveUi: Boolean,
    needsRecovery: Boolean,
    snapshotChanged: Boolean,
    autoResumeAttempted: Boolean
): Boolean = shouldPreserveUi && needsRecovery && autoResumeAttempted && !snapshotChanged

/** 同时读取 WorkManager 和持久检查点，避免缺少任务行时抹掉界面状态 */
private fun readPersistedMigrationUiSnapshot(context: Context): PersistedMigrationUiSnapshot {
    val appContext = context.applicationContext
    val checkpointStore = ManagedDownloadMigrationCheckpointStore(appContext)
    val requestResult = runCatching { checkpointStore.readRequest() }
    val journalResult = runCatching { checkpointStore.readReplacementJournal() }
    val request = requestResult.getOrNull()
    val journal = journalResult.getOrNull()
    val workInfoResult = runCatching {
        WorkManager.getInstance(appContext)
            .getWorkInfosForUniqueWork(ManagedDownloadMigrationWorker.WORK_NAME)
            .get()
    }
    val workInfos = workInfoResult.getOrElse { emptyList() }
    val activeWork = selectActiveMigrationWorkInfo(
        workInfos = workInfos,
        preferredWorkId = request?.workId,
        fallbackWorkId = request?.checkpointWorkId ?: journal?.workId
    )
    val currentWorkId = activeWork?.id?.toString()
        ?: request?.workId
        ?: journal?.workId
        ?: ""
    val progress = selectMigrationProgressCheckpoint(
        checkpointIds = migrationProgressCheckpointIds(
            currentWorkId = currentWorkId,
            inputCheckpointWorkId = request?.checkpointWorkId,
            persistedRequest = request,
            persistedJournal = journal
        ),
        readProgress = checkpointStore::readProgress
    )
    val progressWithWorkInfo = activeWork?.let { work ->
        migrationProgressFromWorkData(work.progress)
    }?.let { workProgress ->
        progress?.let { durable ->
            mergeMigrationProgressFloor(floor = durable, current = workProgress)
        } ?: workProgress
    } ?: progress
    return PersistedMigrationUiSnapshot(
        activeWorkId = activeWork?.id?.toString(),
        activeWorkState = activeWork?.state,
        progress = progressWithWorkInfo,
        requestAutoResume = request?.autoResume == true,
        hasPersistedRequest = request != null,
        journalPhase = journal?.phase,
        checkpointReadFailed = requestResult.isFailure ||
            journalResult.isFailure ||
            workInfoResult.isFailure
    )
}


private fun Context.neteaseQualityLabel(value: String): String {
    return when (value) {
        "standard" -> getString(R.string.settings_audio_quality_standard)
        "higher" -> getString(R.string.settings_audio_quality_higher)
        "exhigh" -> getString(R.string.settings_audio_quality_exhigh)
        "lossless" -> getString(R.string.settings_audio_quality_lossless)
        "hires" -> getString(R.string.quality_hires)
        "jyeffect" -> getString(R.string.settings_audio_quality_jyeffect)
        "sky" -> getString(R.string.settings_audio_quality_sky)
        "jymaster" -> getString(R.string.settings_audio_quality_jymaster)
        else -> value
    }
}

private fun Context.youtubeQualityLabel(value: String): String {
    return when (value) {
        "low" -> getString(R.string.settings_audio_quality_standard)
        "medium" -> getString(R.string.settings_audio_quality_medium)
        "high" -> getString(R.string.settings_audio_quality_high)
        "very_high" -> getString(R.string.quality_very_high)
        else -> value
    }
}

private fun Context.biliQualityLabel(value: String): String {
    return when (value) {
        "dolby" -> getString(R.string.settings_audio_quality_dolby)
        "hires" -> getString(R.string.quality_hires)
        "lossless" -> getString(R.string.settings_audio_quality_lossless)
        "high" -> getString(R.string.settings_audio_quality_high)
        "medium" -> getString(R.string.settings_audio_quality_medium)
        "low" -> getString(R.string.settings_audio_quality_low)
        else -> value
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun SettingsScreen(
    listState: LazyListState,
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeToggleRequest: (Offset, Float) -> Unit,
    onThemeModeRequest: (ThemeMode, Offset, Float) -> Unit,
    preferredQuality: String,
    onQualityChange: (String) -> Unit,
    youtubePreferredQuality: String,
    onYouTubeQualityChange: (String) -> Unit,
    biliPreferredQuality: String,
    onBiliQualityChange: (String) -> Unit,
    mobileDataFollowDefaultAudioQuality: Boolean,
    onMobileDataFollowDefaultAudioQualityChange: (Boolean) -> Unit,
    mobileDataNeteaseAudioQuality: String,
    onMobileDataNeteaseAudioQualityChange: (String) -> Unit,
    mobileDataYouTubeAudioQuality: String,
    onMobileDataYouTubeAudioQualityChange: (String) -> Unit,
    mobileDataBiliAudioQuality: String,
    onMobileDataBiliAudioQualityChange: (String) -> Unit,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    seedColorHex: String,
    onSeedColorChange: (String) -> Unit,
    themeColorPalette: List<String>,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    themePaletteStyle: String,
    onThemePaletteStyleChange: (String) -> Unit,
    themeColorSpec: String,
    onThemeColorSpecChange: (String) -> Unit,
    lyricBlurEnabled: Boolean,
    onLyricBlurEnabledChange: (Boolean) -> Unit,
    lyricBlurAmount: Float,
    onLyricBlurAmountChange: (Float) -> Unit,
    cloudMusicLyricDefaultOffsetMs: Long,
    onCloudMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    qqMusicLyricDefaultOffsetMs: Long,
    onQqMusicLyricDefaultOffsetMsChange: (Long) -> Unit,
    kugouLyricDefaultOffsetMs: Long,
    onKugouLyricDefaultOffsetMsChange: (Long) -> Unit,
    lrclibLyricDefaultOffsetMs: Long,
    onLrclibLyricDefaultOffsetMsChange: (Long) -> Unit,
    amllTtmlLyricDefaultOffsetMs: Long,
    onAmllTtmlLyricDefaultOffsetMsChange: (Long) -> Unit,
    onResetAllLyricDefaultOffsets: () -> Unit,
    floatingLyricsPreferences: FloatingLyricsPreferences,
    onFloatingLyricsPreferencesChange: (FloatingLyricsPreferences) -> Unit,
    advancedBlurEnabled: Boolean,
    onAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurEnabled: Boolean,
    onEnhancedAdvancedBlurEnabledChange: (Boolean) -> Unit,
    enhancedAdvancedBlurRadiusDp: Float,
    onEnhancedAdvancedBlurRadiusDpChange: (Float) -> Unit,
    advancedBlurQuality: AdvancedBlurQuality,
    onAdvancedBlurQualityChange: (AdvancedBlurQuality) -> Unit,
    nowPlayingAudioReactiveEnabled: Boolean,
    onNowPlayingAudioReactiveEnabledChange: (Boolean) -> Unit,
    nowPlayingDynamicBackgroundEnabled: Boolean,
    onNowPlayingDynamicBackgroundEnabledChange: (Boolean) -> Unit,
    nowPlayingCoverBlurBackgroundEnabled: Boolean,
    onNowPlayingCoverBlurBackgroundEnabledChange: (Boolean) -> Unit,
    nowPlayingCoverBlurAmount: Float,
    onNowPlayingCoverBlurAmountChange: (Float) -> Unit,
    nowPlayingCoverBlurDarken: Float,
    onNowPlayingCoverBlurDarkenChange: (Float) -> Unit,
    lyricFontScales: LyricFontScales,
    onLyricFontScaleChange: (LyricFontScaleTarget, Float) -> Unit,
    uiDensityScale: Float,
    onUiDensityScaleChange: (Float) -> Unit,
    bypassProxy: Boolean,
    onBypassProxyChange: (Boolean) -> Unit,
    backgroundImageUri: String?,
    onBackgroundImageChange: (Uri?) -> Unit,
    downloadDirectoryUri: String?,
    downloadFileNameTemplate: String?,
    onDownloadDirectoryUriChange: (String?, String?) -> Unit,
    onDownloadFileNameTemplateChange: (String?) -> Unit,
    backgroundImageBlur: Float,
    onBackgroundImageBlurChange: (Float) -> Unit,
    onBackgroundImageBlurChangeFinished: (Float) -> Unit,
    backgroundImageAlpha: Float,
    onBackgroundImageAlphaChange: (Float) -> Unit,
    onBackgroundImageAlphaChangeFinished: (Float) -> Unit,
    defaultStartDestination: String,
    onDefaultStartDestinationChange: (String) -> Unit,
    showHomeContinueCard: Boolean,
    onShowHomeContinueCardChange: (Boolean) -> Unit,
    showHomeTrendingCard: Boolean,
    onShowHomeTrendingCardChange: (Boolean) -> Unit,
    showHomeRadarCard: Boolean,
    onShowHomeRadarCardChange: (Boolean) -> Unit,
    showHomeRecommendedCard: Boolean,
    onShowHomeRecommendedCardChange: (Boolean) -> Unit,
    homeHasRecentUsage: Boolean,
    playbackFadeIn: Boolean,
    onPlaybackFadeInChange: (Boolean) -> Unit,
    playbackCrossfadeNext: Boolean,
    onPlaybackCrossfadeNextChange: (Boolean) -> Unit,
    sleepTimerFinishCurrentOnExpiry: Boolean,
    onSleepTimerFinishCurrentOnExpiryChange: (Boolean) -> Unit,
    playbackFadeInDurationMs: Long,
    onPlaybackFadeInDurationMsChange: (Long) -> Unit,
    playbackFadeOutDurationMs: Long,
    onPlaybackFadeOutDurationMsChange: (Long) -> Unit,
    playbackCrossfadeInDurationMs: Long,
    onPlaybackCrossfadeInDurationMsChange: (Long) -> Unit,
    playbackCrossfadeOutDurationMs: Long,
    onPlaybackCrossfadeOutDurationMsChange: (Long) -> Unit,
    playbackVolumeNormalizationEnabled: Boolean,
    onPlaybackVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    playbackHighResolutionOutputEnabled: Boolean,
    onPlaybackHighResolutionOutputEnabledChange: (Boolean) -> Unit,
    playbackVolumeBalance: Float,
    onPlaybackVolumeBalanceChange: (Float) -> Unit,
    keepLastPlaybackProgress: Boolean,
    onKeepLastPlaybackProgressChange: (Boolean) -> Unit,
    rememberLongFormPlaybackProgress: Boolean,
    onRememberLongFormPlaybackProgressChange: (Boolean) -> Unit,
    keepPlaybackModeState: Boolean,
    onKeepPlaybackModeStateChange: (Boolean) -> Unit,
    neteaseAutoSourceSwitch: Boolean,
    onNeteaseAutoSourceSwitchChange: (Boolean) -> Unit,
    neteaseLocalSourceFallback: Boolean,
    onNeteaseLocalSourceFallbackChange: (Boolean) -> Unit,
    stopOnBluetoothDisconnect: Boolean,
    onStopOnBluetoothDisconnectChange: (Boolean) -> Unit,
    usbExclusivePlayback: Boolean,
    onUsbExclusivePlaybackChange: (Boolean) -> Unit,
    allowMixedPlayback: Boolean,
    onAllowMixedPlaybackChange: (Boolean) -> Unit,
    preemptAudioFocus: Boolean,
    onPreemptAudioFocusChange: (Boolean) -> Unit,
    onNavigateToDownloadManager: () -> Unit = {},
    maxCacheSizeBytes: Long,
    onMaxCacheSizeBytesChange: (Long) -> Unit,
    onClearCacheClick: (StorageCacheClearOptions) -> Unit,
    onBeforeLanguageRestart: () -> Unit = {},
    onLanguageChanged: (LanguageManager.Language) -> Unit = {},
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val scope = rememberCoroutineScope()
    val autoSettingsRepository = remember(context) { AutoSettingsRepository(context) }
    val listenTogetherPreferences = remember { AppContainer.listenTogetherPreferences }
    val listenTogetherApi = remember { AppContainer.listenTogetherApi }
    val listenTogetherSessionManager = remember { AppContainer.listenTogetherSessionManager }
    val listenTogetherSettings = rememberSettingsListenTogetherController(
        preferences = listenTogetherPreferences,
        api = listenTogetherApi,
        sessionManager = listenTogetherSessionManager,
        onMessage = { AppFeedback.showToast(context = context, message = it) }
    )
    var pendingBackgroundImageBlur by rememberSaveable(backgroundImageUri) {
        mutableFloatStateOf(backgroundImageBlur)
    }
    var pendingBackgroundImageAlpha by rememberSaveable(backgroundImageUri) {
        mutableFloatStateOf(backgroundImageAlpha)
    }

    LaunchedEffect(backgroundImageBlur, backgroundImageUri) {
        if ((pendingBackgroundImageBlur - backgroundImageBlur).absoluteValue > 0.001f) {
            pendingBackgroundImageBlur = backgroundImageBlur
        }
    }
    LaunchedEffect(backgroundImageAlpha, backgroundImageUri) {
        if ((pendingBackgroundImageAlpha - backgroundImageAlpha).absoluteValue > 0.001f) {
            pendingBackgroundImageAlpha = backgroundImageAlpha
        }
    }

    val internationalEnabled by AppContainer.settingsRepo.internationalizationEnabledFlow
        .collectAsState(initial = false)
    val usbExclusivePreferences by AppContainer.settingsRepo.usbExclusivePreferencesFlow
        .collectAsState(initial = UsbExclusivePreferences())

    LaunchedEffect(nowPlayingDynamicBackgroundEnabled, nowPlayingCoverBlurBackgroundEnabled) {
        if (nowPlayingCoverBlurBackgroundEnabled) {
            if (nowPlayingDynamicBackgroundEnabled) {
                onNowPlayingDynamicBackgroundEnabledChange(false)
            }
            if (nowPlayingAudioReactiveEnabled) {
                onNowPlayingAudioReactiveEnabledChange(false)
            }
        } else if (!nowPlayingDynamicBackgroundEnabled && nowPlayingAudioReactiveEnabled) {
            onNowPlayingAudioReactiveEnabledChange(false)
        }
    }

    // 缓存设置的状态
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // 缓存类型选择状态
    var clearAudioCache by remember { mutableStateOf(true) }
    var clearImageCache by remember { mutableStateOf(true) }
    var clearDownloadStagingCache by remember { mutableStateOf(false) }
    var clearSharedMediaCache by remember { mutableStateOf(false) }
    var clearLyricsCache by remember { mutableStateOf(false) }
    var clearNeteasePlaylistCache by remember { mutableStateOf(false) }
    var clearBiliFavoriteCache by remember { mutableStateOf(false) }
    var clearBiliArchiveCache by remember { mutableStateOf(false) }
    var clearYoutubePlaylistCache by remember { mutableStateOf(false) }
    var clearLogFiles by remember { mutableStateOf(false) }
    var clearCrashLogs by remember { mutableStateOf(false) }

    // 存储占用详情状态
    var storageDetails by remember { mutableStateOf(StorageUsageSummary.Empty) }
    var storageDetailsLoading by remember { mutableStateOf(false) }
    var storageScanRequest by rememberSaveable { mutableIntStateOf(0) }


    // 各种对话框和弹窗的显示状态 //
    var showQualityDialog by remember { mutableStateOf(false) }
    var showNeteaseSheet by remember { mutableStateOf(false) }
    var showYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showBiliQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataNeteaseQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataYouTubeQualityDialog by remember { mutableStateOf(false) }
    var showMobileDataBiliQualityDialog by remember { mutableStateOf(false) }
    var showDefaultStartDestinationDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showNeteaseSavedCookieDialog by remember { mutableStateOf(false) }
    var showBiliSheet by remember { mutableStateOf(false) }
    var showBiliSavedCookieDialog by remember { mutableStateOf(false) }
    var showYouTubeSheet by remember { mutableStateOf(false) }
    var showYouTubeSavedCookieDialog by remember { mutableStateOf(false) }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var showDpiDialog by remember { mutableStateOf(false) }
    var showGitHubConfigDialog by remember { mutableStateOf(false) }
    var showClearGitHubConfigDialog by remember { mutableStateOf(false) }
    var showWebDavConfigDialog by remember { mutableStateOf(false) }
    var showClearWebDavConfigDialog by remember { mutableStateOf(false) }
    // ------------------------------------

    val neteaseVm: NeteaseAuthViewModel = viewModel()
    var inlineMsg by remember { mutableStateOf<String?>(null) }
    var loginSuccessTitle by remember { mutableStateOf<String?>(null) }
    var confirmPhoneMasked by remember { mutableStateOf<String?>(null) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    val biliVm: BiliAuthViewModel = viewModel()
    var biliSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    var neteaseSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    val youtubeVm: YouTubeAuthViewModel = viewModel()
    var youtubeSheetInitialTab by rememberSaveable { mutableIntStateOf(0) }
    
    // 备份与恢复
    val backupRestoreVm: BackupRestoreViewModel = viewModel()
    val backupRestoreUiState by backupRestoreVm.uiState.collectAsStateWithLifecycleCompat()
    val configTransferVm: ConfigTransferViewModel = viewModel()
    val configTransferUiState by configTransferVm.uiState.collectAsStateWithLifecycleCompat()
    val localPlaylistCount = backupRestoreUiState.currentPlaylistCount

    fun showSettingsMessage(message: String) {
        AppFeedback.show(context = context, message = message)
    }

    val downloadDirectorySettings = rememberDownloadDirectorySettingsController(
        downloadDirectoryUri = downloadDirectoryUri,
        onDownloadDirectoryUriChange = onDownloadDirectoryUriChange,
        onInlineMessageChange = { inlineMsg = it },
        onShowMessage = ::showSettingsMessage
    )

    // 照片选择器
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                scope.launch {
                    val importedUri = BackgroundImageStorage.importFromUri(
                        context = context,
                        sourceUri = uri,
                        previousUriString = backgroundImageUri
                    )
                    if (importedUri != null) {
                        onBackgroundImageChange(importedUri)
                    }
                }
            }
        }
    )

    // 备份与恢复的SAF启动器
    val exportPlaylistLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.exportPlaylists(uri)
        }
    }

    val importPlaylistLauncher = rememberLauncherForActivityResult(
        contract = OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupRestoreVm.initialize(context)
            backupRestoreVm.importPlaylists(uri)
        }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            configTransferVm.initialize(context)
            configTransferVm.exportConfig(uri)
        }
    }

    val importConfigLauncher = rememberLauncherForActivityResult(
        contract = OpenDocument()
    ) { uri ->
        if (uri != null) {
            configTransferVm.initialize(context)
            configTransferVm.importConfig(uri)
        }
    }

    LaunchedEffect(configTransferUiState.importRequiresActivityRecreate) {
        if (!configTransferUiState.importRequiresActivityRecreate) {
            return@LaunchedEffect
        }
        onBeforeLanguageRestart()
        configTransferVm.consumeImportRecreateRequest()
        (context as? android.app.Activity)?.recreate()
    }

    val qualityLabel = remember(preferredQuality) {
        context.neteaseQualityLabel(preferredQuality)
    }

    val biliQualityLabel = remember(biliPreferredQuality) {
        context.biliQualityLabel(biliPreferredQuality)
    }

    val youtubeQualityLabel = remember(youtubePreferredQuality) {
        context.youtubeQualityLabel(youtubePreferredQuality)
    }

    val normalizedMobileDataNeteaseAudioQuality = remember(mobileDataNeteaseAudioQuality) {
        normalizeMobileDataNeteaseAudioQuality(mobileDataNeteaseAudioQuality)
    }
    val normalizedMobileDataYouTubeAudioQuality = remember(mobileDataYouTubeAudioQuality) {
        normalizeMobileDataYouTubeAudioQuality(mobileDataYouTubeAudioQuality)
    }
    val normalizedMobileDataBiliAudioQuality = remember(mobileDataBiliAudioQuality) {
        normalizeMobileDataBiliAudioQuality(mobileDataBiliAudioQuality)
    }
    val mobileDataNeteaseQualityLabel = remember(normalizedMobileDataNeteaseAudioQuality) {
        context.neteaseQualityLabel(normalizedMobileDataNeteaseAudioQuality)
    }
    val mobileDataYouTubeQualityLabel = remember(normalizedMobileDataYouTubeAudioQuality) {
        context.youtubeQualityLabel(normalizedMobileDataYouTubeAudioQuality)
    }
    val mobileDataBiliQualityLabel = remember(normalizedMobileDataBiliAudioQuality) {
        context.biliQualityLabel(normalizedMobileDataBiliAudioQuality)
    }
    val density = LocalDensity.current

    val homeStartAvailable =
        showHomeTrendingCard ||
            showHomeRadarCard ||
            showHomeRecommendedCard ||
            (showHomeContinueCard && homeHasRecentUsage)
    val homeTrendingLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_guess_you_like
    } else {
        R.string.settings_home_card_netease_trending
    }
    val homeRadarLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_daily_discover
    } else {
        R.string.settings_home_card_netease_radar
    }
    val homeRecommendedLabelRes = if (internationalEnabled) {
        R.string.home_ytmusic_more_recommendations
    } else {
        R.string.settings_home_card_netease_recommended
    }
    val homeTrendingSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_guess_you_like_desc
    } else {
        R.string.settings_home_card_netease_trending_desc
    }
    val homeRadarSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_daily_discover_desc
    } else {
        R.string.settings_home_card_netease_radar_desc
    }
    val homeRecommendedSupportingRes = if (internationalEnabled) {
        R.string.settings_home_card_ytmusic_more_recommendations_desc
    } else {
        R.string.settings_home_card_netease_recommended_desc
    }
    val effectiveDefaultStartDestination = remember(defaultStartDestination, homeStartAvailable) {
        if (!homeStartAvailable && defaultStartDestination == "home") {
            "explore"
        } else {
            defaultStartDestination
        }
    }
    val defaultStartDestinationLabel = remember(effectiveDefaultStartDestination, context) {
        when (effectiveDefaultStartDestination) {
            "explore" -> composeResources.getString(R.string.nav_explore)
            "library" -> composeResources.getString(R.string.nav_library)
            "settings" -> composeResources.getString(R.string.nav_settings)
            else -> composeResources.getString(R.string.nav_home)
        }
    }
    LaunchedEffect(neteaseVm) {
        neteaseVm.events.collect { e ->
            when (e) {
                is NeteaseAuthEvent.ShowSnack -> {
                    inlineMsg = e.message
                }
                is NeteaseAuthEvent.AskConfirmSend -> {
                    confirmPhoneMasked = e.masked
                    showConfirmDialog = true
                }
                NeteaseAuthEvent.LoginSuccess -> {
                    showNeteaseSavedCookieDialog = false
                    inlineMsg = null
                    showNeteaseSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_netease_login_success
                    )
                    neteaseVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(biliVm) {
        biliVm.events.collect { e ->
            when (e) {
                is BiliAuthEvent.ShowSnack -> inlineMsg = e.message
                BiliAuthEvent.LoginSuccess -> {
                    showBiliSavedCookieDialog = false
                    inlineMsg = null
                    showBiliSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_bili_login_success
                    )
                    biliVm.refreshAuthHealth()
                }
            }
        }
    }

    LaunchedEffect(youtubeVm) {
        youtubeVm.events.collect { e ->
            when (e) {
                is YouTubeAuthEvent.ShowSnack -> inlineMsg = e.message
                YouTubeAuthEvent.LoginSuccess -> {
                    showYouTubeSavedCookieDialog = false
                    inlineMsg = null
                    showYouTubeSheet = false
                    loginSuccessTitle = composeResources.getString(
                        R.string.settings_youtube_login_success
                    )
                    youtubeVm.refreshAuthHealth()
                }

            }
        }
    }

    val isSettingsSplitLayout = currentWindowWidthDp() >= 840.dp
    var activeSettingsPage by rememberSaveable {
        mutableStateOf(if (isSettingsSplitLayout) SettingsPage.General else null)
    }
    fun refreshStorageDetails() {
        if (storageDetailsLoading) return
        storageScanRequest++
    }

    LaunchedEffect(storageScanRequest) {
        if (storageScanRequest == 0) return@LaunchedEffect
        storageDetailsLoading = true
        yield()
        try {
            storageDetails = analyzeStorageUsage(context)
        } finally {
            storageDetailsLoading = false
        }
    }

    LaunchedEffect(activeSettingsPage) {
        if (activeSettingsPage == SettingsPage.StorageCacheDetails && storageDetails == StorageUsageSummary.Empty) {
            refreshStorageDetails()
        }
    }
    LaunchedEffect(activeSettingsPage, context) {
        if (activeSettingsPage == SettingsPage.Backup) {
            backupRestoreVm.observePlaylistCount(context)
        }
    }
    val homeTopAppBarState = rememberTopAppBarState()
    val detailTopAppBarStates = SettingsPage.entries.associateWith { rememberTopAppBarState() }
    val detailListStates = SettingsPage.entries.associateWith {
        rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    }
    var settingsSearchQuery by rememberSaveable { mutableStateOf("") }
    var settingsHighlightTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsHighlightPulse by rememberSaveable { mutableIntStateOf(0) }
    var settingsSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var pendingSettingsSearchNavigation by remember { mutableStateOf<PendingSettingsSearchNavigation?>(null) }
    val settingsSearchEntries = remember(context) { buildSettingsSearchEntries(context) }
    val settingsSearchResults = remember(settingsSearchEntries, settingsSearchQuery) {
        searchSettingsEntries(settingsSearchEntries, settingsSearchQuery, limit = 8)
    }
    val onSettingsHighlightFinished: () -> Unit = {
        settingsHighlightTargetId = null
    }

    LaunchedEffect(isSettingsSplitLayout) {
        if (isSettingsSplitLayout && activeSettingsPage == null) {
            activeSettingsPage = SettingsPage.General
        }
    }

    LaunchedEffect(settingsSearchQuery) {
        if (settingsSearchQuery.isNotBlank()) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(activeSettingsPage, pendingSettingsSearchNavigation) {
        val pending = pendingSettingsSearchNavigation ?: return@LaunchedEffect
        if (activeSettingsPage != pending.page) {
            return@LaunchedEffect
        }
        settingsHighlightTargetId = null
        val detailListState = detailListStates.getValue(pending.page)
        val anchor = settingsSearchScrollAnchor(
            page = pending.page,
            targetId = pending.targetId
        )
        withFrameNanos { }
        withFrameNanos { }
        snapshotFlow { detailListState.layoutInfo.totalItemsCount }
            .first { it > anchor.itemIndex }
        detailListState.scrollToItem(
            index = anchor.itemIndex,
            scrollOffset = with(density) { anchor.scrollOffset.toPx().roundToInt() }
        )
        withFrameNanos { }
        detailListState.scrollToItem(
            index = anchor.itemIndex,
            scrollOffset = with(density) { anchor.scrollOffset.toPx().roundToInt() }
        )
        settingsHighlightTargetId = pending.targetId
        settingsHighlightPulse = pending.requestId
        pendingSettingsSearchNavigation = null
    }

    fun navigateBackFromActiveSettingsPage() {
        activeSettingsPage = activeSettingsPage?.backTargetPage()
    }

    val settingsPageBackTarget = activeSettingsPage?.backTargetPage()
    val isolateAdvancedGlassTransitions = LocalAdvancedGlassController.current.isEnabled
    BackHandler(
        enabled = activeSettingsPage != null &&
            (!isSettingsSplitLayout || settingsPageBackTarget != null)
    ) {
        navigateBackFromActiveSettingsPage()
    }

    val settingsHomeTitle: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.settings_title))
            ThemeModeActionButton(
                isDarkTheme = isDarkTheme,
                onToggleRequest = onThemeToggleRequest
            )
        }
    }
    val onSettingsSearchResultClick: (SettingsSearchEntry) -> Unit = { entry ->
        val targetId = resolveSettingsSearchHighlightTarget(
            entry = entry,
            dynamicColor = dynamicColor,
            mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
            hasCustomBackground = backgroundImageUri != null
        )
        activeSettingsPage = entry.page
        settingsHighlightTargetId = null
        settingsSearchRequestId += 1
        pendingSettingsSearchNavigation = PendingSettingsSearchNavigation(
            page = entry.page,
            targetId = targetId,
            requestId = settingsSearchRequestId
        )
    }
    val settingsHomeContent: LazyListScope.() -> Unit = {
        item(key = "settings_search_field") {
            SettingsSearchField(
                query = settingsSearchQuery,
                onQueryChange = { settingsSearchQuery = it }
            )
        }
        if (settingsSearchQuery.isNotBlank()) {
            item(key = "settings_search_results") {
                SettingsSearchResultsCard(
                    results = settingsSearchResults,
                    onResultClick = onSettingsSearchResultClick
                )
            }
        }
        SettingsHomePageGroups.forEachIndexed { groupIndex, pages ->
            item(key = "settings_group_$groupIndex") {
                MiuixSettingsPageGroupCard(
                    pages = pages,
                    onPageClick = { page -> activeSettingsPage = page },
                    selectedPage = activeSettingsPage?.backTargetPage() ?: activeSettingsPage,
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    val settingsPageContent: @Composable (SettingsPage?) -> Unit = { selectedPage ->
        if (selectedPage == null) {
            MiuixSettingsHomeScaffold(
                listState = listState,
                topAppBarState = homeTopAppBarState,
                title = settingsHomeTitle,
                content = settingsHomeContent
            )
        } else {
            MiuixSettingsResponsiveDetailScaffold(
                title = stringResource(selectedPage.titleRes),
                onBack = ::navigateBackFromActiveSettingsPage,
                listState = detailListStates.getValue(selectedPage),
                topAppBarState = detailTopAppBarStates.getValue(selectedPage),
                splitLayout = isSettingsSplitLayout,
                showSplitDetailBackButton = settingsPageBackTarget != null,
                selectedPage = selectedPage,
                homeListState = listState,
                homeTopAppBarState = homeTopAppBarState,
                homeTitle = settingsHomeTitle,
                homeContent = settingsHomeContent
            ) {
                item(key = "${selectedPage.name}:header") {
                    MiuixSettingsHeader(
                        icon = selectedPage.icon,
                        title = stringResource(selectedPage.titleRes),
                        description = stringResource(selectedPage.descriptionRes),
                        modifier = Modifier
                            .animateItem()
                            .settingsHighlightTarget(
                                targetId = "page:${selectedPage.name}",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                    )
                }

                if (
                    selectedPage == SettingsPage.Storage &&
                    downloadDirectorySettings.processingPresentation.usesSharedProcessing
                ) {
                    item(key = "${selectedPage.name}:processing") {
                        ManagedLibraryProcessingDetailsCard(
                            state = downloadDirectorySettings.libraryProcessing,
                            migrationProgress = downloadDirectorySettings.migrationProgress
                        )
                    }
                }

                when (selectedPage) {
                SettingsPage.General -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        AutoSettingsSwitchItems(
                            repository = autoSettingsRepository,
                            scope = scope,
                            sectionScope = AutoSettingsScopes.general,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        PlaybackServiceIdleShutdownSetting(
                            repository = autoSettingsRepository,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        LanguageSettingItem(
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:language",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            ),
                            onLanguageChanged = onLanguageChanged
                        )
                        ListItem(
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:internationalization",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            ),
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_i18n),
                                    contentDescription = stringResource(R.string.settings_internationalization),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            headlineContent = { Text(stringResource(R.string.settings_internationalization)) },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.settings_internationalization_desc)
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = internationalEnabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch {
                                            AppContainer.settingsRepo.setInternationalizationEnabled(enabled)
                                        }
                                    }
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_ui_scale),
                            description = stringResource(R.string.settings_ui_scale_global_desc)
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.UI_DENSITY_SCALE),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.ZoomInMap,
                                    contentDescription = stringResource(R.string.settings_ui_scale),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        R.string.settings_ui_scale_current,
                                        "%.2f".format(uiDensityScale)
                                    )
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { showDpiDialog = true }
                        )
                    }
                }

                SettingsPage.Theme -> {
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:mode") {
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_theme_mode),
                            description = stringResource(R.string.settings_theme_mode_desc)
                        )
                        ThemeModeSelectorListItem(
                            isDarkTheme = isDarkTheme,
                            themeMode = themeMode,
                            onThemeModeRequest = onThemeModeRequest,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_mode",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                        ThemeAutoModeListItem(
                            themeMode = themeMode,
                            isDarkTheme = isDarkTheme,
                            onThemeModeRequest = onThemeModeRequest
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:dynamic_color") {
                        MiuixSettingsSectionIntro(
                            title = stringResource(R.string.settings_theme_color_section),
                            description = stringResource(R.string.settings_theme_color_section_desc)
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.DYNAMIC_COLOR),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.Colorize,
                                    contentDescription = stringResource(R.string.settings_dynamic_color),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { onDynamicColorChange(!dynamicColor) }
                        )
                        LazyAnimatedVisibility(visible = !dynamicColor) {
                            ThemeSeedListItem(
                                seedColorHex = seedColorHex,
                                onClick = { showColorPickerDialog = true },
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_theme_palette_hint),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:palette_style") {
                        ThemePaletteStyleSelector(
                            selectedStyle = themePaletteStyle,
                            onStyleChange = onThemePaletteStyleChange,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_palette_style",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                    }
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:color_spec") {
                        ThemeColorSpecSelector(
                            selectedSpec = themeColorSpec,
                            onSpecChange = onThemeColorSpecChange,
                            modifier = Modifier.settingsHighlightTarget(
                                targetId = "manual:theme_color_spec",
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        )
                    }
                }

                SettingsPage.Accounts -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsLoginExpandedContent(
                            biliVm = biliVm,
                            youtubeVm = youtubeVm,
                            neteaseVm = neteaseVm,
                            onOpenBiliSheet = { tab ->
                                inlineMsg = null
                                biliSheetInitialTab = tab
                                showBiliSheet = true
                            },
                            onOpenBiliSavedCookieDialog = {
                                inlineMsg = null
                                showBiliSavedCookieDialog = true
                            },
                            onOpenYouTubeSavedCookieDialog = {
                                inlineMsg = null
                                showYouTubeSavedCookieDialog = true
                            },
                            onOpenNeteaseSavedCookieDialog = {
                                inlineMsg = null
                                showNeteaseSavedCookieDialog = true
                            },
                            onOpenYouTubeSheet = {
                                inlineMsg = null
                                youtubeSheetInitialTab = 0
                                showYouTubeSheet = true
                            },
                            onOpenNeteaseSheet = {
                                inlineMsg = null
                                neteaseSheetInitialTab = 0
                                showNeteaseSheet = true
                            }
                        )
                    }
                }

                SettingsPage.Personalization -> {
                    for (cardIndex in 0..4) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            when (cardIndex) {
                                0 -> SettingsPersonalizationStartCard(
                                    autoSettingsRepository = autoSettingsRepository,
                                    scope = scope,
                                    defaultStartDestinationLabel = defaultStartDestinationLabel,
                                    onOpenDefaultStartDestination = {
                                        showDefaultStartDestinationDialog = true
                                    },
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
                                1 -> SettingsPersonalizationHomeCard(
                                    internationalEnabled = internationalEnabled,
                                    homeTrendingLabelRes = homeTrendingLabelRes,
                                    homeRadarLabelRes = homeRadarLabelRes,
                                    homeRecommendedLabelRes = homeRecommendedLabelRes,
                                    homeTrendingSupportingRes = homeTrendingSupportingRes,
                                    homeRadarSupportingRes = homeRadarSupportingRes,
                                    homeRecommendedSupportingRes = homeRecommendedSupportingRes,
                                    homeStartAvailable = homeStartAvailable,
                                    showHomeContinueCard = showHomeContinueCard,
                                    onShowHomeContinueCardChange = onShowHomeContinueCardChange,
                                    showHomeTrendingCard = showHomeTrendingCard,
                                    onShowHomeTrendingCardChange = onShowHomeTrendingCardChange,
                                    showHomeRadarCard = showHomeRadarCard,
                                    onShowHomeRadarCardChange = onShowHomeRadarCardChange,
                                    showHomeRecommendedCard = showHomeRecommendedCard,
                                    onShowHomeRecommendedCardChange = onShowHomeRecommendedCardChange,
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
                                2 -> SettingsPersonalizationPlaybackInfoCard(
                                    autoSettingsRepository = autoSettingsRepository,
                                    scope = scope,
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
                                3 -> SettingsPersonalizationControlsCard(
                                    autoSettingsRepository = autoSettingsRepository,
                                    settingsRepository = AppContainer.settingsRepo,
                                    scope = scope,
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
                                4 -> SettingsPersonalizationBackgroundCard(
                                    backgroundImageUri = backgroundImageUri,
                                    onPickBackgroundImage = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    onClearBackgroundImage = {
                                        scope.launch {
                                            BackgroundImageStorage.deleteManagedBackground(
                                                context = context,
                                                uriString = backgroundImageUri
                                            )
                                            onBackgroundImageChange(null)
                                        }
                                    },
                                    pendingBackgroundImageBlur = pendingBackgroundImageBlur,
                                    onPendingBackgroundImageBlurChange = {
                                        pendingBackgroundImageBlur = it
                                    },
                                    onBackgroundImageBlurCommit = {
                                        onBackgroundImageBlurChange(pendingBackgroundImageBlur)
                                        onBackgroundImageBlurChangeFinished(pendingBackgroundImageBlur)
                                    },
                                    pendingBackgroundImageAlpha = pendingBackgroundImageAlpha,
                                    onPendingBackgroundImageAlphaChange = {
                                        pendingBackgroundImageAlpha = it
                                        onBackgroundImageAlphaChange(it)
                                    },
                                    onBackgroundImageAlphaCommit = {
                                        onBackgroundImageAlphaChangeFinished(pendingBackgroundImageAlpha)
                                    },
                                    highlightTargetId = settingsHighlightTargetId,
                                    highlightPulse = settingsHighlightPulse,
                                    onHighlightFinished = onSettingsHighlightFinished
                                )
                            }
                        }
                    }
                }

                SettingsPage.Motion -> {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsMotionSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                advancedBlurEnabled = advancedBlurEnabled,
                                onAdvancedBlurEnabledChange = onAdvancedBlurEnabledChange,
                                enhancedAdvancedBlurEnabled = enhancedAdvancedBlurEnabled,
                                onEnhancedAdvancedBlurEnabledChange =
                                    onEnhancedAdvancedBlurEnabledChange,
                                enhancedAdvancedBlurRadiusDp = enhancedAdvancedBlurRadiusDp,
                                onEnhancedAdvancedBlurRadiusDpChange =
                                    onEnhancedAdvancedBlurRadiusDpChange,
                                advancedBlurQuality = advancedBlurQuality,
                                onAdvancedBlurQualityChange = onAdvancedBlurQualityChange,
                                nowPlayingAudioReactiveEnabled = nowPlayingAudioReactiveEnabled,
                                onNowPlayingAudioReactiveEnabledChange =
                                    onNowPlayingAudioReactiveEnabledChange,
                                nowPlayingDynamicBackgroundEnabled =
                                    nowPlayingDynamicBackgroundEnabled,
                                onNowPlayingDynamicBackgroundEnabledChange =
                                    onNowPlayingDynamicBackgroundEnabledChange,
                                nowPlayingCoverBlurBackgroundEnabled =
                                    nowPlayingCoverBlurBackgroundEnabled,
                                onNowPlayingCoverBlurBackgroundEnabledChange =
                                    onNowPlayingCoverBlurBackgroundEnabledChange,
                                nowPlayingCoverBlurAmount = nowPlayingCoverBlurAmount,
                                onNowPlayingCoverBlurAmountChange = onNowPlayingCoverBlurAmountChange,
                                nowPlayingCoverBlurDarken = nowPlayingCoverBlurDarken,
                                onNowPlayingCoverBlurDarkenChange = onNowPlayingCoverBlurDarkenChange,
                                lyricBlurEnabled = lyricBlurEnabled,
                                onLyricBlurEnabledChange = onLyricBlurEnabledChange,
                                lyricBlurAmount = lyricBlurAmount,
                                onLyricBlurAmountChange = onLyricBlurAmountChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.Lyrics -> {
                    for (cardIndex in 0..4) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsLyricsSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                settingsRepository = AppContainer.settingsRepo,
                                scope = scope,
                                floatingLyricsPreferences = floatingLyricsPreferences,
                                onFloatingLyricsPreferencesChange = onFloatingLyricsPreferencesChange,
                                lyricsAppearanceContent = {
                                    SettingsLyricsAppearanceContent(
                                        autoSettingsRepository = autoSettingsRepository,
                                        scope = scope,
                                        lyricFontScales = lyricFontScales,
                                        onLyricFontScaleChange = onLyricFontScaleChange,
                                        highlightTargetId = settingsHighlightTargetId,
                                        highlightPulse = settingsHighlightPulse,
                                        onHighlightFinished = onSettingsHighlightFinished
                                    )
                                },
                                cloudMusicLyricDefaultOffsetMs = cloudMusicLyricDefaultOffsetMs,
                                onCloudMusicLyricDefaultOffsetMsChange =
                                    onCloudMusicLyricDefaultOffsetMsChange,
                                qqMusicLyricDefaultOffsetMs = qqMusicLyricDefaultOffsetMs,
                                onQqMusicLyricDefaultOffsetMsChange =
                                    onQqMusicLyricDefaultOffsetMsChange,
                                kugouLyricDefaultOffsetMs = kugouLyricDefaultOffsetMs,
                                onKugouLyricDefaultOffsetMsChange =
                                    onKugouLyricDefaultOffsetMsChange,
                                lrclibLyricDefaultOffsetMs = lrclibLyricDefaultOffsetMs,
                                onLrclibLyricDefaultOffsetMsChange =
                                    onLrclibLyricDefaultOffsetMsChange,
                                amllTtmlLyricDefaultOffsetMs = amllTtmlLyricDefaultOffsetMs,
                                onAmllTtmlLyricDefaultOffsetMsChange =
                                    onAmllTtmlLyricDefaultOffsetMsChange,
                                onResetAllLyricDefaultOffsets = onResetAllLyricDefaultOffsets,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.Network -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(AutoSettingsKeys.BYPASS_PROXY),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.AltRoute,
                                    contentDescription = stringResource(R.string.settings_bypass_proxy),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(checked = bypassProxy, onCheckedChange = onBypassProxyChange)
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = { onBypassProxyChange(!bypassProxy) }
                        )
                    }
                }

                SettingsPage.Playback -> {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsPlaybackSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                playbackFadeIn = playbackFadeIn,
                                onPlaybackFadeInChange = onPlaybackFadeInChange,
                                playbackCrossfadeNext = playbackCrossfadeNext,
                                onPlaybackCrossfadeNextChange = onPlaybackCrossfadeNextChange,
                                sleepTimerFinishCurrentOnExpiry = sleepTimerFinishCurrentOnExpiry,
                                onSleepTimerFinishCurrentOnExpiryChange =
                                    onSleepTimerFinishCurrentOnExpiryChange,
                                playbackFadeInDurationMs = playbackFadeInDurationMs,
                                onPlaybackFadeInDurationMsChange = onPlaybackFadeInDurationMsChange,
                                playbackFadeOutDurationMs = playbackFadeOutDurationMs,
                                onPlaybackFadeOutDurationMsChange = onPlaybackFadeOutDurationMsChange,
                                playbackCrossfadeInDurationMs = playbackCrossfadeInDurationMs,
                                onPlaybackCrossfadeInDurationMsChange =
                                    onPlaybackCrossfadeInDurationMsChange,
                                playbackCrossfadeOutDurationMs = playbackCrossfadeOutDurationMs,
                                onPlaybackCrossfadeOutDurationMsChange =
                                    onPlaybackCrossfadeOutDurationMsChange,
                                playbackVolumeNormalizationEnabled = playbackVolumeNormalizationEnabled,
                                onPlaybackVolumeNormalizationEnabledChange =
                                    onPlaybackVolumeNormalizationEnabledChange,
                                playbackHighResolutionOutputEnabled =
                                    playbackHighResolutionOutputEnabled,
                                onPlaybackHighResolutionOutputEnabledChange =
                                    onPlaybackHighResolutionOutputEnabledChange,
                                playbackVolumeBalance = playbackVolumeBalance,
                                onPlaybackVolumeBalanceChange = onPlaybackVolumeBalanceChange,
                                keepLastPlaybackProgress = keepLastPlaybackProgress,
                                onKeepLastPlaybackProgressChange = onKeepLastPlaybackProgressChange,
                                rememberLongFormPlaybackProgress = rememberLongFormPlaybackProgress,
                                onRememberLongFormPlaybackProgressChange =
                                    onRememberLongFormPlaybackProgressChange,
                                keepPlaybackModeState = keepPlaybackModeState,
                                onKeepPlaybackModeStateChange = onKeepPlaybackModeStateChange,
                                stopOnBluetoothDisconnect = stopOnBluetoothDisconnect,
                                onStopOnBluetoothDisconnectChange = onStopOnBluetoothDisconnectChange,
                                usbExclusivePlayback = usbExclusivePlayback,
                                onUsbExclusiveSettingsClick = {
                                    activeSettingsPage = SettingsPage.UsbExclusive
                                },
                                allowMixedPlayback = allowMixedPlayback,
                                onAllowMixedPlaybackChange = onAllowMixedPlaybackChange,
                                preemptAudioFocus = preemptAudioFocus,
                                onPreemptAudioFocusChange = onPreemptAudioFocusChange,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.UsbExclusive -> {
                    item(key = "${selectedPage.name}:content") {
                        UsbExclusiveSettingsSection(
                            usbExclusivePlayback = usbExclusivePlayback,
                            onUsbExclusivePlaybackChange = onUsbExclusivePlaybackChange,
                            preferences = usbExclusivePreferences,
                            onDeviceKeyChange = { deviceKey ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveDeviceKey(deviceKey)
                                }
                            },
                            onSampleRateModeChange = { mode ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveSampleRateMode(mode)
                                }
                            },
                            onBitDepthModeChange = { mode ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBitDepthMode(mode)
                                }
                            },
                            onBitPerfectChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBitPerfect(enabled)
                                }
                            },
                            onBufferProfileChange = { profile ->
                                scope.launch {
                                    AppContainer.settingsRepo.setUsbExclusiveBufferProfile(profile)
                                }
                            },
                            onUnsupportedFormatPolicyChange = { policy ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveUnsupportedFormatPolicy(policy)
                                }
                            },
                            onSampleRateCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveSampleRateCompatibility(enabled)
                                }
                            },
                            onBitDepthCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveBitDepthCompatibility(enabled)
                                }
                            },
                            onChannelCompatibilityChange = { enabled ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveChannelCompatibility(enabled)
                                }
                            },
                            onForegroundBufferMsChange = { bufferMs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveForegroundBufferMs(bufferMs)
                                }
                            },
                            onBackgroundBufferMsChange = { bufferMs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveBackgroundBufferMs(bufferMs)
                                }
                            },
                            onVolumeRiskThresholdDbfsChange = { thresholdDbfs ->
                                scope.launch {
                                    AppContainer.settingsRepo
                                        .setUsbExclusiveVolumeRiskThresholdDbfs(thresholdDbfs)
                                }
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                SettingsPage.PlaybackSource -> {
                    miuixSettingsSectionCardItem(key = "${selectedPage.name}:content") {
                        YouTubePlaybackSourceSetting(
                            repository = AppContainer.settingsRepo,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(
                                AutoSettingsKeys.NETEASE_LOCAL_SOURCE_FALLBACK
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Outlined.LibraryMusic,
                                    contentDescription = stringResource(
                                        R.string.settings_netease_local_source_fallback
                                    ),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = neteaseLocalSourceFallback,
                                    onCheckedChange = onNeteaseLocalSourceFallbackChange
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = {
                                onNeteaseLocalSourceFallbackChange(!neteaseLocalSourceFallback)
                            }
                        )
                        AutoSettingsListItem(
                            setting = AutoSettingsMetadata.requireSetting(
                                AutoSettingsKeys.NETEASE_AUTO_SOURCE_SWITCH
                            ),
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bilibili),
                                    contentDescription = stringResource(
                                        R.string.settings_netease_auto_source_switch
                                    ),
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                MiuixSettingsSwitch(
                                    checked = neteaseAutoSourceSwitch,
                                    onCheckedChange = onNeteaseAutoSourceSwitchChange
                                )
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            onClick = {
                                onNeteaseAutoSourceSwitchChange(!neteaseAutoSourceSwitch)
                            }
                        )
                    }
                }

                SettingsPage.AudioQuality -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsAudioQualitySection(
                            expanded = true,
                            arrowRotation = 0f,
                            onExpandedChange = {},
                            showHeader = false,
                            qualityLabel = qualityLabel,
                            preferredQuality = preferredQuality,
                            onQualityChange = onQualityChange,
                            youtubeQualityLabel = youtubeQualityLabel,
                            youtubePreferredQuality = youtubePreferredQuality,
                            onYouTubeQualityChange = onYouTubeQualityChange,
                            biliQualityLabel = biliQualityLabel,
                            biliPreferredQuality = biliPreferredQuality,
                            onBiliQualityChange = onBiliQualityChange,
                            mobileDataFollowDefaultAudioQuality = mobileDataFollowDefaultAudioQuality,
                            onMobileDataFollowDefaultAudioQualityChange =
                                onMobileDataFollowDefaultAudioQualityChange,
                            mobileDataNeteaseQualityLabel = mobileDataNeteaseQualityLabel,
                            mobileDataNeteaseAudioQuality = normalizedMobileDataNeteaseAudioQuality,
                            onMobileDataNeteaseAudioQualityChange =
                                onMobileDataNeteaseAudioQualityChange,
                            mobileDataYouTubeQualityLabel = mobileDataYouTubeQualityLabel,
                            mobileDataYouTubeAudioQuality = normalizedMobileDataYouTubeAudioQuality,
                            onMobileDataYouTubeAudioQualityChange =
                                onMobileDataYouTubeAudioQualityChange,
                            mobileDataBiliQualityLabel = mobileDataBiliQualityLabel,
                            mobileDataBiliAudioQuality = normalizedMobileDataBiliAudioQuality,
                            onMobileDataBiliAudioQualityChange = onMobileDataBiliAudioQualityChange,
                            showQualityDialog = showQualityDialog,
                            onShowQualityDialogChange = { showQualityDialog = it },
                            showYouTubeQualityDialog = showYouTubeQualityDialog,
                            onShowYouTubeQualityDialogChange = { showYouTubeQualityDialog = it },
                            showBiliQualityDialog = showBiliQualityDialog,
                            onShowBiliQualityDialogChange = { showBiliQualityDialog = it },
                            showMobileDataNeteaseQualityDialog = showMobileDataNeteaseQualityDialog,
                            onShowMobileDataNeteaseQualityDialogChange = {
                                showMobileDataNeteaseQualityDialog = it
                            },
                            showMobileDataYouTubeQualityDialog = showMobileDataYouTubeQualityDialog,
                            onShowMobileDataYouTubeQualityDialogChange = {
                                showMobileDataYouTubeQualityDialog = it
                            },
                            showMobileDataBiliQualityDialog = showMobileDataBiliQualityDialog,
                            onShowMobileDataBiliQualityDialogChange = {
                                showMobileDataBiliQualityDialog = it
                            },
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                }

                SettingsPage.Storage -> {
                    for (cardIndex in 0..3) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsStorageCacheSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                currentDownloadDirectorySummary = downloadDirectorySettings.currentSummary,
                                isCustomDownloadDirectory = !downloadDirectoryUri.isNullOrBlank(),
                                downloadDirectoryPermissionLost = downloadDirectorySettings.permissionLost,
                                downloadDirectoryChangeEnabled = downloadDirectorySettings.changeEnabled,
                                onPickDownloadDirectory = downloadDirectorySettings.onPickRequested,
                                onResetDownloadDirectory = downloadDirectorySettings.onResetRequested,
                                downloadFileNameTemplate = downloadFileNameTemplate,
                                onDownloadFileNameTemplateChange = onDownloadFileNameTemplateChange,
                                maxCacheSizeBytes = maxCacheSizeBytes,
                                onMaxCacheSizeBytesChange = onMaxCacheSizeBytesChange,
                                onOpenStorageDetails = {
                                    activeSettingsPage = SettingsPage.StorageCacheDetails
                                    refreshStorageDetails()
                                },
                                storageDetails = storageDetails,
                                showClearCacheDialog = showClearCacheDialog,
                                onShowClearCacheDialogChange = { showClearCacheDialog = it },
                                clearAudioCache = clearAudioCache,
                                onClearAudioCacheChange = { clearAudioCache = it },
                                clearImageCache = clearImageCache,
                                onClearImageCacheChange = { clearImageCache = it },
                                clearDownloadStagingCache = clearDownloadStagingCache,
                                onClearDownloadStagingCacheChange = { clearDownloadStagingCache = it },
                                clearSharedMediaCache = clearSharedMediaCache,
                                onClearSharedMediaCacheChange = { clearSharedMediaCache = it },
                                clearLyricsCache = clearLyricsCache,
                                onClearLyricsCacheChange = { clearLyricsCache = it },
                                clearNeteasePlaylistCache = clearNeteasePlaylistCache,
                                onClearNeteasePlaylistCacheChange = { clearNeteasePlaylistCache = it },
                                clearBiliFavoriteCache = clearBiliFavoriteCache,
                                onClearBiliFavoriteCacheChange = { clearBiliFavoriteCache = it },
                                clearBiliArchiveCache = clearBiliArchiveCache,
                                onClearBiliArchiveCacheChange = { clearBiliArchiveCache = it },
                                clearYoutubePlaylistCache = clearYoutubePlaylistCache,
                                onClearYoutubePlaylistCacheChange = { clearYoutubePlaylistCache = it },
                                clearLogFiles = clearLogFiles,
                                onClearLogFilesChange = { clearLogFiles = it },
                                clearCrashLogs = clearCrashLogs,
                                onClearCrashLogsChange = { clearCrashLogs = it },
                                downloadStagingClearEnabled =
                                    !downloadDirectorySettings.hasActiveDownloadOperations,
                                onClearCacheClick = onClearCacheClick,
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.StorageCacheDetails -> {
                    item(key = "${selectedPage.name}:content") {
                        StorageCacheDetailsContent(
                            storageDetails = storageDetails,
                            isScanning = storageDetailsLoading,
                            onRefresh = ::refreshStorageDetails,
                            onClearCache = {
                                activeSettingsPage = SettingsPage.Storage
                                showClearCacheDialog = true
                            },
                            onOpenSystemSettings = {
                                runCatching {
                                    val intent = Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        "package:${context.packageName}".toUri()
                                    )
                                    context.startActivity(intent)
                                }.onFailure {
                                    showSettingsMessage(
                                        composeResources.getString(
                                            R.string.storage_open_system_settings_failed
                                        )
                                    )
                                }
                            }
                        )
                    }
                }

                SettingsPage.Downloads -> {
                    item(key = "${selectedPage.name}:quality_follow_playback") {
                        SettingsDownloadQualityFollowPlaybackCard(
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished,
                            modifier = Modifier.animateItem()
                        )
                    }
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsDownloadSection(
                            expanded = true,
                            arrowRotation = 0f,
                            onExpandedChange = {},
                            showHeader = false,
                            onNavigateToDownloadManager = onNavigateToDownloadManager,
                            highlightTargetId = settingsHighlightTargetId,
                            highlightPulse = settingsHighlightPulse,
                            onHighlightFinished = onSettingsHighlightFinished
                        )
                    }
                }

                SettingsPage.TrafficManagement -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsTrafficManagementSection()
                    }
                }

                SettingsPage.Backup -> {
                    for (cardIndex in 0..4) {
                        item(key = "${selectedPage.name}:card:$cardIndex") {
                            SettingsBackupRestoreSection(
                                expanded = true,
                                arrowRotation = 0f,
                                onExpandedChange = {},
                                showHeader = false,
                                currentPlaylistCount = localPlaylistCount,
                                backupRestoreUiState = backupRestoreUiState,
                                configTransferUiState = configTransferUiState,
                                onExportClick = {
                                    if (!backupRestoreUiState.isExporting) {
                                        backupRestoreVm.initialize(context)
                                        exportPlaylistLauncher.launch(
                                            backupRestoreVm.generateBackupFileName()
                                        )
                                    }
                                },
                                onImportClick = {
                                    if (!backupRestoreUiState.isImporting) {
                                        importPlaylistLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                onExportConfigClick = {
                                    if (!configTransferUiState.isExporting) {
                                        configTransferVm.initialize(context)
                                        exportConfigLauncher.launch(
                                            configTransferVm.generateConfigFileName()
                                        )
                                    }
                                },
                                onImportConfigClick = {
                                    if (!configTransferUiState.isImporting) {
                                        importConfigLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                onClearExportStatus = backupRestoreVm::clearExportStatus,
                                onClearImportStatus = backupRestoreVm::clearImportStatus,
                                onClearConfigExportStatus = configTransferVm::clearExportStatus,
                                onClearConfigImportStatus = configTransferVm::clearImportStatus,
                                autoSettingsRepository = autoSettingsRepository,
                                scope = scope,
                                showGitHubConfigDialog = showGitHubConfigDialog,
                                showWebDavConfigDialog = showWebDavConfigDialog,
                                onOpenGitHubConfig = { showGitHubConfigDialog = true },
                                onOpenClearGitHubConfig = { showClearGitHubConfigDialog = true },
                                onOpenWebDavConfig = { showWebDavConfigDialog = true },
                                onOpenClearWebDavConfig = { showClearWebDavConfigDialog = true },
                                cardIndex = cardIndex,
                                highlightTargetId = settingsHighlightTargetId,
                                highlightPulse = settingsHighlightPulse,
                                onHighlightFinished = onSettingsHighlightFinished
                            )
                        }
                    }
                }

                SettingsPage.ListenTogether -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsListenTogetherSection(
                            controller = listenTogetherSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Transparent)
                        )
                    }
                }

                SettingsPage.About -> {
                    miuixSettingsSectionCardItem("${selectedPage.name}:content") {
                        SettingsAboutContent(
                            devModeEnabled = devModeEnabled,
                            onVersionClick = {
                                if (!devModeEnabled) {
                                    versionTapCount++
                                    if (versionTapCount >= 7) {
                                        onDevModeChange(true)
                                        inlineMsg = composeResources.getString(R.string.debug_mode_opened)
                                        versionTapCount = 0
                                    }
                                } else {
                                    inlineMsg = composeResources.getString(R.string.debug_mode_enabled)
                                }
                            },
                            onCopyValue = { value ->
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText("settings_build_value", value)
                                    )
                                val copiedMessage = composeResources.getString(R.string.toast_copied)
                                inlineMsg = copiedMessage
                                showSettingsMessage(copiedMessage)
                            },
                            onOpenGitHubRepo = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/cwuom/NeriPlayer".toUri()
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
                }
            }
        }
    }

    SettingsPageHost(
        activePage = activeSettingsPage,
        splitLayout = isSettingsSplitLayout,
        isolateAdvancedGlassTransitions = isolateAdvancedGlassTransitions,
        content = settingsPageContent
    )

    SettingsNeteaseAuthDialogs(
        showSheet = showNeteaseSheet,
        initialTab = neteaseSheetInitialTab,
        onDismissSheet = { showNeteaseSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        showConfirmDialog = showConfirmDialog,
        confirmPhoneMasked = confirmPhoneMasked,
        onDismissConfirmDialog = { showConfirmDialog = false },
        vm = neteaseVm,
        showSavedCookieDialog = showNeteaseSavedCookieDialog,
        onDismissSavedCookieDialog = { showNeteaseSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            inlineMsg = null
            neteaseSheetInitialTab = tab
            showNeteaseSheet = true
        },
        onLogout = {
            showNeteaseSavedCookieDialog = false
            neteaseVm.clearCookies()
        },
        onBrowserLogin = null
    )

    SettingsBiliAuthDialogs(
        showSheet = showBiliSheet,
        initialTab = biliSheetInitialTab,
        onDismissSheet = { showBiliSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        vm = biliVm,
        showSavedCookieDialog = showBiliSavedCookieDialog,
        onDismissSavedCookieDialog = { showBiliSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            inlineMsg = null
            biliSheetInitialTab = tab
            showBiliSheet = true
        },
        onLogout = {
            showBiliSavedCookieDialog = false
            biliVm.clearCookies()
        },
        onBrowserLogin = null
    )

    SettingsYouTubeAuthDialogs(
        showSheet = showYouTubeSheet,
        initialTab = youtubeSheetInitialTab,
        onDismissSheet = { showYouTubeSheet = false },
        inlineMsg = inlineMsg,
        onInlineMsgChange = { inlineMsg = it },
        vm = youtubeVm,
        showSavedCookieDialog = showYouTubeSavedCookieDialog,
        onDismissSavedCookieDialog = { showYouTubeSavedCookieDialog = false },
        onOpenSheetAtTab = { tab ->
            inlineMsg = null
            youtubeSheetInitialTab = tab
            showYouTubeSheet = true
        },
        onLogout = {
            showYouTubeSavedCookieDialog = false
            youtubeVm.clearAuth()
        }
    )
    loginSuccessTitle?.let { title ->
        LoginSuccessDialog(
            title = title,
            onDismiss = { loginSuccessTitle = null }
        )
    }
    SettingsPreferenceDialogs(
        showDefaultStartDestinationDialog = showDefaultStartDestinationDialog,
        onShowDefaultStartDestinationDialogChange = { showDefaultStartDestinationDialog = it },
        homeStartAvailable = homeStartAvailable,
        effectiveDefaultStartDestination = effectiveDefaultStartDestination,
        onDefaultStartDestinationChange = onDefaultStartDestinationChange,
        showColorPickerDialog = showColorPickerDialog,
        onShowColorPickerDialogChange = { showColorPickerDialog = it },
        seedColorHex = seedColorHex,
        themeColorPalette = themeColorPalette,
        onSeedColorChange = onSeedColorChange,
        onAddColorToPalette = onAddColorToPalette,
        onRemoveColorFromPalette = onRemoveColorFromPalette,
        showDpiDialog = showDpiDialog,
        onShowDpiDialogChange = { showDpiDialog = it },
        uiDensityScale = uiDensityScale,
        onUiDensityScaleChange = onUiDensityScaleChange
    )

    SettingsListenTogetherDialogs(listenTogetherSettings)

    SettingsGitHubDialogs(
        showGitHubConfigDialog = showGitHubConfigDialog,
        onShowGitHubConfigDialogChange = { showGitHubConfigDialog = it },
        showClearGitHubConfigDialog = showClearGitHubConfigDialog,
        onShowClearGitHubConfigDialogChange = { showClearGitHubConfigDialog = it }
    )

    SettingsWebDavDialogs(
        showWebDavConfigDialog = showWebDavConfigDialog,
        onShowWebDavConfigDialogChange = { showWebDavConfigDialog = it },
        showClearWebDavConfigDialog = showClearWebDavConfigDialog,
        onShowClearWebDavConfigDialogChange = { showClearWebDavConfigDialog = it }
    )

    DownloadDirectoryDialogs(controller = downloadDirectorySettings)

}

private class DownloadDirectorySettingsController(
    private val currentSummaryState: State<String>,
    private val permissionLostState: State<Boolean>,
    private val hasActiveDownloadOperationsState: State<Boolean>,
    private val showSwitchWarningState: State<Boolean>,
    private val pendingChangeState: State<PendingDownloadDirectoryChange?>,
    private val isPreparingState: State<Boolean>,
    private val isMigratingState: State<Boolean>,
    private val migrationProgressState: State<ManagedDownloadStorage.MigrationProgress?>,
    private val persistedMigrationProgressState: State<ManagedDownloadStorage.MigrationProgress?>,
    private val libraryProcessingState: State<ManagedLibraryProcessingState>,
    val onPickRequested: () -> Unit,
    val onResetRequested: () -> Unit,
    val onCancelPreparation: () -> Unit,
    val onDismissSwitchWarning: () -> Unit,
    val onConfirmSwitchWarning: () -> Unit,
    val onCancelPendingChange: (PendingDownloadDirectoryChange) -> Unit,
    val onSkipPendingChange: (PendingDownloadDirectoryChange) -> Unit,
    val onConfirmPendingChange: (PendingDownloadDirectoryChange) -> Unit
) {
    val onDismissPendingChange: (PendingDownloadDirectoryChange) -> Unit
        get() = onCancelPendingChange

    val currentSummary: String
        get() = currentSummaryState.value

    val permissionLost: Boolean
        get() = permissionLostState.value

    val changeEnabled: Boolean
        get() = !hasActiveDownloadOperationsState.value &&
            !isPreparingState.value &&
            !isMigratingState.value &&
            libraryProcessingState.value == ManagedLibraryProcessingState.Idle

    val hasActiveDownloadOperations: Boolean
        get() = hasActiveDownloadOperationsState.value

    val showSwitchWarning: Boolean
        get() = showSwitchWarningState.value

    val pendingChange: PendingDownloadDirectoryChange?
        get() = pendingChangeState.value

    val isPreparing: Boolean
        get() = isPreparingState.value

    val isMigrating: Boolean
        get() = isMigratingState.value

    val migrationProgress: ManagedDownloadStorage.MigrationProgress?
        get() = migrationProgressState.value ?: persistedMigrationProgressState.value

    val libraryProcessing: ManagedLibraryProcessingState
        get() = libraryProcessingState.value

    val processingPresentation: DownloadDirectoryProcessingPresentation
        get() = resolveDownloadDirectoryProcessingPresentation(
            isPreparing = isPreparing,
            isMigrating = isMigrating,
            processingState = libraryProcessing,
            migrationProgress = migrationProgress
        )
}


private fun directoryProbeRetryMessage(resources: android.content.res.Resources): String {
    return resources.getString(R.string.managed_library_processing_retry)
}

private fun directoryProbeFailureLog(error: Throwable?): String {
    return error?.let { "errorType=${it::class.java.simpleName}" } ?: "errorType=timeout"
}


@Composable
private fun rememberDownloadDirectorySettingsController(
    downloadDirectoryUri: String?,
    onDownloadDirectoryUriChange: (String?, String?) -> Unit,
    onInlineMessageChange: (String?) -> Unit,
    onShowMessage: (String) -> Unit
): DownloadDirectorySettingsController {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val defaultDirectorySummary = resources.getString(
        R.string.settings_download_directory_default_label
    )
    val showSwitchWarningState = remember { mutableStateOf(false) }
    val pendingChangeState = remember { mutableStateOf<PendingDownloadDirectoryChange?>(null) }
    val isPreparingState = remember { mutableStateOf(false) }
    val isMigratingState = remember { mutableStateOf(false) }
    val preparationJobState = remember { mutableStateOf<Job?>(null) }
    val permissionLostState = remember { mutableStateOf(false) }
    val activeMigrationWorkIdState = rememberSaveable { mutableStateOf<String?>(null) }
    val persistedMigrationProgressState = remember {
        mutableStateOf<ManagedDownloadStorage.MigrationProgress?>(null)
    }
    val migrationAutoResumeAttemptedState = rememberSaveable { mutableStateOf(false) }
    val migrationProgressState = ManagedDownloadStorage.migrationProgressFlow.collectAsState()
    val libraryProcessingState = ManagedLibraryProcessingCoordinator.state.collectAsState()
    val hasActiveDownloadOperationsState =
        GlobalDownloadManager.activeDownloadOperationsFlow.collectAsState()
    val currentSummaryState = remember(downloadDirectoryUri, defaultDirectorySummary) {
        mutableStateOf(defaultDirectorySummary)
    }

    LaunchedEffect(
        migrationProgressState.value,
        persistedMigrationProgressState.value,
        libraryProcessingState.value
    ) {
        val processingState = libraryProcessingState.value
        val progress = migrationProgressState.value ?: persistedMigrationProgressState.value
        val operationId = processingState.operationId
        if (
            operationId.isNullOrBlank() ||
            processingState.reason != ManagedLibraryProcessingReason.DIRECTORY_CHANGE ||
            progress == null
        ) {
            return@LaunchedEffect
        }
        ManagedLibraryProcessingCoordinator.updateProgress(
            context = context,
            operationId = operationId,
            processed = progress.processedFiles.coerceAtLeast(0),
            total = progress.totalFiles.coerceAtLeast(0)
        )
    }

    var showSwitchWarning by showSwitchWarningState
    var pendingChange by pendingChangeState
    var isPreparing by isPreparingState
    var isMigrating by isMigratingState
    var preparationJob by preparationJobState
    var permissionLost by permissionLostState
    var activeMigrationWorkId by activeMigrationWorkIdState
    var persistedMigrationProgress by persistedMigrationProgressState
    var migrationAutoResumeAttempted by migrationAutoResumeAttemptedState
    var currentSummary by currentSummaryState

    fun clearPersistedMigrationUi() {
        // 这里只清理设置页状态，保留 checkpoint 和 journal 供后台恢复
        persistedMigrationProgress = null
        isMigrating = false
        activeMigrationWorkId = null
        migrationAutoResumeAttempted = false
    }

    suspend fun applyPersistedMigrationSnapshot(
        snapshot: PersistedMigrationUiSnapshot,
        fallbackProgress: ManagedDownloadStorage.MigrationProgress? = null,
        autoResumeAttempted: Boolean
    ): PersistedMigrationSnapshotApplyResult {
        val shouldPreserveUi = snapshot.shouldPreserveUi
        if (!shouldPreserveUi) {
            // 终态请求不再保留旧进度，避免冷启动残留迁移弹窗
            clearPersistedMigrationUi()
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = false,
                attemptedAutoResume = false
            )
        }
        val restoredProgress = snapshot.progress
            ?: fallbackProgress
            ?: persistedMigrationProgress
        restoredProgress?.let { progress -> persistedMigrationProgress = progress }
        val activeWorkId = snapshot.activeWorkId
        val activeWorkState = snapshot.activeWorkState
        if (activeWorkId != null && activeWorkState != null && !activeWorkState.isFinished) {
            activeMigrationWorkId = activeWorkId
            isMigrating = true
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = true,
                attemptedAutoResume = false
            )
        }
        if (
            isMigrating ||
                persistedMigrationProgress != null ||
                snapshot.activeWorkId != null ||
                snapshot.requestAutoResume ||
                snapshot.journalPhase != null
        ) {
            isMigrating = true
        }
        if (!snapshot.shouldResume) {
            NPLogger.w(
                "ManagedDownloadMigrationSettings",
                "迁移 checkpoint 读取暂不可恢复，保留进度等待下次检查"
            )
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = true,
                attemptedAutoResume = false
            )
        }
        if (!shouldAttemptMigrationAutoResume(snapshot.shouldResume, autoResumeAttempted)) {
            NPLogger.w(
                "ManagedDownloadMigrationSettings",
                "迁移持久请求自动恢复预算已用尽，停止设置页轮询"
            )
            return PersistedMigrationSnapshotApplyResult(
                preservedUi = true,
                attemptedAutoResume = false
            )
        }
        val resumedWorkId = runCatching {
            ManagedDownloadMigrationWorker.resumePersistedRequestIfNeeded(context)
        }.onFailure { error ->
            NPLogger.w(
                "ManagedDownloadMigrationSettings",
                "设置页恢复持久迁移请求失败，保留进度: ${error.message}",
                error
            )
        }.getOrNull()
        if (resumedWorkId != null) {
            activeMigrationWorkId = resumedWorkId
        }
        return PersistedMigrationSnapshotApplyResult(
            preservedUi = true,
            attemptedAutoResume = true
        )
    }

    LaunchedEffect(downloadDirectoryUri) {
        permissionLost = resolveDownloadDirectoryPermissionLost(
            directoryUri = downloadDirectoryUri,
            isRootResolvable = { probeConfiguredDownloadRoot(context) }
        )
    }

    LaunchedEffect(Unit) {
        var consecutiveSnapshotReadFailures = 0
        var previousSnapshot: PersistedMigrationUiSnapshot? = null
        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { readPersistedMigrationUiSnapshot(context) }
                    .onFailure { error ->
                        NPLogger.w(
                            "ManagedDownloadMigrationSettings",
                            "读取迁移 WorkManager/checkpoint 快照失败，保留现有界面: " +
                                error.message,
                            error
                        )
                    }
                    .getOrNull()
            }
            if (snapshot == null) {
                consecutiveSnapshotReadFailures++
                if (!shouldRetryMigrationSnapshotRead(consecutiveSnapshotReadFailures)) {
                    clearPersistedMigrationUi()
                    break
                }
                delay(MIGRATION_CHECKPOINT_RETRY_DELAY_MS)
                continue
            }
            consecutiveSnapshotReadFailures = if (snapshot.checkpointReadFailed) {
                consecutiveSnapshotReadFailures + 1
            } else {
                0
            }
            if (!shouldRetryMigrationSnapshotRead(consecutiveSnapshotReadFailures)) {
                clearPersistedMigrationUi()
                break
            }
            val snapshotChanged = previousSnapshot == null || previousSnapshot != snapshot
            val applyResult = applyPersistedMigrationSnapshot(
                snapshot = snapshot,
                autoResumeAttempted = migrationAutoResumeAttempted
            )
            migrationAutoResumeAttempted =
                migrationAutoResumeAttempted || applyResult.attemptedAutoResume
            if (!applyResult.preservedUi) break
            if (
                snapshot.activeWorkId == null &&
                    activeMigrationWorkId == null &&
                    (snapshot.shouldResume || snapshot.checkpointReadFailed)
            ) {
                if (
                    shouldStopMigrationRecoveryAfterNoProgress(
                        shouldPreserveUi = snapshot.shouldPreserveUi,
                        needsRecovery = snapshot.shouldResume || snapshot.checkpointReadFailed,
                        snapshotChanged = snapshotChanged,
                        autoResumeAttempted = migrationAutoResumeAttempted
                    )
                ) {
                    clearPersistedMigrationUi()
                    break
                }
                previousSnapshot = snapshot
                delay(MIGRATION_CHECKPOINT_RETRY_DELAY_MS)
                continue
            }
            break
        }
    }

    LaunchedEffect(activeMigrationWorkId) {
        val workId = activeMigrationWorkId ?: return@LaunchedEffect
        var consecutiveSnapshotReadFailures = 0
        var previousSnapshot: PersistedMigrationUiSnapshot? = null
        while (true) {
            val workInfo = withContext(Dispatchers.IO) {
                runCatching {
                    WorkManager.getInstance(context)
                        .getWorkInfoById(java.util.UUID.fromString(workId))
                        .get()
                }.getOrNull()
            }
            if (workInfo == null || workInfo.state.isFinished) {
                val durableSnapshot = withContext(Dispatchers.IO) {
                    runCatching { readPersistedMigrationUiSnapshot(context) }
                        .onFailure { error ->
                            NPLogger.w(
                                "ManagedDownloadMigrationSettings",
                                "迁移任务结束后读取持久 checkpoint 失败: ${error.message}",
                                error
                            )
                        }
                        .getOrNull()
                }
                if (durableSnapshot == null) {
                    consecutiveSnapshotReadFailures++
                    if (!shouldRetryMigrationSnapshotRead(consecutiveSnapshotReadFailures)) {
                        clearPersistedMigrationUi()
                        break
                    }
                    delay(MIGRATION_CHECKPOINT_RETRY_DELAY_MS)
                    continue
                }
                consecutiveSnapshotReadFailures = if (durableSnapshot.checkpointReadFailed) {
                    consecutiveSnapshotReadFailures + 1
                } else {
                    0
                }
                if (!shouldRetryMigrationSnapshotRead(consecutiveSnapshotReadFailures)) {
                    clearPersistedMigrationUi()
                    break
                }
                if (durableSnapshot.shouldPreserveUi) {
                    val previousWorkId = activeMigrationWorkId
                    val snapshotChanged =
                        previousSnapshot == null || previousSnapshot != durableSnapshot
                    val applyResult = applyPersistedMigrationSnapshot(
                        snapshot = durableSnapshot,
                        fallbackProgress = workInfo?.let {
                            migrationProgressFromWorkData(it.progress)
                        },
                        autoResumeAttempted = migrationAutoResumeAttempted
                    )
                    migrationAutoResumeAttempted =
                        migrationAutoResumeAttempted || applyResult.attemptedAutoResume
                    if (!applyResult.preservedUi) break
                    if (
                        durableSnapshot.activeWorkId == null &&
                            activeMigrationWorkId == previousWorkId &&
                            (durableSnapshot.shouldResume ||
                                durableSnapshot.checkpointReadFailed)
                    ) {
                        if (
                            shouldStopMigrationRecoveryAfterNoProgress(
                                shouldPreserveUi = durableSnapshot.shouldPreserveUi,
                                needsRecovery = durableSnapshot.shouldResume ||
                                    durableSnapshot.checkpointReadFailed,
                                snapshotChanged = snapshotChanged,
                                autoResumeAttempted = migrationAutoResumeAttempted
                            )
                        ) {
                            clearPersistedMigrationUi()
                            break
                        }
                        previousSnapshot = durableSnapshot
                        delay(MIGRATION_CHECKPOINT_RETRY_DELAY_MS)
                        continue
                    }
                    break
                }
            }
            if (workInfo == null) {
                clearPersistedMigrationUi()
                onInlineMessageChange(
                    resources.getQuantityString(
                        R.plurals.settings_download_directory_migrate_failed,
                        1,
                        1
                    )
                )
                break
            }
            persistedMigrationProgress = migrationProgressFromWorkData(workInfo.progress)
            if (!workInfo.state.isFinished) {
                isMigrating = true
                delay(500L)
                continue
            }
            isMigrating = false
            if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                val movedFiles = workInfo.outputData
                    .getInt(ManagedDownloadMigrationWorker.KEY_MOVED_FILES, 0)
                val cleanupFailedFiles = workInfo.outputData
                    .getInt(ManagedDownloadMigrationWorker.KEY_CLEANUP_FAILED_FILES, 0)
                onInlineMessageChange(
                    if (cleanupFailedFiles > 0) {
                        resources.getQuantityString(
                            R.plurals.settings_download_directory_migrated_partial,
                            movedFiles,
                            movedFiles,
                            cleanupFailedFiles
                        )
                    } else {
                        resources.getQuantityString(
                            R.plurals.settings_download_directory_migrated,
                            movedFiles,
                            movedFiles
                        )
                    }
                )
            } else {
                val skippedFiles = workInfo.outputData
                    .getInt(ManagedDownloadMigrationWorker.KEY_SKIPPED_FILES, 0)
                    .coerceAtLeast(1)
                onInlineMessageChange(
                    resources.getQuantityString(
                        R.plurals.settings_download_directory_migrate_failed,
                        skippedFiles,
                        skippedFiles
                    )
                )
            }
            persistedMigrationProgress = null
            activeMigrationWorkId = null
            migrationAutoResumeAttempted = false
            break
        }
    }

    fun showPreparationError(error: Exception) {
        if (error is ManagedLibraryProcessingBusyException) {
            val message = resources.getString(
                R.string.managed_library_processing_subtitle
            )
            onInlineMessageChange(message)
            onShowMessage(message)
            return
        }
        val detail = error.message?.takeIf(String::isNotBlank)
            ?: error::class.java.simpleName
        val message = resources.getString(
            R.string.settings_download_directory_pick_failed,
            detail
        )
        onInlineMessageChange(message)
        onShowMessage(message)
    }

    fun guardDirectoryChange(
        targetUri: String? = null,
        releaseTargetPermissionOnBlock: Boolean = false,
        allowWhilePreparing: Boolean = false
    ): Boolean {
        val blockedByPreparation = isPreparing && !allowWhilePreparing
        val blockedByLibraryProcessing =
            libraryProcessingState.value != ManagedLibraryProcessingState.Idle
        if (blockedByPreparation || isMigrating || blockedByLibraryProcessing) {
            if (
                releaseTargetPermissionOnBlock &&
                !targetUri.isNullOrBlank() &&
                !ManagedDownloadStorage.areEquivalentDirectoryUris(downloadDirectoryUri, targetUri)
            ) {
                ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
            }
            if (blockedByLibraryProcessing) {
                val message = resources.getString(
                    R.string.managed_library_processing_subtitle
                )
                onInlineMessageChange(message)
                onShowMessage(message)
            }
            return true
        }
        if (!GlobalDownloadManager.hasActiveDownloadOperations()) {
            return false
        }
        if (
            releaseTargetPermissionOnBlock &&
            !targetUri.isNullOrBlank() &&
            !ManagedDownloadStorage.areEquivalentDirectoryUris(downloadDirectoryUri, targetUri)
        ) {
            ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
        }
        val message = resources.getString(
            R.string.settings_download_directory_change_blocked_active_download
        )
        onInlineMessageChange(message)
        onShowMessage(message)
        return true
    }

    suspend fun applyDirectoryChange(
        targetUri: String?,
        targetSummary: String,
        previousUri: String?,
        shouldReleasePreviousPermission: Boolean
    ) {
        // 扫描开始后由共享协调器统一持有进度状态
        isPreparing = false
        val operationId = ManagedLibraryProcessingCoordinator.tryBeginExclusive(
            context = context,
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        ) ?: throw ManagedLibraryProcessingBusyException(
            ManagedLibraryProcessingCoordinator.state.value.reason
        )
        try {
            val targetLabel = targetSummary.takeIf { !targetUri.isNullOrBlank() }
            ManagedDownloadStorage.updateConfiguredTreeUri(targetUri)
            ManagedDownloadStorage.updateCustomDirectoryLabel(targetLabel)
            onDownloadDirectoryUriChange(targetUri, targetLabel)
            permissionLost = false
            val outcome = GlobalDownloadManager.scanLocalFilesAwait(
                context = context,
                forceRefresh = true
            )
            if (outcome is ManagedLibraryRefreshOutcome.Published) {
                ManagedLibraryProcessingCoordinator.complete(context, operationId)
                if (shouldReleasePreviousPermission) {
                    ManagedDownloadStorage.releasePersistedDirectoryPermission(
                        context,
                        previousUri
                    )
                }
                onInlineMessageChange(
                    if (targetUri.isNullOrBlank()) {
                        resources.getString(R.string.settings_download_directory_reset_done)
                    } else {
                        resources.getString(R.string.settings_download_directory_selected)
                    }
                )
            } else {
                ManagedLibraryProcessingCoordinator.waitingForRetry(context, operationId)
                onInlineMessageChange(
                    resources.getString(R.string.managed_library_processing_retry)
                )
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                runCatching {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(context, operationId)
                }
            }
            throw error
        } catch (error: Exception) {
            runCatching {
                ManagedLibraryProcessingCoordinator.waitingForRetry(context, operationId)
            }
            throw error
        }
    }

    suspend fun prepareDirectoryChange(
        targetUri: String?,
        targetSummary: String,
        releaseTargetPermissionOnCancel: Boolean
    ): DownloadDirectoryPreparationResult {
        val preflightStartedAtNanos = System.nanoTime()
        if (guardDirectoryChange(allowWhilePreparing = true)) {
            return DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION
        }
        val previousUri = downloadDirectoryUri?.takeIf { it.isNotBlank() }
        val direction = when {
            targetUri.isNullOrBlank() -> "to_default"
            previousUri.isNullOrBlank() -> "from_default"
            else -> "between_custom_roots"
        }
        NPLogger.d(
            "DownloadDirectoryPreflight",
            "directory_preflight stage=start direction=$direction"
        )
        if (previousUri == targetUri) {
            onInlineMessageChange(
                if (targetUri.isNullOrBlank()) {
                    resources.getString(R.string.settings_download_directory_reset_done)
                } else {
                    resources.getString(R.string.settings_download_directory_selected)
                }
            )
            return DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
        }

        if (ManagedDownloadStorage.areEquivalentDirectoryUris(previousUri, targetUri)) {
            applyDirectoryChange(
                targetUri = targetUri,
                targetSummary = targetSummary,
                previousUri = previousUri,
                shouldReleasePreviousPermission = false
            )
            return DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
        }

        val decisionResult = runDownloadDirectoryPreflight {
            ManagedDownloadMigrationPolicy.resolveDirectoryChangeAfterProbes(
                fromDirectoryUri = previousUri,
                toDirectoryUri = targetUri,
                probeSourceHasManagedEntries = {
                    val startedAtNanos = System.nanoTime()
                    ManagedDownloadStorage.hasMigratableDownloads(context, previousUri).also { present ->
                        NPLogger.d(
                            "DownloadDirectoryPreflight",
                            "directory_preflight stage=source_presence status=complete " +
                                "present=$present " +
                                "elapsedMs=${elapsedMillisSince(startedAtNanos)}"
                        )
                    }
                },
                probeTargetHasManagedEntries = {
                    val startedAtNanos = System.nanoTime()
                    ManagedDownloadStorage.hasMigratableDownloads(context, targetUri).also { present ->
                        NPLogger.d(
                            "DownloadDirectoryPreflight",
                            "directory_preflight stage=target_presence status=complete " +
                                "present=$present " +
                                "elapsedMs=${elapsedMillisSince(startedAtNanos)}"
                        )
                    }
                },
                probeTargetNonEmpty = {
                    val startedAtNanos = System.nanoTime()
                    ManagedDownloadStorage.hasActualDirectoryEntries(context, targetUri)
                        .also { nonEmpty ->
                            NPLogger.d(
                                "DownloadDirectoryPreflight",
                                "directory_preflight stage=target_non_empty status=complete " +
                                    "nonEmpty=$nonEmpty " +
                                    "elapsedMs=${elapsedMillisSince(startedAtNanos)}"
                            )
                        }
                }
            )
        }
        if (decisionResult == null || decisionResult.isFailure) {
            val error = decisionResult?.exceptionOrNull()
            NPLogger.w(
                "DownloadDirectoryPreflight",
                "directory_preflight stage=decision status=retryable " +
                    "timeoutMs=$DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS " +
                    directoryProbeFailureLog(error)
            )
            val message = directoryProbeRetryMessage(resources)
            onInlineMessageChange(message)
            onShowMessage(message)
            return DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION
        }
        val directoryChangeDecision = decisionResult.getOrThrow()
        NPLogger.d(
            "DownloadDirectoryPreflight",
            "directory_preflight stage=decision status=complete direction=$direction " +
                "decision=$directoryChangeDecision " +
                "elapsedMs=${elapsedMillisSince(preflightStartedAtNanos)}"
        )
        when (directoryChangeDecision) {
            ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY -> {
                applyDirectoryChange(
                    targetUri = targetUri,
                    targetSummary = targetSummary,
                    previousUri = previousUri,
                    shouldReleasePreviousPermission = !previousUri.isNullOrBlank()
                )
            }
            ManagedDownloadDirectoryChangeDecision.REATTACH_EXISTING_TARGET -> {
                applyDirectoryChange(
                    targetUri = targetUri,
                    targetSummary = targetSummary,
                    previousUri = previousUri,
                    shouldReleasePreviousPermission = false
                )
            }
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION,
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET -> {
                pendingChange = PendingDownloadDirectoryChange(
                    previousUri = previousUri,
                    targetUri = targetUri,
                    targetSummary = targetSummary,
                    releaseTargetPermissionOnCancel = releaseTargetPermissionOnCancel,
                    targetNonEmpty = directoryChangeDecision ==
                        ManagedDownloadDirectoryChangeDecision
                            .CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET
                )
            }
        }
        return DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
    }

    val directoryContract = remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent {
                return super.createIntent(context, input).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
        }
    }
    val directoryLauncher = rememberLauncherForActivityResult(
        contract = directoryContract
    ) { uri ->
        if (uri == null || guardDirectoryChange()) {
            return@rememberLauncherForActivityResult
        }
        isPreparing = true
        val targetUri = uri.toString()
        preparationJob = scope.launch {
            var permissionWasPersisted = false
            var keepPersistedPermission = false
            try {
                withContext(Dispatchers.IO) {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                    permissionWasPersisted = true
                }
                permissionLost = false
                val targetSummary = runDownloadDirectoryPreflight {
                    withContext(Dispatchers.IO) {
                        ManagedDownloadStorage.describeConfiguredDirectory(context, targetUri)
                    }
                }?.getOrElse { error -> throw error }
                    ?: throw directoryProbeTimeoutFailure(
                        DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS
                    )
                keepPersistedPermission = prepareDirectoryChange(
                    targetUri = targetUri,
                    targetSummary = targetSummary,
                    releaseTargetPermissionOnCancel = true
                ) == DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showPreparationError(error)
            } finally {
                if (permissionWasPersisted && !keepPersistedPermission) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
                    }
                }
                isPreparing = false
                preparationJob = null
            }
        }
    }

    LaunchedEffect(downloadDirectoryUri, defaultDirectorySummary) {
        currentSummary = if (downloadDirectoryUri.isNullOrBlank()) {
            defaultDirectorySummary
        } else {
            runDownloadDirectoryPreflight {
                withContext(Dispatchers.IO) {
                    ManagedDownloadStorage.describeConfiguredDirectory(context, downloadDirectoryUri)
                }
            }?.getOrNull() ?: currentSummary
        }
    }

    fun applyPendingChangeWithoutMigration(change: PendingDownloadDirectoryChange) {
        if (
            guardDirectoryChange(
                targetUri = change.targetUri,
                releaseTargetPermissionOnBlock = change.releaseTargetPermissionOnCancel
            )
        ) {
            pendingChange = null
            return
        }
        pendingChange = null
        isPreparing = true
        preparationJob = scope.launch {
            try {
                applyDirectoryChange(
                    targetUri = change.targetUri,
                    targetSummary = change.targetSummary,
                    previousUri = change.previousUri,
                    shouldReleasePreviousPermission =
                        change.shouldReleasePreviousPermission
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showPreparationError(error)
            } finally {
                isPreparing = false
                preparationJob = null
            }
        }
    }

    return DownloadDirectorySettingsController(
        currentSummaryState = currentSummaryState,
        permissionLostState = permissionLostState,
        hasActiveDownloadOperationsState = hasActiveDownloadOperationsState,
        showSwitchWarningState = showSwitchWarningState,
        pendingChangeState = pendingChangeState,
        isPreparingState = isPreparingState,
        isMigratingState = isMigratingState,
        migrationProgressState = migrationProgressState,
        persistedMigrationProgressState = persistedMigrationProgressState,
        libraryProcessingState = libraryProcessingState,
        onPickRequested = {
            if (!guardDirectoryChange()) {
                showSwitchWarning = true
            }
        },
        onResetRequested = {
            if (!guardDirectoryChange()) {
                isPreparing = true
                preparationJob = scope.launch {
                    try {
                        val availabilityStartedAtNanos = System.nanoTime()
                        val availability = withContext(Dispatchers.IO) {
                            resolveDownloadDirectoryAvailability(
                                directoryUri = downloadDirectoryUri,
                                isRootResolvable = {
                                    probeConfiguredDownloadRoot(context)
                                }
                            )
                        }
                        NPLogger.d(
                            "DownloadDirectoryPreflight",
                            "directory_preflight stage=source_availability status=complete " +
                                "availability=${availability::class.java.simpleName} " +
                                "elapsedMs=${elapsedMillisSince(availabilityStartedAtNanos)}"
                        )
                        when (availability) {
                            DownloadDirectoryAvailability.Available -> {
                                prepareDirectoryChange(
                                    targetUri = null,
                                    targetSummary = defaultDirectorySummary,
                                    releaseTargetPermissionOnCancel = false
                                )
                            }
                            DownloadDirectoryAvailability.Unavailable -> {
                                applyDirectoryChange(
                                    targetUri = null,
                                    targetSummary = defaultDirectorySummary,
                                    previousUri = downloadDirectoryUri,
                                    shouldReleasePreviousPermission = true
                                )
                            }
                            is DownloadDirectoryAvailability.ProviderFailure -> {
                                NPLogger.w(
                                    "DownloadDirectoryPreflight",
                                    "directory_preflight stage=source_availability " +
                                        "status=retryable " +
                                        directoryProbeFailureLog(availability.error.cause)
                                )
                                val message = directoryProbeRetryMessage(resources)
                                onInlineMessageChange(message)
                                onShowMessage(message)
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        showPreparationError(error)
                    } finally {
                        isPreparing = false
                        preparationJob = null
                    }
                }
            }
        },
        onCancelPreparation = {
            preparationJob?.cancel()
        },
        onDismissSwitchWarning = { showSwitchWarning = false },
        onConfirmSwitchWarning = {
            if (guardDirectoryChange()) {
                showSwitchWarning = false
            } else {
                showSwitchWarning = false
                directoryLauncher.launch(null)
            }
        },
        onCancelPendingChange = { change ->
            if (change.targetUri.isNullOrBlank()) {
                pendingChange = null
            } else {
                pendingChange = null
                if (change.releaseTargetPermissionOnCancel) {
                    ManagedDownloadStorage.releasePersistedDirectoryPermission(
                        context,
                        change.targetUri
                    )
                }
            }
        },
        onSkipPendingChange = { change ->
            applyPendingChangeWithoutMigration(change)
        },
        onConfirmPendingChange = { change ->
            if (
                guardDirectoryChange(
                    targetUri = change.targetUri,
                    releaseTargetPermissionOnBlock = change.releaseTargetPermissionOnCancel
                )
            ) {
                pendingChange = null
            } else {
                pendingChange = null
                migrationAutoResumeAttempted = false
                isMigrating = true
                scope.launch {
                    runCatching {
                        ManagedDownloadMigrationWorker.enqueueOrGetActiveWorkId(
                            context = context,
                            fromDirectoryUri = change.previousUri,
                            toDirectoryUri = change.targetUri,
                            targetLabel = change.targetSummary,
                            releasePreviousPermission = change.shouldReleasePreviousPermission
                        )
                    }.onSuccess { workId ->
                        activeMigrationWorkId = workId
                    }.onFailure { error ->
                        isMigrating = false
                        onInlineMessageChange(
                            resources.getString(
                                R.string.settings_download_directory_pick_failed,
                                error.message ?: ""
                            )
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun ManagedLibraryProcessingDetailsCard(
    state: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
) {
    val title = when (state.reason) {
        ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE ->
            stringResource(R.string.managed_library_processing_upgrade_title)
        ManagedLibraryProcessingReason.DIRECTORY_CHANGE ->
            stringResource(R.string.managed_library_processing_directory_title)
        null -> stringResource(R.string.settings_download_directory_migrating)
    }
    val waitingForRetry = state is ManagedLibraryProcessingState.WaitingForRetry
    val stageText = when (migrationProgress?.stage) {
        ManagedDownloadStorage.MigrationStage.PREPARING ->
            stringResource(R.string.settings_download_directory_migrating_stage_preparing)
        ManagedDownloadStorage.MigrationStage.COPYING ->
            stringResource(R.string.settings_download_directory_migrating_stage_copying)
        ManagedDownloadStorage.MigrationStage.REWRITING_METADATA ->
            stringResource(R.string.settings_download_directory_migrating_stage_rewriting)
        ManagedDownloadStorage.MigrationStage.VERIFYING ->
            stringResource(R.string.settings_download_directory_migrating_stage_verifying)
        ManagedDownloadStorage.MigrationStage.CLEANING_UP ->
            stringResource(R.string.settings_download_directory_migrating_stage_cleanup)
        ManagedDownloadStorage.MigrationStage.FINALIZING ->
            stringResource(R.string.settings_download_directory_migrating)
        null -> when (state.phase) {
            ManagedLibraryProcessingPhase.UPGRADING_DATABASE ->
                stringResource(R.string.managed_library_processing_upgrade_title)
            ManagedLibraryProcessingPhase.REBUILDING_INDEX ->
                stringResource(R.string.settings_download_directory_preparing)
            ManagedLibraryProcessingPhase.WAITING_FOR_RETRY ->
                stringResource(R.string.managed_library_processing_retry)
            null -> stringResource(R.string.settings_download_directory_migrating_desc)
        }
    }
    val progressCount = migrationProgress?.let { progress ->
        when {
            progress.stageTotal > 0 -> progress.stageProcessed to progress.stageTotal
            progress.totalFiles > 0 -> progress.processedFiles to progress.totalFiles
            else -> null
        }
    }
    val processed = progressCount?.first?.coerceAtLeast(0)
        ?: state.processed?.coerceAtLeast(0)
    val total = progressCount?.second?.takeIf { it > 0 }
        ?: state.total?.takeIf { it > 0 }
    val progressFraction = migrationProgress?.fraction?.coerceIn(0f, 1f)
        ?: if (processed != null && total != null) {
            (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
    val currentFileSummary = migrationProgress?.currentFileName
        ?.takeIf(String::isNotBlank)
        ?.let { fileName ->
            stringResource(R.string.settings_download_directory_migrating_current, fileName)
        }

    MiuixSettingsSectionCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (waitingForRetry) {
                    stringResource(R.string.managed_library_processing_retry)
                } else {
                    stringResource(R.string.settings_download_directory_migrating_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stageText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (processed != null && total != null) {
                Text(
                    text = stringResource(
                        R.string.managed_library_processing_progress,
                        processed,
                        total
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!waitingForRetry) {
                if (progressFraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                currentFileSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadDirectoryDialogs(
    controller: DownloadDirectorySettingsController
) {
    val context = LocalContext.current
    val resources = LocalResources.current

    if (controller.showSwitchWarning) {
        MiuixSettingsDialog(
            onDismissRequest = controller.onDismissSwitchWarning,
            title = {
                Text(stringResource(R.string.settings_download_directory_switch_warning_title))
            },
            text = {
                Text(stringResource(R.string.settings_download_directory_switch_warning_message))
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    enabled = controller.changeEnabled,
                    onClick = controller.onConfirmSwitchWarning
                ) {
                    Text(stringResource(R.string.settings_download_directory_switch_warning_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = controller.onDismissSwitchWarning) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    controller.pendingChange?.let { change ->
        MiuixSettingsDialog(
            onDismissRequest = { controller.onCancelPendingChange(change) },
            title = { Text(stringResource(R.string.settings_download_directory_migrate_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(
                            R.string.settings_download_directory_migrate_message,
                            change.targetSummary
                        )
                    )
                    if (change.shouldShowTargetConflictWarning) {
                        Text(
                            text = stringResource(
                                R.string.settings_download_directory_migrate_conflict_warning
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    enabled = controller.changeEnabled,
                    onClick = { controller.onConfirmPendingChange(change) }
                ) {
                    Text(stringResource(R.string.settings_download_directory_migrate_confirm))
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MiuixSettingsTextButton(
                        onClick = { controller.onCancelPendingChange(change) }
                    ) {
                        Text(stringResource(R.string.settings_download_directory_migrate_cancel))
                    }
                    MiuixSettingsTextButton(onClick = { controller.onSkipPendingChange(change) }) {
                        Text(stringResource(R.string.settings_download_directory_migrate_skip))
                    }
                }
            }
        )
    }

    val processingPresentation = controller.processingPresentation

    if (processingPresentation.showPreparation) {
        MiuixSettingsDialog(
            onDismissRequest = controller.onCancelPreparation,
            title = { Text(stringResource(R.string.settings_download_directory_preparing)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.settings_download_directory_preparing_desc))
                }
            },
            confirmButton = {},
            dismissButton = {
                MiuixSettingsTextButton(onClick = controller.onCancelPreparation) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (processingPresentation.showMigration) {
        val activeMigrationProgress = controller.migrationProgress
        val stageText = when (activeMigrationProgress?.stage) {
            ManagedDownloadStorage.MigrationStage.PREPARING ->
                stringResource(R.string.settings_download_directory_migrating_stage_preparing)
            ManagedDownloadStorage.MigrationStage.COPYING ->
                stringResource(R.string.settings_download_directory_migrating_stage_copying)
            ManagedDownloadStorage.MigrationStage.REWRITING_METADATA ->
                stringResource(R.string.settings_download_directory_migrating_stage_rewriting)
            ManagedDownloadStorage.MigrationStage.VERIFYING ->
                stringResource(R.string.settings_download_directory_migrating_stage_verifying)
            ManagedDownloadStorage.MigrationStage.CLEANING_UP ->
                stringResource(R.string.settings_download_directory_migrating_stage_cleanup)
            ManagedDownloadStorage.MigrationStage.FINALIZING ->
                stringResource(R.string.settings_download_directory_migrating)
            null -> stringResource(R.string.settings_download_directory_migrating_desc)
        }
        val progressFraction = activeMigrationProgress?.fraction?.coerceIn(0f, 1f) ?: 0f
        val processedSummary = activeMigrationProgress?.let { progress ->
            resources.getQuantityString(
                R.plurals.settings_download_directory_migrating_progress_files,
                progress.stageTotal.coerceAtLeast(0),
                progress.stageProcessed.coerceAtLeast(0),
                progress.stageTotal.coerceAtLeast(0)
            )
        }
        val processedBytesSummary = activeMigrationProgress?.let { progress ->
            if (progress.stage == ManagedDownloadStorage.MigrationStage.VERIFYING) {
                progress.takeIf { it.verificationBytesTotal > 0L }?.let { verificationProgress ->
                    resources.getString(
                        R.string.settings_download_directory_migrating_verification_progress_bytes,
                        Formatter.formatShortFileSize(
                            context,
                            verificationProgress.verifiedBytes.coerceAtLeast(0L)
                        ),
                        Formatter.formatShortFileSize(
                            context,
                            verificationProgress.verificationBytesTotal
                        )
                    )
                }
            } else {
                progress.takeIf { it.totalBytes > 0L }?.let { copyProgress ->
                    resources.getString(
                        R.string.settings_download_directory_migrating_progress_bytes,
                        Formatter.formatShortFileSize(
                            context,
                            copyProgress.copiedBytes.coerceAtLeast(0L)
                        ),
                        Formatter.formatShortFileSize(context, copyProgress.totalBytes)
                    )
                }
            }
        }
        val currentFileSummary = activeMigrationProgress?.currentFileName
            ?.takeIf(String::isNotBlank)
            ?.let { fileName ->
                resources.getString(R.string.settings_download_directory_migrating_current, fileName)
            }
        MiuixSettingsDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.settings_download_directory_migrating)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(stageText)
                    }
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    processedSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    processedBytesSummary?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    currentFileSummary?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun SettingsLoginExpandedContent(
    biliVm: BiliAuthViewModel,
    youtubeVm: YouTubeAuthViewModel,
    neteaseVm: NeteaseAuthViewModel,
    onOpenBiliSheet: (Int) -> Unit,
    onOpenBiliSavedCookieDialog: () -> Unit,
    onOpenYouTubeSavedCookieDialog: () -> Unit,
    onOpenNeteaseSavedCookieDialog: () -> Unit,
    onOpenYouTubeSheet: () -> Unit,
    onOpenNeteaseSheet: () -> Unit,
) {
    val biliAuthUiState by biliVm.uiState.collectAsStateWithLifecycleCompat()
    val youtubeAuthUiState by youtubeVm.uiState.collectAsStateWithLifecycleCompat()
    val neteaseAuthUiState by neteaseVm.uiState.collectAsStateWithLifecycleCompat()

    LaunchedEffect(biliVm, youtubeVm, neteaseVm) {
        biliVm.refreshAuthHealth()
        neteaseVm.refreshAuthHealth()
        youtubeVm.refreshAuthHealth()
    }

    val biliStatusText = when (biliAuthUiState.health.state) {
        SavedCookieAuthState.Valid -> {
            val relativeTime = biliAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_bili_status_valid, relativeTime)
        }
        SavedCookieAuthState.Checking -> {
            if (biliAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_bili_status_saved_invalid)
            } else {
                stringResource(R.string.settings_bili_status_missing)
            }
        }
        SavedCookieAuthState.Missing -> {
            if (biliAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_bili_status_saved_invalid)
            } else {
                stringResource(R.string.settings_bili_status_missing)
            }
        }
    }
    val neteaseStatusText = when (neteaseAuthUiState.health.state) {
        SavedCookieAuthState.Valid -> {
            val relativeTime = neteaseAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_netease_status_valid, relativeTime)
        }
        SavedCookieAuthState.Checking -> {
            if (neteaseAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_netease_status_saved_invalid)
            } else {
                stringResource(R.string.settings_netease_status_missing)
            }
        }
        SavedCookieAuthState.Missing -> {
            if (neteaseAuthUiState.hasSavedCookies) {
                stringResource(R.string.settings_netease_status_saved_invalid)
            } else {
                stringResource(R.string.settings_netease_status_missing)
            }
        }
    }
    val youtubeStatusText = when (youtubeAuthUiState.health.state) {
        YouTubeAuthState.Valid -> {
            val relativeTime = youtubeAuthUiState.health.savedAt
                .takeIf { it > 0L }
                ?.let { formatSyncTime(it) }
                ?: stringResource(R.string.time_just_now)
            stringResource(R.string.settings_youtube_status_valid, relativeTime)
        }
        YouTubeAuthState.Missing -> {
            if (youtubeAuthUiState.hasSavedAuth) {
                stringResource(R.string.settings_youtube_status_saved_invalid)
            } else {
                stringResource(R.string.settings_youtube_status_missing)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(start = 16.dp, end = 8.dp, bottom = 8.dp)
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_bilibili),
                    contentDescription = stringResource(R.string.settings_bilibili),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.platform_bilibili)) },
            supportingContent = { Text(biliStatusText) },
            modifier = Modifier.settingsItemClickable(
                onClick = {
                    if (biliAuthUiState.hasSavedCookies) {
                        onOpenBiliSavedCookieDialog()
                    } else {
                        onOpenBiliSheet(0)
                    }
                }
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_youtube),
                    contentDescription = stringResource(R.string.common_youtube),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.common_youtube)) },
            supportingContent = { Text(youtubeStatusText) },
            modifier = Modifier.settingsItemClickable(
                onClick = {
                    if (youtubeAuthUiState.hasSavedAuth) {
                        onOpenYouTubeSavedCookieDialog()
                    } else {
                        onOpenYouTubeSheet()
                    }
                }
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_netease_cloud_music),
                    contentDescription = stringResource(R.string.settings_netease),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.platform_netease)) },
            supportingContent = { Text(neteaseStatusText) },
            modifier = Modifier.settingsItemClickable(
                onClick = {
                    if (neteaseAuthUiState.hasSavedCookies) {
                        onOpenNeteaseSavedCookieDialog()
                    } else {
                        onOpenNeteaseSheet()
                    }
                }
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_qq_music),
                    contentDescription = stringResource(R.string.settings_qq_music),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.settings_qq_music)) },
            supportingContent = { Text(stringResource(R.string.common_coming_soon)) },
            modifier = Modifier.settingsItemClickable { },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
