package moe.ouom.neriplayer.ui.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

class HapticFeedbackDispatchTest {

    private val vibrator = mock(Vibrator::class.java)
    private val context = mock(Context::class.java)

    @Before
    fun enableHaptics() {
        syncHapticFeedbackSetting(true)
    }

    @After
    fun restoreHaptics() {
        syncHapticFeedbackSetting(true)
    }

    @Test
    fun `disabled haptics never look up the vibrator`() {
        syncHapticFeedbackSetting(false)

        context.performHapticFeedback(HapticFeedbackEffect.Heavy)

        verifyNoInteractions(context)
    }

    @Test
    fun `a missing vibrator service is ignored`() {
        `when`(context.getSystemService(Vibrator::class.java)).thenReturn(null)

        context.performHapticFeedback()

        verify(context).getSystemService(Vibrator::class.java)
        verifyNoInteractions(vibrator)
    }

    @Test
    fun `devices without vibrator hardware are never vibrated`() {
        `when`(context.getSystemService(Vibrator::class.java)).thenReturn(vibrator)
        `when`(vibrator.hasVibrator()).thenReturn(false)

        context.performHapticFeedback()

        verify(vibrator).hasVibrator()
        verifyNoMoreInteractions(vibrator)
    }

    @Test
    fun `without predefined effects the tick falls back to its tuned one shot vibration`() {
        `when`(context.getSystemService(Vibrator::class.java)).thenReturn(vibrator)
        `when`(vibrator.hasVibrator()).thenReturn(true)
        val oneShot = mock(VibrationEffect::class.java)

        mockStatic(VibrationEffect::class.java).use { effects ->
            effects.`when`<VibrationEffect> { VibrationEffect.createOneShot(8L, 32) }.thenReturn(oneShot)

            context.performHapticFeedback(HapticFeedbackEffect.Tick)

            effects.verify { VibrationEffect.createOneShot(8L, 32) }
        }
        verify(vibrator).vibrate(oneShot)
    }
}
