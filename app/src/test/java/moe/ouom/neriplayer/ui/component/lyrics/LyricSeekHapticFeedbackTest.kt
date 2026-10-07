package moe.ouom.neriplayer.ui.component.lyrics

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.haptic.syncHapticFeedbackSetting
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails

class LyricSeekHapticFeedbackTest {

    private val vibrator = mock(Vibrator::class.java).also { `when`(it.hasVibrator()).thenReturn(true) }
    private val context = mock(Context::class.java).also {
        `when`(it.getSystemService(Vibrator::class.java)).thenReturn(vibrator)
    }
    private val lyrics = listOf(
        LyricEntry("first", 1_000L, 2_000L),
        LyricEntry("second", 2_000L, 3_000L),
        LyricEntry("third", 3_000L, 4_000L)
    )
    private val tick = mock(VibrationEffect::class.java)
    private var uptimeMs = 10_000L

    @Before
    fun enableHaptics() {
        syncHapticFeedbackSetting(true)
    }

    @After
    fun restoreHaptics() {
        syncHapticFeedbackSetting(true)
    }

    @Test
    fun `the first move without a seek start only records the current line`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, lyrics, lyricOffsetMs = 0L)

        feedback.onSeekMove(1_500L)

        assertEquals(0, vibrations())
    }

    @Test
    fun `crossing into another line ticks once while staying on a line does not`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, lyrics, lyricOffsetMs = 0L)
        feedback.onSeekStart(1_500L)

        feedback.onSeekMove(1_800L)
        assertEquals(0, vibrations())
        feedback.onSeekMove(2_500L)

        assertEquals(1, vibrations())
    }

    @Test
    fun `seeking before the first line is silent and the next line ticks again`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, lyrics, lyricOffsetMs = 0L)
        feedback.onSeekStart(2_500L)

        feedback.onSeekMove(500L)
        assertEquals(0, vibrations())
        feedback.onSeekMove(2_500L)

        assertEquals(1, vibrations())
    }

    @Test
    fun `line changes inside the debounce window are not ticked`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, lyrics, lyricOffsetMs = 0L)
        feedback.onSeekStart(1_500L)
        feedback.onSeekMove(2_500L)

        uptimeMs += 20L
        feedback.onSeekMove(3_500L)
        assertEquals(1, vibrations())

        uptimeMs += 60L
        feedback.onSeekMove(2_500L)
        assertEquals(2, vibrations())
    }

    @Test
    fun `lyric offset shifts the line a seek position lands on`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, lyrics, lyricOffsetMs = 1_000L)
        feedback.onSeekStart(500L)

        feedback.onSeekMove(900L)
        assertEquals(0, vibrations())
        feedback.onSeekMove(1_100L)

        assertEquals(1, vibrations())
    }

    @Test
    fun `songs without lyrics never tick`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, emptyList(), lyricOffsetMs = 0L)
        feedback.onSeekStart(0L)

        feedback.onSeekMove(5_000L)
        feedback.onSeekMove(10_000L)

        assertEquals(0, vibrations())
    }

    @Test
    fun `ending a seek drops the baseline line`() = withClock {
        val feedback = LyricSeekHapticFeedback(context, lyrics, lyricOffsetMs = 0L)
        feedback.onSeekStart(1_500L)
        feedback.onSeekEnd()

        feedback.onSeekMove(2_500L)

        assertEquals(0, vibrations())
    }

    private fun vibrations(): Int = mockingDetails(vibrator).invocations.count { it.method.name == "vibrate" }

    private fun withClock(block: () -> Unit) {
        mockStatic(SystemClock::class.java).use { clock ->
            mockStatic(VibrationEffect::class.java).use { effects ->
                clock.`when`<Long> { SystemClock.uptimeMillis() }.thenAnswer { uptimeMs }
                effects.`when`<VibrationEffect> { VibrationEffect.createOneShot(anyLong(), anyInt()) }
                    .thenReturn(tick)
                block()
            }
        }
    }
}
