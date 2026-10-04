package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriTabletMiniPlayerPolicyTest {
    @Test
    fun contentWidthChoosesEveryLayoutAtItsExactBoundary() {
        val cases = listOf(
            0.dp to TabletMiniPlayerLayout.Minimal,
            479.dp to TabletMiniPlayerLayout.Minimal,
            480.dp to TabletMiniPlayerLayout.Overflow,
            599.dp to TabletMiniPlayerLayout.Overflow,
            600.dp to TabletMiniPlayerLayout.Compact,
            839.dp to TabletMiniPlayerLayout.Compact,
            840.dp to TabletMiniPlayerLayout.Full,
            1280.dp to TabletMiniPlayerLayout.Full
        )
        cases.forEach { (width, expected) -> assertEquals("width=$width", expected, tabletMiniPlayerLayout(width)) }
    }

    @Test
    fun unusableContentWidthKeepsTheMinimalFallback() {
        assertEquals(TabletMiniPlayerLayout.Minimal, tabletMiniPlayerLayout((-1).dp))
        assertEquals(TabletMiniPlayerLayout.Minimal, tabletMiniPlayerLayout(Dp.Unspecified))
    }

    @Test
    fun progressIsClampedAndUnknownDurationStartsAtZero() {
        assertEquals(0.25f, miniPlayerProgress(30_000L, 120_000L), 0f)
        assertEquals(0f, miniPlayerProgress(-1L, 120_000L), 0f)
        assertEquals(1f, miniPlayerProgress(240_000L, 120_000L), 0f)
        assertEquals(0f, miniPlayerProgress(30_000L, 0L), 0f)
        assertEquals(0f, miniPlayerProgress(30_000L, -1L), 0f)
    }

    @Test
    fun extremePlaybackValuesAlwaysProduceFiniteProgress() {
        val values = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        values.forEach { position ->
            values.forEach { duration ->
                val progress = miniPlayerProgress(position, duration)
                assertTrue("position=$position duration=$duration progress=$progress", progress.isFinite())
                assertTrue(progress in 0f..1f)
            }
        }
        assertEquals(1f, miniPlayerProgress(Long.MAX_VALUE, Long.MAX_VALUE), 0f)
    }

    @Test
    fun finiteSeekClampsToTheTrackAndPreservesOrdinaryPositions() {
        assertEquals(60_000L, miniPlayerSeekPosition(0.5f, 120_000L))
        assertEquals(0L, miniPlayerSeekPosition(-0.5f, 120_000L))
        assertEquals(120_000L, miniPlayerSeekPosition(1.5f, 120_000L))
        assertEquals(Long.MAX_VALUE, miniPlayerSeekPosition(1f, Long.MAX_VALUE))
    }

    @Test
    fun nonFiniteSeekAndUnknownDurationNeverProduceAPlaybackPosition() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { progress ->
            assertEquals(0L, miniPlayerSeekPosition(progress, 120_000L))
        }
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { duration ->
            assertEquals(0L, miniPlayerSeekPosition(0.5f, duration))
        }
    }

    @Test
    fun finiteSeekRemainsInRangeForExtremeDurations() {
        listOf(1L, 120_000L, Long.MAX_VALUE).forEach { duration ->
            listOf(-Float.MAX_VALUE, 0f, 0.5f, 1f, Float.MAX_VALUE).forEach { progress ->
                val position = miniPlayerSeekPosition(progress, duration)
                assertTrue("progress=$progress duration=$duration position=$position", position in 0L..duration)
            }
        }
    }
}
