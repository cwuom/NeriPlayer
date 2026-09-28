package moe.ouom.neriplayer.core.player.audio.output

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
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
    fun `new equalizer request supersedes older delayed effect`() = runTest {
        val port = RecordingPort()
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)

        owner.setEqualizerEnabled(true, persist = false)
        runCurrent()
        advanceTimeBy(24L)
        owner.setEqualizerEnabled(false, persist = false)
        runCurrent()
        advanceTimeBy(48L)
        runCurrent()

        assertEquals(1, engine.applied.size)
        assertFalse(engine.applied.single().equalizerEnabled)
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
    fun `equalizer band edits validate index and replace custom levels`() = runTest {
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, RecordingPort(), engine)
        owner.updateEqualizerBandLevel(-1, 250, persist = false)
        assertTrue(engine.applied.isEmpty())

        owner.updateEqualizerBandLevel(0, 250, persist = false)
        advanceTimeBy(48L)
        runCurrent()

        assertTrue(owner.config.equalizerEnabled)
        assertEquals(250, owner.config.customBandLevelsMb.first())
        assertEquals(1, engine.applied.size)
    }

    @Test
    fun `scope rebind cancels pending effect and persistence jobs`() = runTest {
        val port = RecordingPort()
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, port, engine)
        owner.setEqualizerEnabled(true, persist = true)
        runCurrent()
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
    fun `every heavy sound effect change uses delayed engine apply`() = runTest {
        val engine = RecordingEngine()
        val owner = PlaybackSoundOwner(backgroundScope, backgroundScope, RecordingPort(), engine)
        val effectChanges = listOf(
            owner.config.copy(equalizerEnabled = true),
            owner.config.copy(presetId = "rock"),
            owner.config.copy(customBandLevelsMb = listOf(100)),
            owner.config.copy(loudnessGainMb = 100)
        )
        effectChanges.forEach { next ->
            owner.applyConfig(next, persist = false)
            runCurrent()
            advanceTimeBy(48L)
            runCurrent()
        }
        assertEquals(effectChanges.size, engine.applied.size)
    }

    private class RecordingEngine : PlaybackSoundEngine {
        val applied = mutableListOf<PlaybackSoundConfig>()
        override fun attachPlayer(player: ExoPlayer?): PlaybackSoundState = PlaybackSoundState()
        override fun onAudioSessionIdChanged(audioSessionId: Int?): PlaybackSoundState = PlaybackSoundState()
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
