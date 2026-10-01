package moe.ouom.neriplayer.widget

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.core.player.presentation.widget.PlaybackWidgetState
import moe.ouom.neriplayer.core.player.presentation.widget.buildPlaybackWidgetState
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PlaybackWidgetRemoteViewsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun legacyLauncherBoundsKeepPortraitAndLandscapeOnModernAndroid() {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 344)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
        }
        assertEquals(listOf(PlaybackWidgetSize(250, 180), PlaybackWidgetSize(344, 110)),
            playbackWidgetSizeVariantsFromOptions(options, hasProgress = true))
    }

    @Test
    @androidx.test.filters.SdkSuppress(minSdkVersion = 31)
    fun responsiveHostSwitchesShapeAndRefreshesProgressAfterResize() {
        val provider = ComponentName(context, NeriPlayerPlaybackWidgetProvider::class.java)
        val info = AppWidgetManager.getInstance(context).installedProviders.first { it.provider == provider }
        instrumentation.runOnMainSync {
            val sizes = listOf(PlaybackWidgetSize(250, 110), PlaybackWidgetSize(344, 180))
            val host = AppWidgetHostView(context).apply {
                setAppWidget(424242, info)
                setPadding(0, 0, 0, 0)
            }
            fun layout(size: PlaybackWidgetSize) {
                host.measure(View.MeasureSpec.makeMeasureSpec(dp(context, size.widthDp), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(context, size.heightDp), View.MeasureSpec.EXACTLY))
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
            }
            fun update(position: Long) = host.updateAppWidget(parcelled(PlaybackWidgetUpdater.buildRemoteViewsForSizes(
                context, R.layout.widget_playback_4x2, state(position), buildPlaybackWidgetVisuals(artwork()), sizes)))
            update(5_000)
            layout(sizes.first())
            assertEquals(View.GONE, host.findViewById<View>(R.id.widget_status).visibility)
            layout(sizes.last())
            assertEquals(View.VISIBLE, host.findViewById<View>(R.id.widget_status).visibility)
            update(75_000)
            layout(sizes.last())
            assertEquals(416, host.findViewById<ProgressBar>(R.id.widget_progress).progress)
            assertEquals("1:15", host.findViewById<Chronometer>(R.id.widget_elapsed).text.toString())
        }
    }

    @Test
    fun everyShapeAppliesAfterParcelRoundTripAndReappliesWithoutHostErrors() {
        val shapes = listOf(
            R.layout.widget_playback_4x1 to PlaybackWidgetSize(250, 56),
            R.layout.widget_playback_4x1 to PlaybackWidgetSize(340, 56),
            R.layout.widget_playback_2x2 to PlaybackWidgetSize(110, 110),
            R.layout.widget_playback_2x2 to PlaybackWidgetSize(160, 160),
            R.layout.widget_playback_2x2 to PlaybackWidgetSize(300, 240),
            R.layout.widget_playback_4x2 to PlaybackWidgetSize(250, 110),
            R.layout.widget_playback_4x2 to PlaybackWidgetSize(344, 180),
            R.layout.widget_playback_4x2 to PlaybackWidgetSize(420, 240),
            R.layout.widget_playback_4x2 to PlaybackWidgetSize(250, 56),
            R.layout.widget_playback_4x2 to PlaybackWidgetSize(250, 100),
            R.layout.widget_playback_4x2 to PlaybackWidgetSize(250, 109),
        )
        instrumentation.runOnMainSync {
            shapes.forEach { (layout, size) ->
                val root = applyRemoteViews(context, layout, size, PlaybackWidgetState.idle(context))
                assertFalse(root.findViewById<View>(R.id.widget_play_pause_touch).isEnabled)
                val playing = state(75_000)
                parcelled(views(context, layout, size, playing)).reapply(context, root)
                assertTrue(root.findViewById<View>(R.id.widget_play_pause_touch).isEnabled)
                assertEquals(playing.title, root.findViewById<TextView>(R.id.widget_title).text.toString())
                assertEquals(context.getString(CommonR.string.player_pause),
                    root.findViewById<View>(R.id.widget_play_pause_touch).contentDescription.toString())
                listOf(R.id.widget_previous_touch, R.id.widget_play_pause_touch, R.id.widget_next_touch,
                    R.id.widget_favorite_touch, R.id.widget_floating_lyrics_touch).forEach { id ->
                    assertFalse("Widget controls should not have a ripple",
                        root.findViewById<View>(id)?.background is RippleDrawable)
                }
                assertGeometry(root)
            }
        }
    }

    @Test
    fun cardsFillTheHostAndKeepCoverTextProgressAndControlsSeparate() {
        instrumentation.runOnMainSync {
            listOf(PlaybackWidgetSize(250, 110), PlaybackWidgetSize(340, 130), PlaybackWidgetSize(340, 160),
                PlaybackWidgetSize(344, 180), PlaybackWidgetSize(420, 240))
                .forEach { size ->
                    val root = applyRemoteViews(context, R.layout.widget_playback_4x2, size, state(5_000))
                    val content = root.findViewById<View>(R.id.widget_main_content)
                    assertEquals(root.height, content.height)
                    assertEquals(root.width, content.width)
                    val album = bounds(root, R.id.widget_album_shell)
                    val info = bounds(root, R.id.widget_song_info)
                    val progress = bounds(root, R.id.widget_progress_row)
                    val controls = bounds(root, R.id.widget_controls)
                    assertTrue(album.right <= info.left)
                    val expanded = size.heightDp >= 180
                    assertTrue("Cover needs breathing room above progress",
                        album.bottom + dp(context, if (expanded) 12 else 4) <= progress.top)
                    assertTrue("Progress needs breathing room above controls",
                        progress.bottom + dp(context, if (expanded) 8 else 4) <= controls.top)
                    assertEquals("Controls should stay at the bottom after resizing",
                        root.height - content.paddingBottom, controls.bottom)
                    assertEquals(dp(context, if (expanded) 12 else 8), content.paddingTop)
                    assertTrue("Artwork needs padding above it", album.top >= content.paddingTop)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        assertTrue("Artwork should remain proportional to the card",
                            album.width() <= dp(context, minOf(80,
                                minOf(size.widthDp * 0.25f, size.heightDp * 0.40f).roundToInt())))
                    }
                    assertGeometry(root)
                }
        }
    }

    @Test
    fun responsivePaddingMatchesXmlDimensionsOnFractionalDensityScreens() {
        val config = Configuration(context.resources.configuration).apply { densityDpi = 420 }
        val fractionalContext = context.createConfigurationContext(config)
        assertEquals(2.625f, fractionalContext.resources.displayMetrics.density, 0f)
        instrumentation.runOnMainSync {
            listOf(
                R.layout.widget_playback_4x2_expanded to PlaybackWidgetSize(344, 180),
                R.layout.widget_playback_4x2 to PlaybackWidgetSize(250, 110),
                R.layout.widget_playback_4x1 to PlaybackWidgetSize(340, 64),
            ).forEach { (layout, size) ->
                val reference = RemoteViews(fractionalContext.packageName, layout)
                    .apply(fractionalContext, FrameLayout(fractionalContext))
                    .findViewById<View>(R.id.widget_main_content)
                val root = applyRemoteViews(fractionalContext, layout, size, state(5_000))
                val content = root.findViewById<View>(R.id.widget_main_content)
                assertEquals(reference.paddingLeft, content.paddingLeft)
                assertEquals(reference.paddingTop, content.paddingTop)
                assertEquals(reference.paddingRight, content.paddingRight)
                assertEquals(reference.paddingBottom, content.paddingBottom)
                val controls = bounds(root, R.id.widget_controls)
                if (layout == R.layout.widget_playback_4x1) {
                    assertEquals((content.paddingTop + root.height - content.paddingBottom) / 2,
                        controls.centerY())
                } else {
                    assertEquals(root.height - content.paddingBottom, controls.bottom)
                }
                assertGeometry(root)
            }
        }
    }

    @Test
    fun shortCardsKeepCoverTextAndControlsSeparateWithoutAProgressRow() {
        instrumentation.runOnMainSync {
            listOf(PlaybackWidgetSize(250, 100), PlaybackWidgetSize(250, 109)).forEach { size ->
                val root = applyRemoteViews(context, R.layout.widget_playback_4x2, size, state(0))
                assertEquals(null, root.findViewById<View>(R.id.widget_progress_row))
                val album = bounds(root, R.id.widget_album_shell)
                val info = bounds(root, R.id.widget_song_info)
                val controls = bounds(root, R.id.widget_controls)
                assertTrue(album.right <= info.left)
                assertTrue(info.right <= controls.left)
                assertGeometry(root)
            }
        }
    }

    @Test
    fun renderedCardsKeepTransparentCornersWithArtworkAndFallbacks() {
        instrumentation.runOnMainSync {
            listOf(R.layout.widget_playback_4x1 to PlaybackWidgetSize(340, 64),
                R.layout.widget_playback_2x2 to PlaybackWidgetSize(180, 180),
                R.layout.widget_playback_4x2 to PlaybackWidgetSize(340, 160)).forEach { (layout, size) ->
                listOf(artwork(), null).forEach { cover ->
                    val root = parcelled(PlaybackWidgetUpdater.buildRemoteViews(context, layout, state(0),
                        buildPlaybackWidgetVisuals(cover), size)).apply(context, FrameLayout(context))
                    root.measure(View.MeasureSpec.makeMeasureSpec(dp(context, size.widthDp), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dp(context, size.heightDp), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        listOf(0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1,
                            bitmap.width - 1 to bitmap.height - 1).forEach { (x, y) ->
                            assertEquals("Card layers must preserve transparent corners", 0,
                                Color.alpha(bitmap.getPixel(x, y)))
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    @Test
    fun oversizedBitmapFallbackUsesOnlyResourceDrawables() {
        instrumentation.runOnMainSync {
            val size = PlaybackWidgetSize(340, 180)
            val visuals = buildPlaybackWidgetVisuals(artwork())
            listOf(R.layout.widget_playback_4x1, R.layout.widget_playback_2x2,
                R.layout.widget_playback_4x2).forEach { layout ->
                val root = parcelled(PlaybackWidgetUpdater.buildRemoteViews(context, layout, state(0),
                    visuals, size, includeBitmapPayload = false)).apply(context, FrameLayout(context))
                listOf(R.id.widget_theme_background, R.id.widget_album_art, R.id.widget_play_pause_background)
                    .forEach { id ->
                        assertFalse("Fallback must not allocate widget bitmaps",
                            root.findViewById<ImageView>(id).drawable is BitmapDrawable)
                    }
                if (layout == R.layout.widget_playback_2x2) {
                    assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_album_scrim).visibility)
                }
            }
            val sizes = (0 until 8).map { PlaybackWidgetSize(160 + it * 10, 160 + it * 10) }
            val parcel = Parcel.obtain()
            try {
                PlaybackWidgetUpdater.buildRemoteViewsForSizes(context, R.layout.widget_playback_2x2,
                    state(0), visuals, sizes, includeBitmapPayload = false).writeToParcel(parcel, 0)
                assertTrue("Fallback parcel must stay small with eight size variants", parcel.dataSize() < 100_000)
            } finally { parcel.recycle() }
        }
    }

    @Test
    fun miniArtworkEdgesDoNotBlendWithTheLightThemeSurface() {
        instrumentation.runOnMainSync {
            val size = PlaybackWidgetSize(180, 180)
            val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            val root = parcelled(PlaybackWidgetUpdater.buildRemoteViews(context, R.layout.widget_playback_2x2,
                state(0), buildPlaybackWidgetVisuals(cover), size)).apply(context, FrameLayout(context))
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(context, size.widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(context, size.heightDp), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_theme_background).visibility)
                for (y in 0 until dp(context, 24)) {
                    for (x in 0 until dp(context, 24)) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.alpha(pixel) > 0) {
                            assertEquals("Dark artwork must not gain a pale corner fringe", 0,
                                Color.red(pixel) + Color.green(pixel) + Color.blue(pixel))
                        }
                    }
                }
            } finally {
                bitmap.recycle()
                cover.recycle()
            }
        }
    }

    @Test
    fun miniCardsKeepArtworkAsTheBackdropWithAllPlaybackControls() {
        instrumentation.runOnMainSync {
            listOf(1f, 1.5f).forEach { fontScale ->
                val configuration = Configuration(context.resources.configuration).apply { this.fontScale = fontScale }
                val ctx = context.createConfigurationContext(configuration)
                val sizes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(PlaybackWidgetSize(160, 160), PlaybackWidgetSize(180, 180))
                } else {
                    listOf(PlaybackWidgetSize(180, 180))
                }
                sizes.forEach { size ->
                    val root = applyRemoteViews(ctx, R.layout.widget_playback_2x2, size, state(0))
                    val album = bounds(root, R.id.widget_album_shell)
                    val title = bounds(root, R.id.widget_title)
                    val subtitle = bounds(root, R.id.widget_subtitle)
                    val controls = bounds(root, R.id.widget_controls)
                    assertEquals(Rect(0, 0, root.width, root.height), album)
                    val info = bounds(root, R.id.widget_song_info)
                    val metadataBottom = if (root.findViewById<View>(R.id.widget_subtitle).visibility == View.VISIBLE) {
                        subtitle.bottom
                    } else title.bottom
                    assertTrue("Mini metadata should stay centered above its controls",
                        kotlin.math.abs((title.top + metadataBottom) - (info.top + info.bottom)) <= dp(ctx, 2))
                    if (root.findViewById<View>(R.id.widget_subtitle).visibility == View.VISIBLE) {
                        assertTrue(title.bottom <= subtitle.top)
                        assertTrue(subtitle.bottom <= controls.top)
                    } else {
                        assertTrue(title.bottom <= controls.top)
                    }
                    assertTrue(title.width() >= root.width - dp(ctx, 40))
                    listOf(R.id.widget_previous_touch, R.id.widget_play_pause_touch, R.id.widget_next_touch)
                        .forEach { id -> assertEquals(View.VISIBLE, root.findViewById<View>(id).visibility) }
                    assertGeometry(root)
                }
            }
        }
    }

    @Test
    fun narrowShapesKeepAllThreePlaybackControls() {
        instrumentation.runOnMainSync {
            listOf(R.layout.widget_playback_4x1 to PlaybackWidgetSize(250, 56),
                R.layout.widget_playback_2x2 to PlaybackWidgetSize(110, 110)).forEach { (layout, size) ->
                val root = applyRemoteViews(context, layout, size, state(0))
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_previous_touch).visibility)
                listOf(R.id.widget_previous_touch, R.id.widget_play_pause_touch, R.id.widget_next_touch).forEach { id ->
                    assertTrue(root.findViewById<View>(id).width >= dp(context, if (layout == R.layout.widget_playback_4x1) 44 else 32))
                    assertTrue(root.findViewById<View>(id).height >= dp(context, 44))
                }
            }
        }
    }

    @Test
    fun fullCardsUseSmallerIconsWithoutShrinkingTheirTouchTargets() {
        instrumentation.runOnMainSync {
            val root = applyRemoteViews(context, R.layout.widget_playback_4x2, PlaybackWidgetSize(340, 180), state(0))
            listOf(R.id.widget_previous, R.id.widget_next, R.id.widget_favorite, R.id.widget_floating_lyrics)
                .forEach { id ->
                    val icon = root.findViewById<View>(id)
                    assertEquals(dp(context, 24), icon.width)
                    assertEquals(0, icon.paddingLeft + icon.paddingRight)
                    assertTrue((icon.parent as View).height >= dp(context, 44))
                }
            assertEquals(dp(context, 36), root.findViewById<View>(R.id.widget_play_pause_background).width)
            assertEquals(dp(context, 22), root.findViewById<View>(R.id.widget_play_pause).width)
        }
    }

    @Test
    fun largeTextAndRightToLeftStillFitSmallHosts() {
        val configuration = Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
            setLayoutDirection(java.util.Locale.forLanguageTag("ar"))
        }
        val largeTextContext = context.createConfigurationContext(configuration)
        instrumentation.runOnMainSync {
            listOf(R.layout.widget_playback_4x1 to PlaybackWidgetSize(250, 56),
                R.layout.widget_playback_2x2 to PlaybackWidgetSize(110, 110),
                R.layout.widget_playback_4x2 to PlaybackWidgetSize(250, 110)).forEach { (layout, size) ->
                val root = applyRemoteViews(largeTextContext, layout, size, state(0))
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_subtitle).visibility)
                assertGeometry(root)
            }
        }
    }

    @Test
    fun fullAndPartialUpdatesResetElapsedClockAfterSeek() {
        instrumentation.runOnMainSync {
            val size = PlaybackWidgetSize(250, 110)
            val root = applyRemoteViews(context, R.layout.widget_playback_4x2, size, state(5_000))
            val elapsed = root.findViewById<Chronometer>(R.id.widget_elapsed)
            val previousBase = elapsed.base
            val afterSkip = state(75_000)
            val before = SystemClock.elapsedRealtime()
            parcelled(PlaybackWidgetUpdater.buildPlaybackWidgetProgressRemoteViews(
                context, R.layout.widget_playback_4x2, afterSkip)).reapply(context, root)
            val after = SystemClock.elapsedRealtime()
            assertEquals(afterSkip.progress, root.findViewById<ProgressBar>(R.id.widget_progress).progress)
            assertEquals(afterSkip.elapsedText, elapsed.text.toString())
            assertTrue(elapsed.base < previousBase - 60_000)
            assertTrue(elapsed.base in (before - 75_000)..(after - 75_000))
            val paused = afterSkip.copy(isPlaying = false, showPauseAction = false)
            parcelled(views(context, R.layout.widget_playback_4x2, size, paused)).reapply(context, root)
            assertEquals(context.getString(CommonR.string.player_play),
                root.findViewById<View>(R.id.widget_play_pause_touch).contentDescription.toString())
        }
    }

    @Test
    fun playAndPauseKeepSeparatePendingIntentIdentitiesAcrossUpdates() {
        val fallback = PendingIntent.getActivity(context, 9997, Intent("widget.test.OPEN").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun control(action: String) = PlaybackWidgetUpdater.enabledPlaybackPendingIntent(
            context, action, 9998, true, fallback, NeriPlayerStripWidgetProvider::class.java)
        val play = control(AudioPlayerService.ACTION_PLAY)
        val pause = control(AudioPlayerService.ACTION_PAUSE)
        try {
            assertNotEquals(play, pause)
            assertEquals(play, control(AudioPlayerService.ACTION_PLAY))
            assertEquals(pause, control(AudioPlayerService.ACTION_PAUSE))
        } finally {
            play.cancel()
            pause.cancel()
            fallback.cancel()
        }
    }

    @Test
    fun absentServiceNeverReplaysCachedPlayingState() {
        val prefs = context.getSharedPreferences("neriplayer_playback_widget", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("title", "Remembered song").putBoolean("has_song", true)
                .putBoolean("is_playing", true).putString("status", "Playing")
                .putLong("position_ms", 5_000).commit()
            val cached = PlaybackWidgetUpdater.readState(context)
            assertFalse(cached.isPlaying)
            assertFalse(cached.showPauseAction)
            assertEquals(context.getString(CommonR.string.widget_playback_paused), cached.status)
            assertEquals("Remembered song", cached.title)
            assertEquals(5_000L, cached.positionMs)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun renderCurrentWidgetPreviews() {
        instrumentation.runOnMainSync {
            if (InstrumentationRegistry.getArguments().getString("publish_widget_previews") == "true") {
                val manager = AppWidgetManager.getInstance(context)
                listOf(NeriPlayerPlaybackWidgetProvider::class.java, NeriPlayerCompactWidgetProvider::class.java,
                    NeriPlayerStripWidgetProvider::class.java).forEach { provider ->
                    manager.getAppWidgetIds(ComponentName(context, provider)).forEach { id ->
                        println("Launcher widget $id (${provider.simpleName}): ${manager.getAppWidgetOptions(id)}")
                    }
                }
                PlaybackWidgetUpdater.updateFromPlaybackService(context,
                    state(75_000).copy(isPlaying = false, showPauseAction = false, status = "已暂停"), artwork())
            }
            val folder = File(context.getExternalFilesDir(null), "widget-previews").apply { mkdirs() }
            listOf(Triple("4x1", R.layout.widget_playback_4x1, PlaybackWidgetSize(340, 64)),
                Triple("2x2", R.layout.widget_playback_2x2, PlaybackWidgetSize(180, 180)),
                Triple("4x2-short", R.layout.widget_playback_4x2, PlaybackWidgetSize(340, 160)),
                Triple("4x2", R.layout.widget_playback_4x2, PlaybackWidgetSize(340, 180))).forEach { (name, layout, size) ->
                listOf(false, true).forEach { dark ->
                    val config = Configuration(context.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val themed = context.createConfigurationContext(config)
                    val root = applyRemoteViews(themed, layout, size, state(75_000))
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(folder, "widget-$name-${if (dark) "dark" else "light"}.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            }
        }
    }

    private fun views(ctx: Context, layout: Int, size: PlaybackWidgetSize, state: PlaybackWidgetState): RemoteViews =
        PlaybackWidgetUpdater.buildRemoteViews(ctx, layout, state,
            buildPlaybackWidgetVisuals(artwork(), ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES), size)

    private fun applyRemoteViews(ctx: Context, layout: Int, size: PlaybackWidgetSize, state: PlaybackWidgetState): ViewGroup =
        (parcelled(views(ctx, layout, size, state)).apply(ctx, FrameLayout(ctx)) as ViewGroup).also { root ->
            root.measure(View.MeasureSpec.makeMeasureSpec(dp(ctx, size.widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(ctx, size.heightDp), View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }

    private fun parcelled(views: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    private fun bounds(root: ViewGroup, id: Int): Rect {
        val view = root.findViewById<View>(id)
        return Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    }

    private fun assertGeometry(root: ViewGroup) {
        listOf(R.id.widget_album_shell, R.id.widget_title, R.id.widget_controls,
            R.id.widget_play_pause_touch, R.id.widget_next_touch).forEach { id ->
            val rect = bounds(root, id)
            assertTrue("View $id has no space: $rect", rect.width() > 0 && rect.height() > 0)
            assertTrue("View $id leaves its host: $rect / ${root.width}x${root.height}",
                rect.left >= 0 && rect.top >= 0 && rect.right <= root.width && rect.bottom <= root.height)
        }
    }

    private fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).roundToInt()

    private fun state(position: Long): PlaybackWidgetState = buildPlaybackWidgetState(
        title = "夜空与城市的呼吸", subtitle = "NeriPlayer · Evening Sessions", status = "正在播放",
        positionMs = position, durationMs = 180_000, hasSong = true, isPlaying = true,
        isFavorite = true, canToggleFavorite = true, isFloatingLyricsEnabled = false, artworkReady = true,
    )

    private fun artwork(): Bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { image ->
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 192f, 192f, Color.rgb(69, 123, 128), Color.rgb(22, 40, 78), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, 192f, 192f, paint)
        paint.shader = null
        paint.color = Color.rgb(240, 189, 136)
        canvas.drawCircle(130f, 68f, 38f, paint)
        paint.color = Color.rgb(17, 50, 65)
        canvas.drawRect(0f, 140f, 192f, 192f, paint)
    }
}
