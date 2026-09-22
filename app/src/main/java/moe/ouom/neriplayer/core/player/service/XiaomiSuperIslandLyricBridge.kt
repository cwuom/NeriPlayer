package moe.ouom.neriplayer.core.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import com.xzakota.hyper.notification.focus.FocusNotification
import com.xzakota.hyper.notification.island.model.BigIslandArea
import com.xzakota.hyper.notification.island.model.TextInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.XiaomiSuperIslandSettings
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.util.media.CoverArtColorCache

/** Publishes Xiaomi HyperOS Super Island lyrics independently from the media notification. */
internal class XiaomiSuperIslandLyricBridge(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val TAG = "NeriPlayerSuperIsland"
        const val CHANNEL_ID = "neriplayer_xiaomi_super_island_lyrics_v2"
        const val NOTIFICATION_ID = 0x454c4c53
        const val DEFAULT_ACCENT = 0xFF3482FF.toInt()
        const val MIN_RENDER_INTERVAL_MS = 1_500L
    }

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val imageLoader by lazy { ImageLoader(appContext) }
    private val appIcon by lazy { Icon.createWithResource(appContext, R.mipmap.ic_launcher) }
    private val networkMutex = Mutex()
    private val artworkCache = HashMap<String, Bitmap>()

    @Volatile
    private var enabled = false
    @Volatile
    private var settings = XiaomiSuperIslandSettings()
    private var lastPayloadKey: String? = null
    private var networkJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkSource: String? = null
    private var artworkRequest: RenderRequest? = null
    private var pauseDismissJob: Job? = null
    private var pendingRenderJob: Job? = null
    private var pendingRenderRequest: RenderRequest? = null
    private var lastRenderElapsedMs = 0L
    private var dispatchGeneration = 0L
    private var networkCutSeq = 0L
    private var aggressiveNetworkCutActive = false
    private var aggressiveTrackKey: String? = null
    private var aggressiveCutGeneration = 0L
    private var xmsfNetworkingBlocked = false

    private data class RenderRequest(
        val song: SongItem,
        val displayLyric: String,
        val fullLyric: String,
        val progressPercent: Int,
        val accentColor: Int,
        val artwork: Bitmap?,
        val settings: XiaomiSuperIslandSettings,
        val trackKey: String
    )

    private data class ArtworkResources(
        val source: Bitmap,
        val avatar: Icon,
        val island: Icon,
        val smallIsland: Icon,
        val share: Icon
    )

    private var artworkResources: ArtworkResources? = null

    @Synchronized
    private fun cachedArtworkResources(artwork: Bitmap?): ArtworkResources? {
        if (artwork == null || artwork.isRecycled) return null
        artworkResources?.takeIf { it.source === artwork }?.let { return it }
        return ArtworkResources(
            source = artwork,
            avatar = Icon.createWithBitmap(scaleArtwork(artwork, 480)),
            island = Icon.createWithBitmap(scaleArtwork(artwork, 120)),
            smallIsland = Icon.createWithBitmap(scaleArtwork(artwork, 88)),
            share = Icon.createWithBitmap(scaleArtwork(artwork, 224))
        ).also { artworkResources = it }
    }

    private fun scaleArtwork(source: Bitmap, targetSize: Int): Bitmap {
        if (source.width == targetSize && source.height == targetSize) return source
        return Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        lastPayloadKey = null
        if (enabled) ensureChannel() else clear()
    }

    fun setSettings(settings: XiaomiSuperIslandSettings) {
        val previousMode = this.settings.xmsfBypassMode
        this.settings = settings.sanitized()
        lastPayloadKey = null
        if (
            previousMode == XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE &&
            this.settings.xmsfBypassMode != XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE
        ) {
            restoreXmsfNetworkingAsync(expectedAggressiveGeneration = aggressiveCutGeneration)
        }
    }

    fun sendLyric(
        song: SongItem,
        line: LyricEntry,
        translation: LyricEntry?,
        positionMs: Long,
        durationMs: Long
    ) {
        if (!enabled) return
        pauseDismissJob?.cancel()
        val activeSettings = settings
        val original = line.text.trim()
        val translated = (translation?.text ?: line.translation).orEmpty().trim()
        val displayLyric = when (activeSettings.lyricTextMode) {
            XiaomiSuperIslandSettings.TEXT_TRANSLATION -> translated.ifBlank { original }
            XiaomiSuperIslandSettings.TEXT_PRONUNCIATION -> translated.ifBlank { original }
            else -> original
        }.ifBlank { return }
        val fullLyric = displayLyric
        val progress = if (durationMs > 0L) {
            ((positionMs.coerceIn(0L, durationMs) * 100L) / durationMs)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }
        val trackKey = song.stableKey()
        prepareForRender(trackKey, activeSettings.xmsfBypassMode)
        val coverSource = song.displayCoverUrl(appContext)?.trim().orEmpty()
        val artwork = synchronized(artworkCache) { artworkCache[coverSource] }
        val albumColorPending =
            activeSettings.colorSource == XiaomiSuperIslandSettings.COLOR_SOURCE_ALBUM &&
                coverSource.isNotBlank() &&
                artwork == null
        val accentColor = resolveAccentColor(activeSettings, artwork, coverSource)
        val payloadKey = listOf(
            trackKey,
            displayLyric,
            fullLyric,
            progress / 5,
            activeSettings.hashCode(),
            accentColor
        ).joinToString("|")
        synchronized(this) {
            if (!enabled || payloadKey == lastPayloadKey) return
            lastPayloadKey = payloadKey
        }

        val request = RenderRequest(
            song = song,
            displayLyric = displayLyric,
            fullLyric = fullLyric,
            progressPercent = progress,
            accentColor = accentColor,
            artwork = artwork,
            settings = activeSettings,
            trackKey = trackKey
        )
        if (artwork == null && coverSource.isNotBlank()) {
            requestArtwork(coverSource, request)
            // Do not publish the default blue first and then recolor the same island when the
            // cover arrives. The first Focus payload must already carry the album-derived color.
            if (albumColorPending) return
        }
        renderThrottled(request)
    }

    fun onPlaybackPaused() {
        restoreXmsfNetworkingAsync()
        pauseDismissJob?.cancel()
        val dismissDelay = settings.dismissDelayMs.toLong()
        if (dismissDelay <= 0L) {
            clear()
        } else {
            pauseDismissJob = scope.launch {
                delay(dismissDelay)
                clear()
            }
        }
    }

    fun clear(preserveAggressiveIsolation: Boolean = false) {
        pauseDismissJob?.cancel()
        pauseDismissJob = null
        pendingRenderJob?.cancel()
        pendingRenderJob = null
        pendingRenderRequest = null
        artworkJob?.cancel()
        artworkJob = null
        artworkSource = null
        artworkRequest = null
        lastRenderElapsedMs = 0L
        lastPayloadKey = null
        artworkResources = null
        XiaomiSuperIslandLyricService.stop(appContext)
        notificationManager.cancel(NOTIFICATION_ID)
        networkJob?.cancel()
        val preserveAggressive = preserveAggressiveIsolation &&
            settings.xmsfBypassMode == XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE &&
            aggressiveNetworkCutActive
        if (preserveAggressive) return

        aggressiveTrackKey = null
        aggressiveNetworkCutActive = false
        val generation = synchronized(this) { ++dispatchGeneration }
        networkJob = scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                if (generation == dispatchGeneration) restoreXmsfNetworking()
            }
        }
    }

    fun destroy() {
        enabled = false
        clear()
    }

    private fun requestArtwork(source: String, request: RenderRequest) {
        artworkRequest = request
        if (artworkSource == source && artworkJob?.isActive == true) return
        artworkSource = source
        artworkJob?.cancel()
        artworkJob = scope.launch(Dispatchers.IO) {
            val artwork = runCatching {
                val result = imageLoader.execute(
                    ImageRequest.Builder(appContext)
                        .data(source)
                        .allowHardware(false)
                        .build()
                )
                result.drawable?.toBitmap()
            }.getOrNull()
            val paletteColor = if (settings.colorSource == XiaomiSuperIslandSettings.COLOR_SOURCE_ALBUM) {
                runCatching { CoverArtColorCache.getOrLoad(appContext, source)?.baseColorArgb }
                    .getOrNull()
            } else {
                null
            }
            if (artwork == null || !enabled || artworkSource != source) return@launch
            synchronized(artworkCache) { artworkCache[source] = artwork }
            val latestRequest = synchronized(this@XiaomiSuperIslandLyricBridge) {
                artworkRequest?.also { artworkRequest = null } ?: request
            }
            withContext(Dispatchers.Main.immediate) {
                if (!enabled || artworkSource != source) return@withContext
                val updated = latestRequest.copy(
                    artwork = artwork,
                    accentColor = resolveAccentColor(settings, artwork, source, paletteColor),
                    settings = settings
                )
                renderThrottled(updated)
            }
        }
    }

    private fun renderThrottled(request: RenderRequest) {
        val now = SystemClock.elapsedRealtime()
        val remaining = (lastRenderElapsedMs + MIN_RENDER_INTERVAL_MS - now).coerceAtLeast(0L)
        if (remaining == 0L) {
            pendingRenderJob?.cancel()
            pendingRenderJob = null
            pendingRenderRequest = null
            lastRenderElapsedMs = now
            renderAndDispatch(request)
            return
        }

        pendingRenderRequest = request
        pendingRenderJob?.cancel()
        pendingRenderJob = scope.launch {
            delay(remaining)
            val latestRequest = pendingRenderRequest ?: return@launch
            pendingRenderRequest = null
            pendingRenderJob = null
            lastRenderElapsedMs = SystemClock.elapsedRealtime()
            renderAndDispatch(latestRequest)
        }
    }

    private fun renderAndDispatch(request: RenderRequest) {
        val notification = buildNotification(
            song = request.song,
            displayLyric = request.displayLyric,
            fullLyric = request.fullLyric,
            progressPercent = request.progressPercent,
            accentColor = request.accentColor,
            artwork = request.artwork,
            activeSettings = request.settings
        )
        Log.d(TAG, "Publishing Super Island lyric text=${request.displayLyric.take(48)}")
        dispatch(notification, request.trackKey, request.settings)
    }

    private fun prepareForRender(trackKey: String, mode: Int) {
        if (!aggressiveNetworkCutActive) return

        if (mode != XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE) {
            restoreXmsfNetworkingAsync(expectedAggressiveGeneration = aggressiveCutGeneration)
            return
        }

        if (aggressiveTrackKey != null && aggressiveTrackKey != trackKey) {
            // Auto-advance can deliver the next song while playback remains active. Keep the
            // aggressive bypass window open instead of briefly restoring XMSF between tracks.
            aggressiveTrackKey = trackKey
            aggressiveCutGeneration++
        }
    }

    private fun dispatch(
        notification: Notification,
        trackKey: String,
        activeSettings: XiaomiSuperIslandSettings
    ) {
        val mode = activeSettings.xmsfBypassMode
        networkJob?.cancel()
        if (mode == XiaomiSuperIslandSettings.XMSF_MODE_DISABLED) {
            val generation = synchronized(this) { ++dispatchGeneration }
            restoreXmsfNetworkingAsync(expectedDispatchGeneration = generation)
            XiaomiSuperIslandLyricService.publish(appContext, notification)
            return
        }

        if (mode == XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE) {
            dispatchAggressive(notification, trackKey)
        } else {
            dispatchWithTimedCut(
                notification = notification,
                durationMs = if (mode == XiaomiSuperIslandSettings.XMSF_MODE_CUSTOM) {
                    activeSettings.xmsfCustomDurationMs.toLong()
                } else {
                    XiaomiSuperIslandSettings.XMSF_STANDARD_DURATION_MS.toLong()
                }
            )
        }
    }

    private fun dispatchAggressive(notification: Notification, trackKey: String) {
        val dispatchId = synchronized(this) { ++dispatchGeneration }
        val cutGeneration = synchronized(this) { ++aggressiveCutGeneration }
        networkJob = scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                if (dispatchId != dispatchGeneration || cutGeneration != aggressiveCutGeneration || !enabled) {
                    return@withLock
                }

                // Capsulyric keeps the deny window open across lyric updates and track changes.
                // Only issue the expensive binder transaction again if the policy was not already
                // established; a later update still gets a fresh generation guard.
                if (!aggressiveNetworkCutActive) {
                    aggressiveNetworkCutActive = blockXmsfNetworking()
                }
                aggressiveTrackKey = trackKey
                if (dispatchId != dispatchGeneration || cutGeneration != aggressiveCutGeneration || !enabled) {
                    return@withLock
                }
                XiaomiSuperIslandLyricService.publish(appContext, notification)
            }
        }
    }

    private fun dispatchWithTimedCut(notification: Notification, durationMs: Long) {
        val dispatchId = synchronized(this) { ++dispatchGeneration }
        val seq = synchronized(this) { ++networkCutSeq }
        networkJob = scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                // If aggressive mode was active, this transaction also acts as a serialized
                // handoff: no restore can race the next timed cut.
                aggressiveNetworkCutActive = false
                aggressiveTrackKey = null
                blockXmsfNetworking()
                if (dispatchId != dispatchGeneration || !enabled) return@withLock
                XiaomiSuperIslandLyricService.publish(appContext, notification)
                try {
                    delay(durationMs)
                } catch (_: CancellationException) {
                    // A newer lyric owns the next restore window.
                }
                if (seq == networkCutSeq && dispatchId == dispatchGeneration) {
                    restoreXmsfNetworking()
                }
            }
        }
    }

    private suspend fun blockXmsfNetworking(): Boolean {
        val blocked = withContext(NonCancellable) {
            ShizukuXmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, false)
        }
        if (blocked) {
            xmsfNetworkingBlocked = true
        } else {
            Log.w(TAG, "XMSF bypass unavailable; sending Focus notification directly")
        }
        return blocked
    }

    private fun restoreXmsfNetworkingAsync(
        expectedDispatchGeneration: Long? = null,
        expectedAggressiveGeneration: Long? = null
    ) {
        scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                if (
                    expectedDispatchGeneration != null &&
                    expectedDispatchGeneration != dispatchGeneration
                ) {
                    return@withLock
                }
                if (
                    expectedAggressiveGeneration != null &&
                    expectedAggressiveGeneration != aggressiveCutGeneration
                ) {
                    return@withLock
                }
                restoreXmsfNetworking()
            }
        }
    }

    private suspend fun restoreXmsfNetworking() {
        aggressiveTrackKey = null
        aggressiveNetworkCutActive = false
        if (!xmsfNetworkingBlocked) return
        val restored = withContext(NonCancellable) {
            ShizukuXmsfNetworkHelper.setXmsfNetworkingEnabled(appContext, true)
        }
        if (restored) {
            xmsfNetworkingBlocked = false
        } else {
            Log.w(TAG, "Unable to restore XMSF networking; keeping retry state")
        }
    }

    private fun buildNotification(
        song: SongItem,
        displayLyric: String,
        fullLyric: String,
        progressPercent: Int,
        accentColor: Int,
        artwork: Bitmap?,
        activeSettings: XiaomiSuperIslandSettings
    ): Notification {
        val actionBundle = Bundle()
        val cachedArtwork = cachedArtworkResources(artwork)
        val songTitle = song.displayName().ifBlank { "♪" }
        val artist = song.displayArtist().trim()
        val subText = if (artist.isNotBlank()) "$songTitle - $artist" else songTitle
        val hexColor = String.format("#FF%06X", 0xFFFFFF and accentColor)
        val highlightColor = if (activeSettings.textColorEnabled) hexColor else "#757575"
        val progressColor = if (activeSettings.progressColorEnabled) hexColor else "#757575"

        val focusExtras = FocusNotification.buildV3 {
            business = "lyric_display"
            isShowNotification = true
            enableFloat = false
            updatable = true
            islandFirstFloat = false
            aodTitle = displayLyric.take(20).ifBlank { "♪" }

            val avatarKey = cachedArtwork?.avatar?.let { createPicture("miui.focus.pic_avatar", it) }
            val islandKey = cachedArtwork?.island?.let { createPicture("miui.focus.pic_island", it) }
            val smallIslandKey = cachedArtwork?.smallIsland?.let {
                createPicture("miui.land.pic_island", it)
            }
            val shareKey = cachedArtwork?.share?.let { createPicture("miui.focus.pic_share", it) }
            val appKey = createPicture("miui.focus.pic_app", appIcon)

            ticker = displayLyric.ifBlank { fullLyric }
            tickerPic = appKey

            chatInfo {
                picProfile = avatarKey
                title = fullLyric
                content = subText
                appIconPkg = appContext.packageName
            }

            if (activeSettings.actionStyle == XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS) {
                actions {
                    val useThreeButtons =
                        activeSettings.mediaButtonLayout == XiaomiSuperIslandSettings.MEDIA_BUTTON_LAYOUT_THREE
                    if (useThreeButtons) {
                        addActionInfo {
                            type = 0
                            action = createMediaAction(
                                actionBundle,
                                "miui.focus.action_prev",
                                3610,
                                AudioPlayerService.ACTION_PREV,
                                R.drawable.round_skip_previous_24,
                                "Previous"
                            )
                            actionIcon = createPicture(
                                "miui.focus.pic_btn_prev",
                                controlActionIcon(R.drawable.round_skip_previous_24)
                            )
                            clickWithCollapse = false
                        }
                    }
                    addActionInfo {
                        type = 0
                        action = createMediaAction(
                            actionBundle,
                            "miui.focus.action_play_pause",
                            3611,
                            AudioPlayerService.ACTION_TOGGLE_PLAY_PAUSE,
                            R.drawable.round_pause_24,
                            "Play/Pause"
                        )
                        actionIcon = createPicture(
                            "miui.focus.pic_btn_play_pause",
                            controlActionIcon(R.drawable.round_pause_24)
                        )
                        clickWithCollapse = false
                    }
                    addActionInfo {
                        type = 0
                        action = createMediaAction(
                            actionBundle,
                            "miui.focus.action_next",
                            3612,
                            AudioPlayerService.ACTION_NEXT,
                            R.drawable.round_skip_next_24,
                            "Next"
                        )
                        actionIcon = createPicture(
                            "miui.focus.pic_btn_next",
                            controlActionIcon(R.drawable.round_skip_next_24)
                        )
                        clickWithCollapse = false
                    }
                }
            } else {
                progressInfo {
                    progress = progressPercent
                    colorProgress = progressColor
                    colorProgressEnd = progressColor
                }
            }

            island {
                islandProperty = 1
                if (activeSettings.textColorEnabled) this.highlightColor = hexColor
                bigIslandArea {
                    applyLyrics(
                        settings = activeSettings,
                        displayLyric = displayLyric,
                        fullLyric = fullLyric,
                        song = song,
                        islandKey = islandKey,
                        showHighlightColor = activeSettings.textColorEnabled
                    )
                }
                if (activeSettings.shareEnabled) {
                    shareData {
                        pic = shareKey
                        title = songTitle
                        content = fullLyric
                        this.shareContent = shareContent(song, fullLyric, activeSettings.shareFormat)
                    }
                }
                smallIslandArea {
                    combinePicInfo {
                        if (smallIslandKey != null) {
                            picInfo {
                                type = 1
                                pic = smallIslandKey
                            }
                        }
                        progressInfo {
                            progress = progressPercent
                            colorReach = highlightColor
                            colorUnReach = "#333333"
                        }
                    }
                }
            }
        }
        if (!actionBundle.isEmpty) {
            focusExtras.putBundle("miui.focus.actions", actionBundle)
        }

        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyrics_24)
            .setContentTitle(fullLyric)
            .setContentText(subText)
            .setSubText(appContext.packageName)
            .setContentIntent(createContentIntent(activeSettings.clickStyle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(
                if (activeSettings.actionStyle == XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS) {
                    0xFF757575.toInt()
                } else {
                    accentColor
                }
            )
            .addExtras(focusExtras)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun BigIslandArea.applyLyrics(
        settings: XiaomiSuperIslandSettings,
        displayLyric: String,
        fullLyric: String,
        song: SongItem,
        islandKey: String?,
        showHighlightColor: Boolean
    ) {
        val titleWithArtist = buildString {
            append(song.displayName().ifBlank { "♪" })
            song.displayArtist().takeIf { it.isNotBlank() }?.let {
                append(" - ")
                append(it)
            }
        }
        val showLeftCover = islandKey != null &&
            (settings.lyricMode != XiaomiSuperIslandSettings.LYRIC_MODE_FULL ||
                settings.fullLyricShowLeftCover)
        val leftWeight = XiaomiSuperIslandLyricLayout.weightForCharacters(
            if (showLeftCover) settings.leftWithCoverTextChars else settings.leftWithoutCoverTextChars
        )
        val rightWeight = XiaomiSuperIslandLyricLayout.weightForCharacters(settings.rightTextChars)
        val text = if (settings.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL) {
            XiaomiSuperIslandLyricLayout.splitFullLyric(
                text = fullLyric,
                showLeftCover = showLeftCover,
                leftMaxWeight = leftWeight,
                rightMaxWeight = rightWeight
            )
        } else {
            XiaomiSuperIslandLyricLayout.Split(
                left = XiaomiSuperIslandLyricLayout.takeByWeight(titleWithArtist, leftWeight),
                right = XiaomiSuperIslandLyricLayout.takeByWeight(displayLyric, rightWeight)
            )
        }
        imageTextInfoLeft {
            type = 1
            if (showLeftCover) {
                picInfo {
                    type = 1
                    pic = islandKey
                }
            }
            textInfo {
                title = text.left.ifBlank { "♪" }
                this.showHighlightColor = showHighlightColor
                narrowFont = false
            }
        }
        textInfo = TextInfo().apply {
            title = text.right.ifBlank { "♪" }
            this.showHighlightColor = showHighlightColor
            narrowFont = false
        }
    }

    private fun controlActionIcon(@androidx.annotation.DrawableRes resId: Int): Icon =
        Icon.createWithResource(appContext, resId)

    private fun createMediaAction(
        actionBundle: Bundle,
        key: String,
        requestCode: Int,
        action: String,
        iconResId: Int,
        title: String
    ): String {
        val pendingIntent = createMediaCommandIntent(requestCode, action)
        val notificationAction = Notification.Action.Builder(
            controlActionIcon(iconResId),
            title,
            pendingIntent
        ).build()
        actionBundle.putParcelable(key, notificationAction)
        return key
    }

    private fun createMediaCommandIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            appContext,
            requestCode,
            Intent(appContext, AudioPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createContentIntent(clickStyle: Int): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            3600 + clickStyle,
            Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun shareContent(song: SongItem, lyric: String, format: Int): String {
        val title = song.displayName().ifBlank { "未知歌曲" }
        val artist = song.displayArtist().ifBlank { "未知歌手" }
        return when (format) {
            XiaomiSuperIslandSettings.SHARE_FORMAT_INLINE -> "$lyric -$artist，$title"
            XiaomiSuperIslandSettings.SHARE_FORMAT_ARTIST_AND_SONG -> "$lyric\n$artist，$title"
            else -> "$lyric\n$title by $artist"
        }
    }

    private fun resolveAccentColor(
        settings: XiaomiSuperIslandSettings,
        artwork: Bitmap?,
        coverSource: String,
        paletteColor: Int? = null
    ): Int {
        if (settings.colorSource == XiaomiSuperIslandSettings.COLOR_SOURCE_CUSTOM) {
            return settings.customColor
        }
        return paletteColor
            ?: CoverArtColorCache.peek(coverSource)?.baseColorArgb
            ?: artwork?.let(::averageColor)
            ?: DEFAULT_ACCENT
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val sample = scaleArtwork(bitmap, 24)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val color = sample.getPixel(x, y)
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        if (count == 0L) return DEFAULT_ACCENT
        return Color.rgb(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt()
        ) or (0xFF shl 24)
    }

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "小米超级岛歌词",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "HyperOS Super Island lyric updates"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
