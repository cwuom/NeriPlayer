package moe.ouom.neriplayer.core.player.usb.sink

import android.content.Context
import android.media.AudioManager
import moe.ouom.neriplayer.core.player.usb.system.UsbExclusiveSystemVolumeBridge
import org.junit.Assert.assertEquals
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
        val port = RecordingVolumePort()
        val owner = UsbExclusiveSinkVolumeOwner(
            context = context,
            observeSystemVolume = false,
            bitPerfect = { true },
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
        val owner = UsbExclusiveSinkVolumeOwner(context, false, { false }, port)
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
    fun `system volume read failure falls back without aborting owner creation`() {
        UsbExclusiveSystemVolumeBridge.clearSessionVolumeFraction()
        val context = mock(Context::class.java)
        val manager = mock(AudioManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(manager)
        `when`(manager.getStreamMinVolume(AudioManager.STREAM_MUSIC))
            .thenThrow(IllegalStateException("volume unavailable"))
        val owner = UsbExclusiveSinkVolumeOwner(context, false, { true }, RecordingVolumePort())

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
        val owner = UsbExclusiveSinkVolumeOwner(context, false, { false }, RecordingVolumePort())
        try {
            verify(manager).getStreamVolume(AudioManager.STREAM_MUSIC)
        } finally {
            owner.release()
        }
    }

    private class RecordingVolumePort : UsbExclusiveSinkVolumePort {
        val nativeVolumes = mutableListOf<Pair<Long, Float>>()
        val fallbackVolumes = mutableListOf<Float>()
        val publishedVolumes = mutableListOf<Float>()

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
