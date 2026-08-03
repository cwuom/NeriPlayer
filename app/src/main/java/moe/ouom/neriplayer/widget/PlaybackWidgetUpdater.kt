package moe.ouom.neriplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.core.content.edit
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.core.logging.NPLogger
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

    fun updateFromPlaybackService(
        context: Context,
        state: PlaybackWidgetState,
        artwork: Bitmap?,
    ) {
        val visuals = prepareVisuals(context, state, artwork)
        saveState(context, state)
        updateAllInstalledWidgets(context, state, visuals)
    }

    fun updateStoredWidgets(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        @LayoutRes layoutRes: Int,
    ) {
        val state = readState(context)
        updateWidgets(
            context = context,
            appWidgetManager = appWidgetManager,
            appWidgetIds = appWidgetIds,
            layoutRes = layoutRes,
            state = state,
            visuals = buildPlaybackWidgetVisuals(readCachedArtwork(context, state)),
        )
    }

    internal fun hasInstalledWidgets(context: Context): Boolean {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        return appWidgetManager.getAppWidgetIds(
            ComponentName(context, NeriPlayerPlaybackWidgetProvider::class.java),
        ).isNotEmpty() || appWidgetManager.getAppWidgetIds(
            ComponentName(context, NeriPlayerCompactWidgetProvider::class.java),
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
    ) {
        if (appWidgetIds.isEmpty()) {
            return
        }
        val views = buildRemoteViews(context, layoutRes, state, visuals)
        try {
            appWidgetManager.updateAppWidget(appWidgetIds, views)
        } catch (error: RuntimeException) {
            NPLogger.w(
                "NERI-Widget",
                "Widget update failed with artwork; retrying without bitmap payload",
                error,
            )
            val fallbackViews = buildRemoteViews(
                context = context,
                layoutRes = layoutRes,
                state = state,
                visuals = PlaybackWidgetVisuals(
                    artwork = null,
                    compactArtwork = null,
                    themeBackground = null,
                    compactThemeBackground = null,
                    legacyThemeBackground = null,
                    legacyCompactThemeBackground = null,
                    primaryControl = null,
                ),
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

    private fun buildRemoteViews(
        context: Context,
        @LayoutRes layoutRes: Int,
        state: PlaybackWidgetState,
        visuals: PlaybackWidgetVisuals,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        val hasProgress = layoutRes == R.layout.widget_playback_4x2
        val controlReceiver = if (hasProgress) {
            NeriPlayerPlaybackWidgetProvider::class.java
        } else {
            NeriPlayerCompactWidgetProvider::class.java
        }
        views.setTextViewText(R.id.widget_title, state.title)
        views.setTextViewText(R.id.widget_subtitle, state.subtitle)
        views.setTextViewText(R.id.widget_status, state.status)
        if (hasProgress) {
            views.setTextViewText(R.id.widget_elapsed, state.elapsedText)
            views.setChronometer(
                R.id.widget_elapsed,
                SystemClock.elapsedRealtime() - state.positionMs.coerceAtLeast(0L),
                null,
                state.hasSong && state.isPlaying,
            )
            views.setTextViewText(R.id.widget_duration, state.durationText)
            views.setProgressBar(
                R.id.widget_progress,
                PLAYBACK_WIDGET_PROGRESS_MAX,
                state.progress,
                false,
            )
        }
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (state.isPlaying) R.drawable.round_pause_24 else R.drawable.round_play_arrow_24,
        )
        if (hasProgress) {
            views.setImageViewResource(
                R.id.widget_favorite,
                if (state.isFavorite) {
                    R.drawable.ic_baseline_favorite_24
                } else {
                    R.drawable.ic_outline_favorite_24
                },
            )
        }
        if (visuals.primaryControl != null) {
            views.setImageViewBitmap(R.id.widget_play_pause_background, visuals.primaryControl)
        } else {
            views.setImageViewResource(
                R.id.widget_play_pause_background,
                R.drawable.widget_primary_control_background,
            )
        }
        val themeBackground = selectPlaybackWidgetThemeBackground(
            visuals = visuals,
            hasProgress = hasProgress,
            sdkInt = Build.VERSION.SDK_INT,
        )
        views.setViewVisibility(
            R.id.widget_fallback_background,
            if (shouldShowPlaybackWidgetFallbackBackground(themeBackground != null)) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            },
        )
        if (themeBackground != null) {
            views.setImageViewBitmap(R.id.widget_theme_background, themeBackground)
            views.setViewVisibility(R.id.widget_theme_background, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_theme_background, android.view.View.GONE)
        }
        val albumArtwork = visuals.artwork
        if (albumArtwork != null) {
            views.setImageViewBitmap(R.id.widget_album_art, albumArtwork)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_neriplayer_round)
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
                action = AudioPlayerService.ACTION_TOGGLE_PLAY_PAUSE,
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
            context.getString(if (state.isPlaying) R.string.player_pause else R.string.player_play),
        )
        views.setContentDescription(
            R.id.widget_previous_touch,
            context.getString(R.string.player_previous),
        )
        views.setContentDescription(R.id.widget_next_touch, context.getString(R.string.player_next))
        if (hasProgress) {
            views.setContentDescription(
                R.id.widget_favorite_touch,
                context.getString(
                    if (state.isFavorite) R.string.favorite_remove else R.string.favorite_add,
                ),
            )
            views.setContentDescription(
                R.id.widget_floating_lyrics_touch,
                context.getString(
                    if (state.isFloatingLyricsEnabled) {
                        R.string.notification_hide_floating_lyrics
                    } else {
                        R.string.notification_show_floating_lyrics
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
                    R.drawable.ic_lyrics_off_24
                } else {
                    R.drawable.ic_lyrics_24
                },
            )
        }
        return views
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

    private fun enabledPlaybackPendingIntent(
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
        views.setFloat(viewId, "setAlpha", if (enabled) 1f else 0.5f)
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

    private fun readState(context: Context): PlaybackWidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TITLE)) {
            return PlaybackWidgetState.idle(context)
        }
        return PlaybackWidgetState(
            title = prefs.getString(KEY_TITLE, null) ?: context.getString(R.string.app_name),
            subtitle = prefs.getString(KEY_SUBTITLE, null)
                ?: context.getString(R.string.widget_playback_idle_subtitle),
            status = prefs.getString(KEY_STATUS, null)
                ?: context.getString(R.string.widget_playback_ready),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L),
            elapsedText = prefs.getString(KEY_ELAPSED_TEXT, null) ?: "0:00",
            durationText = prefs.getString(KEY_DURATION_TEXT, null) ?: "0:00",
            progress = prefs.getInt(KEY_PROGRESS, 0)
                .coerceIn(0, PLAYBACK_WIDGET_PROGRESS_MAX),
            hasSong = prefs.getBoolean(KEY_HAS_SONG, false),
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
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
        if (!shouldUseCachedPlaybackWidgetArtwork(state)) {
            val retainedVisuals = lastWidgetVisuals
            if (shouldRetainPlaybackWidgetVisuals(state) && retainedVisuals != null) {
                return retainedVisuals
            }
            lastArtworkInput = null
            lastWidgetVisuals = null
            return buildPlaybackWidgetVisuals(null)
        }
        if (artwork == null) {
            return lastWidgetVisuals ?: buildPlaybackWidgetVisuals(
                readCachedArtwork(context, state),
            ).also { lastWidgetVisuals = it }
        }
        if (artwork === lastArtworkInput) {
            return lastWidgetVisuals ?: buildPlaybackWidgetVisuals(artwork).also {
                lastWidgetVisuals = it
            }
        }
        val visuals = buildPlaybackWidgetVisuals(artwork)
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

}
