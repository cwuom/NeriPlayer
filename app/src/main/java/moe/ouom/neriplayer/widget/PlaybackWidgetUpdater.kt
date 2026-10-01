package moe.ouom.neriplayer.widget

import moe.ouom.neriplayer.core.player.presentation.widget.shouldRetainPlaybackWidgetVisuals
import moe.ouom.neriplayer.core.player.presentation.widget.shouldUseCachedPlaybackWidgetArtwork
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.core.player.presentation.widget.PLAYBACK_WIDGET_PROGRESS_MAX
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.appcompat.content.res.AppCompatResources
import androidx.annotation.LayoutRes
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import java.io.File
import java.io.FileOutputStream

internal object PlaybackWidgetUpdater {
    private const val PREFS_NAME = "neriplayer_playback_widget"
    private const val KEY_TITLE = "title"
    private const val KEY_SUBTITLE = "subtitle"
    private const val KEY_STATUS = "status"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_ELAPSED_TEXT = "elapsed_text"
    private const val KEY_DURATION_TEXT = "duration_text"
    private const val KEY_PROGRESS = "progress"
    private const val KEY_HAS_SONG = "has_song"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_IS_FAVORITE = "is_favorite"
    private const val KEY_CAN_TOGGLE_FAVORITE = "can_toggle_favorite"
    private const val KEY_FLOATING_LYRICS_ENABLED = "floating_lyrics_enabled"
    private const val KEY_ARTWORK_READY = "artwork_ready"
    private const val ARTWORK_FILE_NAME = "playback_widget_artwork.png"
    private const val ARTWORK_TEMP_FILE_NAME = "playback_widget_artwork.tmp"
    private const val REQUEST_OPEN_APP = 6100
    private const val REQUEST_PREVIOUS = 6101
    private const val REQUEST_PLAY_PAUSE = 6102
    private const val REQUEST_NEXT = 6103
    private const val REQUEST_FAVORITE = 6104
    private const val REQUEST_FLOATING_LYRICS = 6105
    private var lastArtworkInput: Bitmap? = null
    private var lastWidgetVisuals: PlaybackWidgetVisuals? = null
    private var lastDarkTheme: Boolean? = null

    fun updateFromPlaybackService(
        context: Context,
        state: PlaybackWidgetState,
        artwork: Bitmap?,
    ) {
        val visuals = prepareVisuals(context, state, artwork)
        saveState(context, state)
        updateAllInstalledWidgets(context, state, visuals)
    }

    fun refreshForConfigurationChange(context: Context) {
        if (!hasInstalledWidgets(context)) return
        if (AudioPlayerService.refreshPlaybackWidgetsFromActiveService("widget_configuration_changed")) return
        val state = readState(context)
        updateAllInstalledWidgets(
            context, state, buildPlaybackWidgetVisuals(readCachedArtwork(context, state), isDarkTheme(context)),
        )
    }

    fun updatePlaybackProgressFromPlaybackService(
        context: Context,
        state: PlaybackWidgetState,
    ) {
        if (!state.hasSong || !state.isPlaying) {
            return
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, NeriPlayerPlaybackWidgetProvider::class.java),
        )
        if (appWidgetIds.isEmpty()) {
            return
        }
        try {
            appWidgetIds.forEach { id ->
                val sizes = playbackWidgetSizeVariantsFromOptions(
                    appWidgetManager.getAppWidgetOptions(id), hasProgress = true,
                )
                if (sizes.size > 1 || lastDarkTheme != isDarkTheme(context)) {
                    // the host merges only root actions for partial RemoteViews updates
                    updateWidgetGroup(
                        context, appWidgetManager, intArrayOf(id), R.layout.widget_playback_4x2,
                        state, prepareVisuals(context, state, null), sizes,
                    )
                } else {
                    val views = buildPlaybackWidgetProgressRemoteViews(
                        context, resolvePlaybackWidgetLayoutRes(R.layout.widget_playback_4x2, sizes.first()), state,
                    )
                    appWidgetManager.partiallyUpdateAppWidget(id, views)
                }
            }
        } catch (error: RuntimeException) {
            NPLogger.w("NERI-Widget", "Widget progress update failed", error)
        }
    }

    fun updateStoredWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        @LayoutRes layoutRes: Int,
        widgetOptions: Bundle? = null,
    ) {
        val state = readState(context)
        updateWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = appWidgetIds,
            layoutRes = layoutRes,
            state = state,
            visuals = buildPlaybackWidgetVisuals(readCachedArtwork(context, state), isDarkTheme(context)),
            widgetOptions = widgetOptions,
        )
    }

    internal fun hasInstalledWidgets(context: Context): Boolean {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        return appWidgetManager.getAppWidgetIds(
            ComponentName(context, NeriPlayerPlaybackWidgetProvider::class.java),
        ).isNotEmpty() || appWidgetManager.getAppWidgetIds(
            ComponentName(context, NeriPlayerCompactWidgetProvider::class.java),
        ).isNotEmpty() || appWidgetManager.getAppWidgetIds(
            ComponentName(context, NeriPlayerStripWidgetProvider::class.java),
        ).isNotEmpty()
    }

    private fun updateAllInstalledWidgets(
        context: Context,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
    ) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        updateProvider(
            context = context,
            appWidgetManager = appWidgetManager,
            provider = NeriPlayerPlaybackWidgetProvider::class.java,
            layoutRes = R.layout.widget_playback_4x2,
            state = state,
            visuals = visuals,
        )
        updateProvider(
            context = context,
            appWidgetManager = appWidgetManager,
            provider = NeriPlayerStripWidgetProvider::class.java,
            layoutRes = R.layout.widget_playback_4x1,
            state = state,
            visuals = visuals,
        )
        updateProvider(
            context = context,
            appWidgetManager = appWidgetManager,
            provider = NeriPlayerCompactWidgetProvider::class.java,
            layoutRes = R.layout.widget_playback_2x2,
            state = state,
            visuals = visuals,
        )
    }

    private fun updateProvider(
        context: Context,
        appWidgetManager: AppWidgetManager,
        provider: Class<*>,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
    ) {
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, provider))
        updateWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = ids,
            layoutRes = layoutRes,
            state = state,
            visuals = visuals,
        )
    }

    private fun updateWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
        widgetOptions: Bundle? = null,
    ) {
        if (appWidgetIds.isEmpty()) {
            return
        }
        val hasProgress = isPlaybackWidgetWithProgress(layoutRes)
        val sizeVariantsByWidgetId = appWidgetIds.asList().associateWith { appWidgetId ->
            playbackWidgetSizeVariantsFromOptions(
                options = widgetOptions ?: appWidgetManager.getAppWidgetOptions(appWidgetId),
                hasProgress = hasProgress,
                isStrip = layoutRes == R.layout.widget_playback_4x1,
            )
        }
        sizeVariantsByWidgetId.entries.groupBy { it.value }.forEach { (sizes, entries) ->
            updateWidgetGroup(
                context = context,
                appWidgetManager = appWidgetManager,
                appWidgetIds = entries.map { it.key }.toIntArray(),
                layoutRes = layoutRes,
                state = state,
                visuals = visuals,
                sizes = sizes,
            )
        }
    }

    private fun updateWidgetGroup(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
        sizes: List<PlaybackWidgetSize>,
    ) {
        val views = buildRemoteViewsForSizes(context, layoutRes, state, visuals, sizes)
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, views)
        } catch (error: RuntimeException) {
            NPLogger.w(
                "NERI-Widget",
                "Widget update failed with artwork; retrying without bitmap payload",
                error,
            )
            val fallbackViews = buildRemoteViewsForSizes(
                context = context,
                layoutRes = layoutRes,
                state = state,
                visuals = buildPlaybackWidgetVisuals(null, isDarkTheme(context)),
                sizes = sizes,
                includeBitmapPayload = false,
            )
            try {
                appWidgetManager.updateAppWidget(appWidgetIds, fallbackViews)
            } catch (fallbackError: RuntimeException) {
                NPLogger.w(
                    "NERI-Widget",
                    "Widget update failed without bitmap payload",
                    fallbackError,
                )
            }
        }
    }

    internal fun buildRemoteViews(
        context: Context,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
        size: PlaybackWidgetSize,
        includeBitmapPayload: Boolean = true,
    ): RemoteViews {
        val resolvedLayoutRes = resolvePlaybackWidgetLayoutRes(layoutRes, size)
        val views = RemoteViews(context.packageName, resolvedLayoutRes)
        val hasProgress = isPlaybackWidgetWithProgress(resolvedLayoutRes)
        val compact = resolvedLayoutRes == R.layout.widget_playback_2x2
        applyPlaybackWidgetLayout(
            context = context,
            views = views,
            layoutRes = resolvedLayoutRes,
            size = size,
            hasProgress = hasProgress,
        )
        val controlReceiver = when (layoutRes) {
            R.layout.widget_playback_4x1 -> NeriPlayerStripWidgetProvider::class.java
            R.layout.widget_playback_2x2 -> NeriPlayerCompactWidgetProvider::class.java
            else -> NeriPlayerPlaybackWidgetProvider::class.java
        }
        views.setTextViewText(R.id.widget_title, state.title)
        views.setTextViewText(R.id.widget_subtitle, state.subtitle)
        views.setTextViewText(R.id.widget_status, state.status)
        if (hasProgress) {
            applyPlaybackWidgetProgress(views, state)
            views.setTextViewText(R.id.widget_duration, state.durationText)
        }
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (state.showPauseAction) CoreCommonR.drawable.round_pause_24 else CoreCommonR.drawable.round_play_arrow_24,
        )
        if (hasProgress) {
            views.setImageViewResource(
                R.id.widget_favorite,
                if (state.isFavorite) {
                    R.drawable.widget_icon_favorite_filled
                } else {
                    R.drawable.widget_icon_favorite
                },
            )
        }
        if (includeBitmapPayload && visuals.primaryControl != null) {
            views.setImageViewBitmap(R.id.widget_play_pause_background, visuals.primaryControl)
        } else {
            views.setImageViewResource(
                R.id.widget_play_pause_background,
                R.drawable.widget_primary_control_background,
            )
        }
        val cornerRadiusDp = context.resources.getDimension(R.dimen.widget_background_radius) /
            context.resources.displayMetrics.density
        views.setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        if (includeBitmapPayload && !compact) {
            views.setImageViewBitmap(R.id.widget_theme_background,
                playbackWidgetSurface(size, cornerRadiusDp, visuals.backgroundColor,
                    renderScale = context.resources.displayMetrics.density))
            views.setInt(R.id.widget_theme_background, "setColorFilter", android.graphics.Color.TRANSPARENT)
        } else {
            views.setImageViewResource(R.id.widget_theme_background, R.drawable.widget_playback_clip)
            views.setInt(R.id.widget_theme_background, "setColorFilter", visuals.backgroundColor)
        }
        views.setViewVisibility(R.id.widget_theme_background,
            if (includeBitmapPayload && compact) android.view.View.GONE else android.view.View.VISIBLE)
        views.setViewVisibility(R.id.widget_fallback_background, android.view.View.GONE)
        views.setTextColor(R.id.widget_title, if (compact) android.graphics.Color.WHITE else visuals.textPrimary)
        views.setTextColor(R.id.widget_subtitle, if (compact) 0xFFE9EFEB.toInt() else visuals.textSecondary)
        views.setTextColor(R.id.widget_status, if (compact) 0xFFE9EFEB.toInt() else visuals.textSecondary)
        views.setInt(R.id.widget_play_pause, "setColorFilter", visuals.primaryControlTint)
        listOf(R.id.widget_previous, R.id.widget_next).forEach { id ->
            views.setInt(id, "setColorFilter", if (compact) android.graphics.Color.WHITE else visuals.controlTint)
        }
        if (hasProgress) {
            views.setTextColor(R.id.widget_elapsed, visuals.textSecondary)
            views.setTextColor(R.id.widget_duration, visuals.textSecondary)
            views.setInt(R.id.widget_favorite, "setColorFilter", visuals.controlTint)
            views.setInt(R.id.widget_floating_lyrics, "setColorFilter", visuals.controlTint)
        }
        val albumArtwork = if (!includeBitmapPayload) null else if (compact) {
            val cover = visuals.compactArtwork ?: requireNotNull(
                AppCompatResources.getDrawable(context, R.drawable.ic_neriplayer_round),
            ).toBitmap(192, 192)
            playbackWidgetBackdrop(cover, size, cornerRadiusDp, applyScrim = true,
                renderScale = context.resources.displayMetrics.density)
        } else visuals.artwork
        if (albumArtwork != null) {
            views.setImageViewBitmap(R.id.widget_album_art, albumArtwork)
            views.setInt(R.id.widget_album_shell, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_neriplayer_round)
            if (!compact) {
                views.setInt(R.id.widget_album_shell, "setBackgroundResource", R.drawable.widget_album_background)
            }
        }
        if (compact) {
            views.setViewVisibility(R.id.widget_album_scrim,
                if (albumArtwork == null) android.view.View.VISIBLE else android.view.View.GONE)
        }

        val openAppIntent = openAppPendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)
        views.setContentDescription(
            R.id.widget_root,
            listOf(state.title, state.subtitle, state.status)
                .filter(String::isNotBlank)
                .joinToString(", "),
        )
        views.setOnClickPendingIntent(
            R.id.widget_previous_touch,
            enabledPlaybackPendingIntent(
                context = context,
                action = AudioPlayerService.ACTION_PREV,
                requestCode = REQUEST_PREVIOUS,
                enabled = state.hasSong,
                fallback = openAppIntent,
                receiver = controlReceiver,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play_pause_touch,
            enabledPlaybackPendingIntent(
                context = context,
                action = if (state.showPauseAction) AudioPlayerService.ACTION_PAUSE else AudioPlayerService.ACTION_PLAY,
                requestCode = REQUEST_PLAY_PAUSE,
                enabled = state.hasSong,
                fallback = openAppIntent,
                receiver = controlReceiver,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next_touch,
            enabledPlaybackPendingIntent(
                context = context,
                action = AudioPlayerService.ACTION_NEXT,
                requestCode = REQUEST_NEXT,
                enabled = state.hasSong,
                fallback = openAppIntent,
                receiver = controlReceiver,
            ),
        )
        if (hasProgress) {
            views.setOnClickPendingIntent(
                R.id.widget_favorite_touch,
                enabledPlaybackPendingIntent(
                    context = context,
                    action = AudioPlayerService.ACTION_TOGGLE_FAV,
                    requestCode = REQUEST_FAVORITE,
                    enabled = state.hasSong && state.canToggleFavorite,
                    fallback = openAppIntent,
                    receiver = controlReceiver,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_floating_lyrics_touch,
                enabledPlaybackPendingIntent(
                    context = context,
                    action = AudioPlayerService.ACTION_TOGGLE_FLOATING_LYRICS,
                    requestCode = REQUEST_FLOATING_LYRICS,
                    enabled = state.hasSong,
                    fallback = openAppIntent,
                    receiver = controlReceiver,
                ),
            )
        }
        views.setContentDescription(
            R.id.widget_play_pause_touch,
            context.getString(if (state.showPauseAction) CoreCommonR.string.player_pause else CoreCommonR.string.player_play),
        )
        views.setContentDescription(
            R.id.widget_previous_touch,
            context.getString(CoreCommonR.string.player_previous),
        )
        views.setContentDescription(R.id.widget_next_touch, context.getString(CoreCommonR.string.player_next))
        if (hasProgress) {
            views.setContentDescription(
                R.id.widget_favorite_touch,
                context.getString(
                    if (state.isFavorite) CoreCommonR.string.favorite_remove else CoreCommonR.string.favorite_add,
                ),
            )
            views.setContentDescription(
                R.id.widget_floating_lyrics_touch,
                context.getString(
                    if (state.isFloatingLyricsEnabled) {
                        CoreCommonR.string.notification_hide_floating_lyrics
                    } else {
                        CoreCommonR.string.notification_show_floating_lyrics
                    },
                ),
            )
        }
        setControlEnabled(views, R.id.widget_previous_touch, state.hasSong)
        setControlEnabled(views, R.id.widget_play_pause_touch, state.hasSong)
        setControlEnabled(views, R.id.widget_next_touch, state.hasSong)
        if (hasProgress) {
            setControlEnabled(
                views,
                R.id.widget_favorite_touch,
                state.hasSong && state.canToggleFavorite,
            )
            setControlEnabled(views, R.id.widget_floating_lyrics_touch, state.hasSong)
            views.setImageViewResource(
                R.id.widget_floating_lyrics,
                if (state.isFloatingLyricsEnabled) {
                    CoreCommonR.drawable.ic_lyrics_off_24
                } else {
                    R.drawable.widget_icon_lyrics
                },
            )
        }
        return views
    }

    internal fun buildPlaybackWidgetProgressRemoteViews(
        context: Context,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
    ): RemoteViews {
        return RemoteViews(context.packageName, layoutRes).also { views ->
            if (isPlaybackWidgetWithProgress(layoutRes)) applyPlaybackWidgetProgress(views, state)
        }
    }

    private fun applyPlaybackWidgetProgress(
        views: RemoteViews,
        state: PlaybackWidgetState,
    ) {
        views.setChronometer(
            R.id.widget_elapsed,
            SystemClock.elapsedRealtime() - state.positionMs.coerceAtLeast(0L),
            null,
            state.hasSong && state.isPlaying,
        )
        // reset the base before the text so a seek is visible before the next tick
        views.setTextViewText(R.id.widget_elapsed, state.elapsedText)
        views.setProgressBar(
            R.id.widget_progress,
            PLAYBACK_WIDGET_PROGRESS_MAX,
            state.progress,
            false,
        )
    }

    internal fun buildRemoteViewsForSizes(
        context: Context,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
        sizes: List<PlaybackWidgetSize>,
        includeBitmapPayload: Boolean = true,
    ): RemoteViews {
        val normalizedSizes = sizes.ifEmpty {
            listOf(
                PlaybackWidgetSize(
                    widthDp = if (isPlaybackWidgetWithProgress(layoutRes) || layoutRes == R.layout.widget_playback_4x1) {
                        PLAYBACK_WIDGET_DEFAULT_FULL_WIDTH_DP
                    } else {
                        PLAYBACK_WIDGET_DEFAULT_COMPACT_WIDTH_DP
                    },
                    heightDp = if (layoutRes == R.layout.widget_playback_4x1) {
                        PLAYBACK_WIDGET_DEFAULT_STRIP_HEIGHT_DP
                    } else {
                        PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP
                    },
                ),
            )
        }
        return combinePlaybackWidgetSizes(normalizedSizes) { size ->
            buildRemoteViews(context, layoutRes, state, visuals, size, includeBitmapPayload)
        }
    }

    private fun combinePlaybackWidgetSizes(
        sizes: List<PlaybackWidgetSize>,
        build: (PlaybackWidgetSize) -> RemoteViews,
    ): RemoteViews {
        if (sizes.size == 1) return build(sizes.first())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return RemoteViews(build(sizes.last()), build(sizes.first()))
        }
        return RemoteViews(sizes.associate { size ->
            SizeF(size.widthDp.toFloat(), size.heightDp.toFloat()) to build(size)
        })
    }

    private fun applyPlaybackWidgetLayout(
        context: Context,
        views: RemoteViews,
        @LayoutRes layoutRes: Int,
        size: PlaybackWidgetSize,
        hasProgress: Boolean,
    ) {
        val strip = layoutRes == R.layout.widget_playback_4x1
        val expanded = layoutRes == R.layout.widget_playback_4x2_expanded
        val spec = playbackWidgetLayoutSpec(size, hasProgress)
        val density = context.resources.displayMetrics.density
        val padding = if (strip) 8 else spec.horizontalPaddingDp
        val topPadding = if (strip) 4 else spec.topPaddingDp
        val bottomPadding = if (strip) 4 else spec.bottomPaddingDp
        views.setViewPadding(
            R.id.widget_main_content,
            (padding * density).toInt(), (topPadding * density).toInt(),
            (padding * density).toInt(), (bottomPadding * density).toInt(),
        )
        views.setTextViewTextSize(R.id.widget_status, TypedValue.COMPLEX_UNIT_SP, spec.statusTextSizeSp)
        views.setTextViewTextSize(
            R.id.widget_title, TypedValue.COMPLEX_UNIT_SP,
            if (strip) 16f else spec.titleTextSizeSp,
        )
        views.setTextViewTextSize(
            R.id.widget_subtitle, TypedValue.COMPLEX_UNIT_SP,
            if (strip) 12f else spec.subtitleTextSizeSp,
        )
        val largeText = context.resources.configuration.fontScale >= 1.3f
        views.setViewVisibility(R.id.widget_status, if (expanded && !largeText) android.view.View.VISIBLE else android.view.View.GONE)
        views.setViewVisibility(
            R.id.widget_subtitle,
            if (largeText && size.heightDp < 130) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            },
        )
        views.setViewVisibility(R.id.widget_previous_touch, android.view.View.VISIBLE)
        if (hasProgress) {
            views.setViewVisibility(
                R.id.widget_floating_lyrics_touch,
                if (size.widthDp >= 280) android.view.View.VISIBLE else android.view.View.GONE,
            )
            views.setTextViewTextSize(R.id.widget_elapsed, TypedValue.COMPLEX_UNIT_SP, 11f)
            views.setTextViewTextSize(R.id.widget_duration, TypedValue.COMPLEX_UNIT_SP, 11f)
            listOf(R.id.widget_elapsed, R.id.widget_duration).forEach { id ->
                views.setViewVisibility(id, if (largeText && !expanded) android.view.View.GONE else android.view.View.VISIBLE)
            }
            applyViewSize(views, R.id.widget_progress_row, null, spec.progressRowHeightDp)
        }
        val albumSize = if (strip) {
            40
        } else {
            spec.albumSizeDp
        }
        if (layoutRes != R.layout.widget_playback_2x2) {
            applyViewSize(views, R.id.widget_album_shell, albumSize, albumSize)
        }
        applyViewSize(views, R.id.widget_controls, null,
            if (strip) 48 else if (hasProgress) spec.controlsHeightDp else 44,
        )
        applyWidgetControlSizes(views, spec, hasProgress)
    }

    private fun isPlaybackWidgetWithProgress(@LayoutRes layoutRes: Int): Boolean {
        return layoutRes == R.layout.widget_playback_4x2 ||
            layoutRes == R.layout.widget_playback_4x2_expanded
    }

    @LayoutRes
    private fun resolvePlaybackWidgetLayoutRes(
        @LayoutRes layoutRes: Int,
        size: PlaybackWidgetSize,
    ): Int {
        if (layoutRes == R.layout.widget_playback_4x1) return layoutRes
        if (size.heightDp < PLAYBACK_WIDGET_DEFAULT_HEIGHT_DP && size.widthDp >= 240) {
            return R.layout.widget_playback_4x1
        }
        if (layoutRes == R.layout.widget_playback_2x2) return layoutRes
        return if (shouldUseExpandedFullPlaybackWidgetLayout(size)) {
            R.layout.widget_playback_4x2_expanded
        } else {
            R.layout.widget_playback_4x2
        }
    }

    private fun applyWidgetControlSizes(
        views: RemoteViews,
        spec: PlaybackWidgetLayoutSpec,
        hasProgress: Boolean,
    ) {
        val regularControls = buildList {
            if (hasProgress) {
                add(R.id.widget_favorite)
            }
            add(R.id.widget_previous)
            add(R.id.widget_next)
            if (hasProgress) {
                add(R.id.widget_floating_lyrics)
            }
        }
        regularControls.forEach { viewId ->
            applyViewSize(
                views = views,
                viewId = viewId,
                widthDp = spec.controlSizeDp,
                heightDp = spec.controlSizeDp,
            )
        }
        applyViewSize(
            views = views,
            viewId = R.id.widget_play_pause_background,
            widthDp = spec.primaryControlSizeDp,
            heightDp = spec.primaryControlSizeDp,
        )
        applyViewSize(
            views = views,
            viewId = R.id.widget_play_pause,
            widthDp = if (hasProgress) 22 else 24,
            heightDp = if (hasProgress) 22 else 24,
        )
    }

    private fun applyViewSize(
        views: RemoteViews,
        viewId: Int,
        widthDp: Int?,
        heightDp: Int?,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        widthDp?.let {
            views.setViewLayoutWidth(viewId, it.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        }
        heightDp?.let {
            views.setViewLayoutHeight(viewId, it.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun enabledPlaybackPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        enabled: Boolean,
        fallback: PendingIntent,
        receiver: Class<out NeriPlayerBaseWidgetProvider>,
    ): PendingIntent {
        if (!enabled) {
            return fallback
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, receiver).apply {
                this.action = ACTION_PLAYBACK_WIDGET_CONTROL
                data = Uri.Builder()
                    .scheme("neriplayer")
                    .authority("playback-widget")
                    .appendPath(requestCode.toString())
                    .appendPath(action)
                    .build()
                putExtra(EXTRA_PLAYBACK_WIDGET_SERVICE_ACTION, action)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setControlEnabled(
        views: RemoteViews,
        viewId: Int,
        enabled: Boolean,
    ) {
        views.setBoolean(viewId, "setEnabled", enabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setFloat(viewId, "setAlpha", if (enabled) 1f else 0.5f)
        }
    }

    private fun saveState(context: Context, state: PlaybackWidgetState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_TITLE, state.title)
            putString(KEY_SUBTITLE, state.subtitle)
            putString(KEY_STATUS, state.status)
            putLong(KEY_POSITION_MS, state.positionMs)
            putString(KEY_ELAPSED_TEXT, state.elapsedText)
            putString(KEY_DURATION_TEXT, state.durationText)
            putInt(KEY_PROGRESS, state.progress)
            putBoolean(KEY_HAS_SONG, state.hasSong)
            putBoolean(KEY_IS_PLAYING, state.isPlaying)
            putBoolean(KEY_IS_FAVORITE, state.isFavorite)
            putBoolean(KEY_CAN_TOGGLE_FAVORITE, state.canToggleFavorite)
            putBoolean(KEY_FLOATING_LYRICS_ENABLED, state.isFloatingLyricsEnabled)
            putBoolean(KEY_ARTWORK_READY, state.artworkReady)
        }
    }

    internal fun readState(context: Context): PlaybackWidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TITLE)) {
            return PlaybackWidgetState.idle(context)
        }
        return PlaybackWidgetState(
            title = prefs.getString(KEY_TITLE, null) ?: context.getString(CoreCommonR.string.app_name),
            subtitle = prefs.getString(KEY_SUBTITLE, null)
                ?: context.getString(CoreCommonR.string.widget_playback_idle_subtitle),
            status = context.getString(
                if (prefs.getBoolean(KEY_HAS_SONG, false)) CoreCommonR.string.widget_playback_paused
                else CoreCommonR.string.widget_playback_ready,
            ),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            elapsedText = prefs.getString(KEY_ELAPSED_TEXT, null) ?: "0:00",
            durationText = prefs.getString(KEY_DURATION_TEXT, null) ?: "0:00",
            progress = prefs.getInt(KEY_PROGRESS, 0)
                .coerceIn(0, PLAYBACK_WIDGET_PROGRESS_MAX),
            hasSong = prefs.getBoolean(KEY_HAS_SONG, false),
            // cached metadata cannot prove that a playback service is still running
            isPlaying = false,
            isFavorite = prefs.getBoolean(KEY_IS_FAVORITE, false),
            canToggleFavorite = prefs.getBoolean(KEY_CAN_TOGGLE_FAVORITE, false),
            isFloatingLyricsEnabled = prefs.getBoolean(KEY_FLOATING_LYRICS_ENABLED, false),
            artworkReady = prefs.getBoolean(KEY_ARTWORK_READY, false),
        )
    }

    private fun cachedArtworkFile(context: Context): File {
        return File(context.filesDir, ARTWORK_FILE_NAME)
    }

    private fun cachedArtworkTempFile(context: Context): File {
        return File(context.filesDir, ARTWORK_TEMP_FILE_NAME)
    }

    private fun prepareVisuals(
        context: Context,
        state: PlaybackWidgetState,
        artwork: Bitmap?,
    ): PlaybackWidgetVisuals {
        val darkTheme = isDarkTheme(context)
        if (lastDarkTheme != darkTheme) {
            lastArtworkInput = null
            lastWidgetVisuals = null
            lastDarkTheme = darkTheme
        }
        if (!shouldUseCachedPlaybackWidgetArtwork(state)) {
            val retainedVisuals = lastWidgetVisuals
            if (shouldRetainPlaybackWidgetVisuals(state) && retainedVisuals != null) {
                return retainedVisuals
            }
            lastArtworkInput = null
            lastWidgetVisuals = null
            return buildPlaybackWidgetVisuals(null, darkTheme)
        }
        if (artwork == null) {
            return lastWidgetVisuals ?: buildPlaybackWidgetVisuals(
                readCachedArtwork(context, state), darkTheme,
            ).also { lastWidgetVisuals = it }
        }
        if (artwork === lastArtworkInput) {
            return lastWidgetVisuals ?: buildPlaybackWidgetVisuals(artwork, darkTheme).also {
                lastWidgetVisuals = it
            }
        }
        val visuals = buildPlaybackWidgetVisuals(artwork, darkTheme)
        lastArtworkInput = artwork
        lastWidgetVisuals = visuals
        visuals.compactArtwork?.let { saveCachedArtwork(context, it) }
        return visuals
    }

    private fun saveCachedArtwork(context: Context, artwork: Bitmap) {
        val temporaryFile = cachedArtworkTempFile(context)
        runCatching {
            FileOutputStream(temporaryFile).use { output ->
                check(artwork.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            val targetFile = cachedArtworkFile(context)
            if (!temporaryFile.renameTo(targetFile)) {
                check(!targetFile.exists() || targetFile.delete())
                check(temporaryFile.renameTo(targetFile))
            }
        }.onFailure {
            temporaryFile.delete()
        }
    }

    private fun readCachedArtwork(
        context: Context,
        state: PlaybackWidgetState,
    ): Bitmap? {
        if (!shouldUseCachedPlaybackWidgetArtwork(state)) {
            return null
        }
        return runCatching {
            BitmapFactory.decodeFile(cachedArtworkFile(context).absolutePath)
        }.getOrNull()
    }

    private fun isDarkTheme(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

}
