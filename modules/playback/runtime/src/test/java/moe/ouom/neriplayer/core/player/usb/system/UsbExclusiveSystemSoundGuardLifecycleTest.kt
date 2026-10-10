package moe.ouom.neriplayer.core.player.usb.system

import android.content.Context
import android.util.Log
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.`when`

class UsbExclusiveSystemSoundGuardLifecycleTest {

    private val context = mock(Context::class.java).also {
        `when`(it.applicationContext).thenReturn(it)
        `when`(it.packageName).thenReturn(PACKAGE)
    }
    private lateinit var log: MockedStatic<Log>

    @Before
    fun startInactive() {
        log = mockStatic(Log::class.java)
        UsbExclusiveSystemSoundGuard.forceRelease(context, "setup")
        log.clearInvocations()
    }

    @After
    fun leaveInactive() {
        UsbExclusiveSystemSoundGuard.forceRelease(context, "teardown")
        log.close()
    }

    @Test
    fun `only the first activation of a session is reported as first`() {
        UsbExclusiveSystemSoundGuard.activate(context, "play")
        UsbExclusiveSystemSoundGuard.activate(context, "resume")

        log.verify({ Log.i(anyString(), eq("activate reason=play first=true package=$PACKAGE"), isNull()) })
        log.verify({ Log.i(anyString(), eq("activate reason=resume first=false package=$PACKAGE"), isNull()) })
    }

    @Test
    fun `an active session is released once whichever release path runs first`() {
        UsbExclusiveSystemSoundGuard.activate(context, "play")

        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(context, "native_idle")
        UsbExclusiveSystemSoundGuard.forceRelease(context, "stop")

        log.verify({ Log.i(anyString(), eq("release reason=native_idle package=$PACKAGE"), isNull()) }, times(1))
        log.verify({ Log.i(anyString(), contains("release reason=stop"), isNull()) }, never())
    }

    @Test
    fun `releasing without an active session reports nothing`() {
        UsbExclusiveSystemSoundGuard.forceRelease(context, "stop")
        UsbExclusiveSystemSoundGuard.releaseWhenNativeIdle(context, "native_idle")

        log.verify({ Log.i(anyString(), contains("release reason="), isNull()) }, never())
    }

    private companion object {
        const val PACKAGE = "moe.ouom.neriplayer"
    }
}
