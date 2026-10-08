package moe.ouom.neriplayer.core.player.audio.output

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSoundOwnerTest {
    @Test
    fun `speed change applies immediately and persists after debounce`() = runTest {
        val port = RecordingPort()
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)

        owner.setSpeed(1.2f, persist = true)
        runCurrent()

        assertEquals(1.2f, engine.applied.single().speed, 0.0001f)
        assertTrue(port.persisted.isEmpty())
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1.2f, port.persisted.single().speed, 0.0001f)
    }

    @Test
    fun `newer sound request supersedes one that has not been applied yet`() = runTest {
        val port = RecordingPort()
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)

        owner.setVolumeNormalizationEnabled(true, persist = false)
        owner.setVolumeNormalizationEnabled(false, persist = false)
        runCurrent()

        assertEquals(1, engine.applied.size)
        assertFalse(engine.applied.single().volumeNormalizationEnabled)
    }

    @Test
    fun `USB engine bypass leaves stored sound settings intact`() = runTest {
        val port = RecordingPort().apply { usbEnabled = true }
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)

        owner.setSpeed(1.25f, persist = false)
        runCurrent()

        assertEquals(1.25f, owner.config.speed, 0.0001f)
        assertEquals(1f, engine.applied.single().speed, 0.0001f)
    }

    @Test
    fun `high resolution setting updates offload and USB route once`() = runTest {
        val port = RecordingPort().apply { usbEnabled = true }
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, RecordingEngine())

        owner.setHighResolutionEnabled(true, persist = true)
        owner.setHighResolutionEnabled(true, persist = true)
        runCurrent()

        assertTrue(owner.highResolutionEnabled)
        assertEquals(1, port.offloadUpdates)
        assertEquals(1, port.usbReconfigurations)
        assertEquals(listOf(true), port.persistedHighResolution)
    }

    @Test
    fun `listen together rate modifies only effective engine speed`() = runTest {
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, RecordingPort(), engine)
        owner.setSpeed(1.1f, persist = false)
        runCurrent()

        owner.setListenTogetherSyncRate(1.03f)
        runCurrent()

        assertEquals(1.1f, owner.config.speed, 0.0001f)
        assertEquals(normalizePlaybackSpeed(1.1f * 1.03f), engine.applied.last().speed, 0.0001f)
    }

    @Test
    fun `speed and pitch reset keeps balance and normalization`() = runTest {
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, RecordingPort(), engine)
        owner.setSpeed(1.5f, persist = false)
        owner.setPitch(0.8f, persist = false)
        owner.setVolumeBalance(0.3f, persist = false)
        owner.setVolumeNormalizationEnabled(true, persist = false)

        owner.resetSpeedAndPitch(persist = false)
        runCurrent()

        assertEquals(1f, owner.config.speed, 0.0001f)
        assertEquals(1f, owner.config.pitch, 0.0001f)
        assertEquals(0.3f, owner.config.volumeBalance, 0.0001f)
        assertTrue(owner.config.volumeNormalizationEnabled)
        assertEquals(owner.config, engine.applied.last())
    }

    @Test
    fun `linked speed and pitch reach the engine as one change`() = runTest {
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, RecordingPort(), engine)

        owner.setSpeedAndPitch(1.25f, 1.25f, persist = false)
        runCurrent()

        assertEquals(1, engine.applied.size)
        assertEquals(1.25f, engine.applied.single().speed, 0.0001f)
        assertEquals(1.25f, engine.applied.single().pitch, 0.0001f)
    }

    @Test
    fun `scope rebind cancels pending effect and persistence jobs`() = runTest {
        val port = RecordingPort()
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)
        owner.setVolumeNormalizationEnabled(true, persist = true)
        owner.rebindScopes(backgroundScope, backgroundScope)
        advanceTimeBy(200L)
        runCurrent()

        assertTrue(engine.applied.isEmpty())
        assertTrue(port.persisted.isEmpty())
    }

    @Test
    fun `Lyricon speed receives changed normalized value and unchanged rate is ignored`() = runTest {
        val port = RecordingPort().apply { lyricon = true }
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)
        owner.setSpeed(1.2f, persist = false)
        runCurrent()
        owner.setListenTogetherSyncRate(1f)
        runCurrent()

        assertEquals(listOf(1.2f), port.lyriconSpeeds)
        assertEquals(1, engine.applied.size)
        owner.setHighResolutionEnabled(true, persist = false)
        assertEquals(0, port.usbReconfigurations)
    }

    @Test
    fun `every applied sound change reaches the engine once settled`() = runTest {
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, RecordingPort(), engine)
        val changes = listOf(
            owner.config.copy(speed = 1.5f),
            owner.config.copy(pitch = 1.2f),
            owner.config.copy(volumeBalance = -0.4f),
            owner.config.copy(volumeNormalizationEnabled = true)
        )
        changes.forEach { next ->
            owner.applyConfig(next, persist = false)
            runCurrent()
        }
        assertEquals(changes, engine.applied)
    }

    private class RecordingEngine : PlaybackSoundEngine {
        val applied = mutableListOf<PlaybackSoundConfig>()
        override fun attachPlayer(player: ExoPlayer?): PlaybackSoundState = PlaybackSoundState()
        override fun updateConfig(config: PlaybackSoundConfig): PlaybackSoundState {
            applied += config
            return PlaybackSoundState()
        }
        override fun release(): PlaybackSoundState = PlaybackSoundState()
    }

    private class RecordingPort : PlaybackSoundPort {
        var usbEnabled = false
        var lyricon = false
        var offloadUpdates = 0
        var usbReconfigurations = 0
        val lyriconSpeeds = mutableListOf<Float>()
        val persisted = mutableListOf<PlaybackSoundConfig>()
        val persistedHighResolution = mutableListOf<Boolean>()
        override fun lyriconEnabled(): Boolean = lyricon
        override fun usbExclusiveEnabled(): Boolean = usbEnabled
        override fun updateLyriconSpeed(speed: Float) { lyriconSpeeds += speed }
        override fun updateAudioOffloadPreferences(reason: String) { offloadUpdates++ }
        override fun reconfigureUsbSinkForHighResolution() { usbReconfigurations++ }
        override fun debugStackHint(): String = "test"
        override suspend fun persistConfig(config: PlaybackSoundConfig) { persisted += config }
        override suspend fun persistHighResolution(enabled: Boolean) { persistedHighResolution += enabled }
    }
}
