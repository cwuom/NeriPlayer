package moe.ouom.neriplayer.widget

import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PlaybackWidgetRemoteViewsTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fullWidgetCentersTheSameCardInAnOversizedHost() {
        val size = PlaybackWidgetSize(widthDp = 344, heightDp = 190)
        val root = applyRemoteViews(R.layout.widget_playback_4x2, size)
        val spec = playbackWidgetLayoutSpec(size, hasProgress = true)

        val card = root.findViewById<View>(R.id.widget_card)
        val album = root.findViewById<View>(R.id.widget_album_shell)
        val mainContent = root.findViewById<View>(R.id.widget_main_content)
        val rightContent = root.findViewById<View>(R.id.widget_content)
        val progressRow = root.findViewById<View>(R.id.widget_progress_row)
        val controls = root.findViewById<View>(R.id.widget_controls)
        assertNotNull(card)
        assertNotNull(album)
        assertNotNull(mainContent)
        assertNotNull(rightContent)
        assertNotNull(progressRow)
        assertNotNull(controls)
        val controlParent = controls.parent as ViewGroup
        assertEquals(dpToPx(spec.cardHeightDp), card.layoutParams.height)
        assertEquals((root.height - card.height) / 2, card.top)
        assertEquals(dpToPx(spec.albumSizeDp), album.layoutParams.width)
        assertEquals(dpToPx(spec.albumSizeDp), album.layoutParams.height)
        assertEquals(dpToPx(spec.horizontalPaddingDp), mainContent.paddingLeft)
        assertEquals(dpToPx(spec.topPaddingDp), mainContent.paddingTop)
        assertEquals(dpToPx(spec.bottomPaddingDp), mainContent.paddingBottom)
        assertEquals(dpToPx(spec.controlsHeightDp), controls.layoutParams.height)
        assertTrue(spec.usesFullWidthControls)
        assertEquals(mainContent, controlParent)
        assertTrue(controlParent is RelativeLayout)
        assertEquals(mainContent, progressRow.parent)
        assertTrue(progressRow.width > rightContent.width)
        assertTrue(progressRow.top < controls.top)
        assertTrue(progressRow.bottom <= controls.top)
        assertFullWidthControlSlots(controls)
    }

    @Test
    fun fullWidgetUsesTheCompactLayoutAtItsDefaultTwoRowHeight() {
        val root = applyRemoteViews(
            layoutRes = R.layout.widget_playback_4x2,
            size = PlaybackWidgetSize(widthDp = 250, heightDp = 110),
        )
        val album = root.findViewById<View>(R.id.widget_album_shell)
        val mainContent = root.findViewById<View>(R.id.widget_main_content)
        val infoArea = root.findViewById<View>(R.id.widget_info_area)
        val rightContent = root.findViewById<View>(R.id.widget_content)
        val progressRow = root.findViewById<View>(R.id.widget_progress_row)
        val controls = root.findViewById<View>(R.id.widget_controls)

        assertNotNull(album)
        assertNotNull(mainContent)
        assertNotNull(infoArea)
        assertNotNull(rightContent)
        assertNotNull(progressRow)
        assertNotNull(controls)
        assertEquals(root.height, mainContent.height)
        assertTrue(album.bottom <= controls.top)
        assertTrue(progressRow.bottom <= controls.top)
        assertTrue(controls.top - progressRow.bottom >= dpToPx(5))
        assertPixelsClose(
            (rightContent.top + rightContent.bottom) / 2,
            (album.top + album.bottom) / 2,
        )
        assertTrue(controls.bottom <= root.height)
        assertEquals(mainContent, controls.parent)
        assertEquals(infoArea, rightContent.parent)
        assertEquals(infoArea, progressRow.parent)
    }

    @Test
    fun fullWidgetRebalancesCoverAndLiftsProgressInATallCompactHost() {
        val root = applyRemoteViews(
            layoutRes = R.layout.widget_playback_4x2,
            size = PlaybackWidgetSize(widthDp = 250, heightDp = 161),
        )
        val album = root.findViewById<View>(R.id.widget_album_shell)
        val rightContent = root.findViewById<View>(R.id.widget_content)
        val progressRow = root.findViewById<View>(R.id.widget_progress_row)
        val controls = root.findViewById<View>(R.id.widget_controls)

        assertNotNull(album)
        assertNotNull(rightContent)
        assertNotNull(progressRow)
        assertNotNull(controls)
        assertTrue(album.top > dpToPx(6))
        assertTrue(album.bottom <= controls.top)
        assertPixelsClose(
            (rightContent.top + rightContent.bottom) / 2,
            (album.top + album.bottom) / 2,
        )
        assertTrue(controls.top - progressRow.bottom >= dpToPx(5))
    }

    @Test
    fun fullWidgetRefreshResetsElapsedClockAfterAnAutomaticSkip() {
        val size = PlaybackWidgetSize(widthDp = 250, heightDp = 110)
        val beforeSkip = playingWidgetState(positionMs = 5_000L)
        val afterSkip = playingWidgetState(positionMs = 75_000L)
        val root = applyRemoteViews(
            layoutRes = R.layout.widget_playback_4x2,
            size = size,
            state = beforeSkip,
        )
        val elapsed = root.findViewById<Chronometer>(R.id.widget_elapsed)
        val progress = root.findViewById<ProgressBar>(R.id.widget_progress)
        assertNotNull(elapsed)
        assertNotNull(progress)
        val previousBase = elapsed.base

        val beforeRefresh = android.os.SystemClock.elapsedRealtime()
        PlaybackWidgetUpdater.buildRemoteViews(
            context = context,
            layoutRes = R.layout.widget_playback_4x2,
            state = afterSkip,
            visuals = buildPlaybackWidgetVisuals(null),
            size = size,
        ).reapply(context, root)
        val afterRefresh = android.os.SystemClock.elapsedRealtime()

        assertEquals(afterSkip.progress, progress.progress)
        assertEquals(afterSkip.elapsedText, elapsed.text.toString())
        assertTrue(elapsed.base < previousBase - 60_000L)
        assertTrue(
            "Chronometer base was ${elapsed.base}",
            elapsed.base in (beforeRefresh - afterSkip.positionMs - 1_000L)..
                (afterRefresh - afterSkip.positionMs + 1_000L),
        )
    }

    @Test
    fun fullWidgetKeepsTheReferenceAspectRatioOnDifferentHosts() {
        val compactHost = PlaybackWidgetSize(widthDp = 250, heightDp = 180)
        val tallHost = PlaybackWidgetSize(widthDp = 420, heightDp = 240)

        val compactCard = applyRemoteViews(
            R.layout.widget_playback_4x2,
            compactHost,
        ).findViewById<View>(R.id.widget_card)
        val tallCard = applyRemoteViews(
            R.layout.widget_playback_4x2,
            tallHost,
        ).findViewById<View>(R.id.widget_card)

        assertEquals(
            dpToPx(playbackWidgetLayoutSpec(compactHost, hasProgress = true).cardHeightDp),
            compactCard.height,
        )
        assertEquals(
            dpToPx(playbackWidgetLayoutSpec(tallHost, hasProgress = true).cardHeightDp),
            tallCard.height,
        )
        assertTrue(tallCard.height > compactCard.height)
    }

    @Test
    fun adaptiveCompactWidgetRemoteViewsApplyAtLargeSize() {
        val size = PlaybackWidgetSize(widthDp = 300, heightDp = 200)
        val root = applyRemoteViews(R.layout.widget_playback_2x2, size)
        val controls = root.findViewById<View>(R.id.widget_controls)
        val songInfo = root.findViewById<View>(R.id.widget_compact_song_info)

        assertNotNull(controls)
        assertNotNull(songInfo)
        val spec = playbackWidgetLayoutSpec(size, hasProgress = false)
        val controlsMargins = controls.layoutParams as ViewGroup.MarginLayoutParams
        val songInfoMargins = songInfo.layoutParams as ViewGroup.MarginLayoutParams
        assertEquals(dpToPx(spec.controlsHeightDp), controls.layoutParams.height)
        assertEquals(
            dpToPx(spec.horizontalPaddingDp),
            songInfo.paddingLeft + songInfoMargins.marginStart,
        )
        assertPixelsClose(
            dpToPx(spec.compactInfoTopPaddingDp),
            songInfo.paddingTop + songInfoMargins.topMargin,
        )
        assertEquals(songInfoMargins.marginStart, controlsMargins.marginStart)
        assertEquals(songInfoMargins.marginEnd, controlsMargins.marginEnd)
        assertEquals(dpToPx(spec.compactControlBottomMarginDp), controlsMargins.bottomMargin)
    }

    @Test
    fun fullWidgetUsesTheSameCardAtMinimumHostSize() {
        val root = applyRemoteViews(
            layoutRes = R.layout.widget_playback_4x2,
            size = PlaybackWidgetSize(widthDp = 250, heightDp = 180),
        )
        val album = root.findViewById<View>(R.id.widget_album_shell)
        val card = root.findViewById<View>(R.id.widget_card)
        val controls = root.findViewById<View>(R.id.widget_controls)
        val rightContent = root.findViewById<View>(R.id.widget_content)
        val progressRow = root.findViewById<View>(R.id.widget_progress_row)
        val mainContent = root.findViewById<View>(R.id.widget_main_content)

        assertEquals(dpToPx(170), card.layoutParams.height)
        assertEquals((root.height - card.height) / 2, card.top)
        assertEquals(mainContent, controls.parent)
        assertEquals(mainContent, progressRow.parent)
        assertTrue(progressRow.width > rightContent.width)
        assertTrue(album.bottom <= controls.top)
        assertTrue(progressRow.bottom <= controls.top)
        assertFullWidthControlSlots(controls)
    }

    private fun applyRemoteViews(
        layoutRes: Int,
        size: PlaybackWidgetSize,
        state: PlaybackWidgetState = PlaybackWidgetState.idle(context),
    ): View {
        val views = PlaybackWidgetUpdater.buildRemoteViews(
            context = context,
            layoutRes = layoutRes,
            state = state,
            visuals = buildPlaybackWidgetVisuals(null),
            size = size,
        )
        return views.apply(context, FrameLayout(context)).also { root ->
            val width = dpToPx(size.widthDp)
            val height = dpToPx(size.heightDp)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
    }

    private fun playingWidgetState(positionMs: Long): PlaybackWidgetState {
        return buildPlaybackWidgetState(
            title = "Wall",
            subtitle = "Jing Guo",
            status = "Playing",
            positionMs = positionMs,
            durationMs = 180_000L,
            hasSong = true,
            isPlaying = true,
            isFavorite = false,
            canToggleFavorite = true,
            isFloatingLyricsEnabled = false,
            artworkReady = true,
            contentId = "song-a",
            coverId = "cover-a",
        )
    }

    private fun dpToPx(value: Int): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }

    private fun assertFullWidthControlSlots(controls: View) {
        val container = controls as ViewGroup
        assertEquals(5, container.childCount)
        val slotWidths = (0 until container.childCount).map { index ->
            container.getChildAt(index).width
        }
        assertTrue(slotWidths.all { it > 0 })
        assertTrue(slotWidths.max() - slotWidths.min() <= 1)
        assertEquals(controls.width, slotWidths.sum())
    }

    private fun assertPixelsClose(expected: Int, actual: Int) {
        assertTrue(
            "Expected $expected px but was $actual px",
            abs(expected - actual) <= 1,
        )
    }
}
