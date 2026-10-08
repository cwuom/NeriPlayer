package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import android.media.AudioManager
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class UsbExclusiveSinkVolumeOwnerTest {
    @Test
    fun `fallback and native routes use volume owner's player state`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val port = RecordingVolumePort(bitPerfect = true)
        val owner = UsbExclusiveSinkVolumeOwner(
            context = context,
            observeSystemVolume = false,
            port = port,
        )
        try {
            owner.setPlayerVolume(0.25f)
            assertEquals(0.25f, owner.playerVolume)
            assertEquals(listOf(0.25f), port.fallbackVolumes)
            assertEquals(listOf(0.25f), port.publishedVolumes)

            owner.setNativeHandle(7L)
            owner.setPlayerVolume(0.5f)
            assertEquals(listOf(7L to 1f), port.nativeVolumes)
            assertEquals(listOf(0.25f, 1f), port.publishedVolumes)

            owner.setNativeHandle(0L)
            owner.setPlayerVolume(0.75f)
            assertEquals(listOf(0.25f, 0.75f), port.fallbackVolumes)
            assertEquals(listOf(0.25f, 1f, 0.75f), port.publishedVolumes)
        } finally {
            owner.release()
        }
    }

    @Test
    fun `system volume changes update native gain without changing player volume`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        val port = RecordingVolumePort()
        val owner = UsbExclusiveSinkVolumeOwner(context, false, port)
        try {
            owner.setNativeHandle(7L)
            owner.setPlayerVolume(0.8f)
            owner.updateSystemVolumeFraction(0.5f)

            assertEquals(0.8f, owner.playerVolume)
            assertEquals(7L to 0.2f, port.nativeVolumes.last())
            assertEquals(0.2f, port.publishedVolumes.last())
        } finally {
            owner.release()
        }
    }

    @Test
    fun `bit perfect volume keys move the DAC hardware volume and keep PCM at unity`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val port = RecordingVolumePort(bitPerfect = true, hardwareVolume = true)
        val owner = UsbExclusiveSinkVolumeOwner(contextWithoutAudioManager(), false, port)
        try {
            owner.setNativeHandle(7L)
            owner.updateSystemVolumeFraction(0.4f)
            owner.updateSystemVolumeFraction(0.6f)

            assertEquals(listOf(7L to 1f, 7L to 0.4f, 7L to 0.6f), port.hardwareVolumes)
            assertTrue(port.nativeVolumes.all { it.second == 1f })
            owner.setNativeHandle(0L)
            assertEquals(listOf(true, false), port.publishedHardwareVolume)
        } finally {
            owner.release()
        }
    }

    @Test
    fun `digital volume keeps the DAC at unity and DACs without hardware volume are left alone`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val digital = RecordingVolumePort(hardwareVolume = true)
        val digitalOwner = UsbExclusiveSinkVolumeOwner(contextWithoutAudioManager(), false, digital)
        val fixed = RecordingVolumePort(bitPerfect = true)
        val fixedOwner = UsbExclusiveSinkVolumeOwner(contextWithoutAudioManager(), false, fixed)
        try {
            digitalOwner.setNativeHandle(7L)
            digitalOwner.updateSystemVolumeFraction(0.5f)
            assertTrue(digital.hardwareVolumes.all { it.second == 1f })
            assertEquals(7L to 0.25f, digital.nativeVolumes.last())

            fixedOwner.setNativeHandle(9L)
            fixedOwner.updateSystemVolumeFraction(0.5f)
            assertTrue(fixed.hardwareVolumes.isEmpty())
            assertEquals(listOf(false), fixed.publishedHardwareVolume)
        } finally {
            digitalOwner.release()
            fixedOwner.release()
        }
    }

    @Test
    fun `opening native output reads the current system volume instead of a stale cache`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val context = mock(Context::class.java)
        val manager = mock(AudioManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        `when`(manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)).thenReturn(0)
        `when`(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        `when`(manager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(5)
        val port = RecordingVolumePort(bitPerfect = true, hardwareVolume = true)
        val owner = UsbExclusiveSinkVolumeOwner(context, false, port)
        try {
            `when`(manager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(8)
            owner.setNativeHandle(7L)

            assertEquals(7L to 0.8f, port.hardwareVolumes.single())
        } finally {
            owner.release()
        }
    }

    @Test
    fun `system volume is polled only while observing without a pushed session volume`() {
        assertTrue(shouldPollUsbExclusiveSystemVolume(observing = true, sessionVolumePushed = false))
        assertFalse(shouldPollUsbExclusiveSystemVolume(observing = true, sessionVolumePushed = true))
        assertFalse(shouldPollUsbExclusiveSystemVolume(observing = false, sessionVolumePushed = false))
    }

    @Test
    fun `system volume read failure falls back without aborting owner creation`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val context = mock(Context::class.java)
        val manager = mock(AudioManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        `when`(manager.getStreamMinVolume(AudioManager.STREAM_MUSIC))
            .thenThrow(IllegalStateException("volume unavailable"))
        val owner = UsbExclusiveSinkVolumeOwner(context, false, RecordingVolumePort(bitPerfect = true))

        owner.release()
    }

    @Test
    fun `owner reads stream volume through its system source`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val context = mock(Context::class.java)
        val manager = mock(AudioManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        `when`(manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)).thenReturn(0)
        `when`(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(10)
        `when`(manager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(5)
        val owner = UsbExclusiveSinkVolumeOwner(context, false, RecordingVolumePort())
        try {
            verify(manager).getStreamVolume(AudioManager.STREAM_MUSIC)
        } finally {
            owner.release()
        }
    }

    private fun contextWithoutAudioManager(): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(null)
        return context
    }

    private class RecordingVolumePort(
        private val bitPerfect: Boolean = false,
        private val hardwareVolume: Boolean = false,
    ) : UsbExclusiveSinkVolumePort {
        val nativeVolumes = mutableListOf<Pair<Long, Float>>()
        val fallbackVolumes = mutableListOf<Float>()
        val publishedVolumes = mutableListOf<Float>()
        val hardwareVolumes = mutableListOf<Pair<Long, Float>>()
        val publishedHardwareVolume = mutableListOf<Boolean>()

        override fun hasHardwareVolume(handle: Long): Boolean = hardwareVolume

        override fun setHardwareVolume(handle: Long, fraction: Float) {
            hardwareVolumes += handle to fraction
        }

        override fun publishHardwareVolume(available: Boolean) {
            publishedHardwareVolume += available
        }

        override fun bitPerfect(): Boolean = bitPerfect

        override fun setNativeVolume(handle: Long, volume: Float) {
            nativeVolumes += handle to volume
        }

        override fun setFallbackVolume(volume: Float) {
            fallbackVolumes += volume
        }

        override fun publishVolume(volume: Float) {
            publishedVolumes += volume
        }
    }
}
