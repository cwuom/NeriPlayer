package moe.ouom.neriplayer.core.player.audio.effects

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsResolution
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.SpeakerOptimizerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioEffectsOwnerTest {
    private val bassBoost = AudioEffectsProfile(enabled = true, sound = AudioEffectsSound(bassDb = 4f))

    @Test
    fun `restore publishes once and reports the active state change`() = runTest {
        val port = FakePort()
        val publisher = RecordingPublisher()
        val owner = AudioEffectsOwner(backgroundScope, port, publisher)

        owner.restore(AudioEffectsSettings(main = bassBoost))

        assertTrue(publisher.resolutions.single().active)
        assertTrue(owner.isDspActive)
        assertEquals(listOf(true), port.activeChanges)
    }

    @Test
    fun `slider edits update immediately and persist only the latest value`() = runTest {
        val port = FakePort()
        val owner = AudioEffectsOwner(backgroundScope, port, RecordingPublisher())

        owner.update(persist = true) { it.copy(main = bassBoost) }
        owner.update(persist = true) { it.copy(main = bassBoost.copy(sound = AudioEffectsSound(bassDb = 6f))) }
        runCurrent()

        assertEquals(6f, owner.settings.value.main.sound.bassDb)
        assertTrue(port.persisted.isEmpty())
        advanceTimeBy(260L)
        runCurrent()
        assertEquals(listOf(6f), port.persisted.map { it.main.sound.bassDb })
    }

    @Test
    fun `stored values are ignored while a local write is pending`() = runTest {
        val owner = AudioEffectsOwner(backgroundScope, FakePort(), RecordingPublisher())
        owner.update(persist = true) { it.copy(main = bassBoost) }

        owner.applyStored(AudioEffectsSettings())
        assertTrue(owner.settings.value.main.enabled)

        advanceTimeBy(260L)
        runCurrent()
        owner.applyStored(AudioEffectsSettings())
        assertFalse(owner.settings.value.main.enabled)
    }

    @Test
    fun `route changes re-resolve speaker optimization`() = runTest {
        val port = FakePort()
        val publisher = RecordingPublisher()
        val owner = AudioEffectsOwner(backgroundScope, port, publisher)
        owner.restore(AudioEffectsSettings(speaker = SpeakerOptimizerSettings(enabled = true)))
        assertTrue(publisher.resolutions.last().active)

        owner.onRouteChanged(AudioOutputRoute.BLUETOOTH)
        owner.onRouteChanged(AudioOutputRoute.BLUETOOTH)

        assertFalse(publisher.resolutions.last().active)
        assertEquals(2, publisher.resolutions.size)
        assertEquals(AudioOutputRoute.BLUETOOTH, owner.route.value)
        assertEquals(listOf(true, false), port.activeChanges)
    }

    @Test
    fun `USB processing is only allowed when enabled and not bit perfect`() = runTest {
        val port = FakePort()
        val publisher = RecordingPublisher()
        val owner = AudioEffectsOwner(backgroundScope, port, publisher)
        owner.restore(AudioEffectsSettings(main = bassBoost, applyInUsbExclusive = true))
        assertTrue(publisher.usbAllowed.last())

        port.bitPerfect = true
        owner.onUsbPreferencesChanged()

        assertFalse(publisher.usbAllowed.last())
    }

    @Test
    fun `unchanged updates do not republish`() = runTest {
        val publisher = RecordingPublisher()
        val owner = AudioEffectsOwner(backgroundScope, FakePort(), publisher)
        owner.restore(AudioEffectsSettings())

        owner.update(persist = false) { it }

        assertEquals(1, publisher.resolutions.size)
    }

    private class FakePort : AudioEffectsPort {
        var bitPerfect = false
        val activeChanges = mutableListOf<Boolean>()
        val persisted = mutableListOf<AudioEffectsSettings>()
        override fun usbBitPerfect(): Boolean = bitPerfect
        override fun onDspActiveChanged(active: Boolean, reason: String) {
            activeChanges += active
        }
        override suspend fun persist(settings: AudioEffectsSettings) {
            persisted += settings
        }
    }

    private class RecordingPublisher : AudioEffectsRuntimePublisher {
        val resolutions = mutableListOf<AudioEffectsResolution>()
        val usbAllowed = mutableListOf<Boolean>()
        override fun publish(resolution: AudioEffectsResolution, usbNativeAllowed: Boolean, route: AudioOutputRoute) {
            resolutions += resolution
            usbAllowed += usbNativeAllowed
        }
    }
}
