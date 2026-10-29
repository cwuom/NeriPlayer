package moe.ouom.neriplayer.lanltw.playback
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.lanltw.core.LanClock
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanPlaybackSchedulerTest {
    private val track = ListenTogetherTrack("netease:1", "netease", "1", name = "Track", artist = "Artist")
    private class Port : LanPlaybackPort {
        override val schedulingLeadMs = 40.0 // Explicit synthetic backend calibration, never a product default.
        var loaded = true; var plays = 0; var pauses = 0; var position = 0.0; var prepareDelay = 0L
        override fun currentTrack(): ListenTogetherTrack? = null
        override fun positionSeconds() = position
        override fun isPlaying() = plays > 0
        override fun supported() = true
        override suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double): Boolean { delay(prepareDelay); return loaded }
        override fun ready(track: ListenTogetherTrack) = loaded
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun seek(positionSeconds: Double) { position = positionSeconds }
        override suspend fun setMediaVolume(value: Float) = Unit
        override fun begin(onCommand: (String, ListenTogetherTrack?, Double) -> Unit, onStop: () -> Unit) = Unit
        override fun end() = Unit
    }
    private fun action(type: String, revision: Long, at: Double) = LanMessage("SCHEDULED_ACTION", revision = revision,
        scheduledAction = LanAction(type, track.stableKey, 10.0, track), serverTimeToExecute = at)
    @Test fun oneLeadSubtractionAndPauseCancelsOldPlay() = runTest {
        val p = Port()
        val owner = LanPlaybackScheduler(this, p, LanClock { testScheduler.currentTime.toDouble() }, {0.0}, {0}, {true}, {}, {})
        owner.schedule(action("PLAY", 1, 1000.0)); runCurrent()
        advanceTimeBy(959); assertEquals(0, p.plays)
        advanceTimeBy(1); runCurrent(); assertEquals(1, p.plays)
        owner.schedule(action("PLAY", 2, 2000.0)); runCurrent()
        owner.schedule(action("PAUSE", 3, 1700.0)); advanceUntilIdle()
        assertEquals(1, p.plays); assertTrue(p.pauses > 0)
        owner.close()
    }
    @Test fun staleActionsAndCancelledLoadsNeverPlayOrAcknowledge() = runTest {
        val p = Port().apply { prepareDelay = 100 }
        val sent = mutableListOf<LanMessage>()
        val owner = LanPlaybackScheduler(this, p, LanClock { testScheduler.currentTime.toDouble() }, {0.0}, {0}, {true}, {sent += it}, {})
        fun load(rev: Long) = LanMessage("ROOM_EVENT", revision = rev, track = track, trackTimeSeconds = 0.0, loadGeneration = rev)
        owner.load(load(1)); runCurrent(); owner.load(load(2)); runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(listOf(2L), sent.map { it.loadGeneration })
        owner.schedule(action("PLAY", 1, 1000.0)); advanceUntilIdle(); assertEquals(0, p.plays)
        owner.close()
    }
    @Test fun unsyncedClientPausesAndRequestsFreshSync() = runTest {
        val p = Port(); val sent = mutableListOf<LanMessage>()
        val owner = LanPlaybackScheduler(this, p, LanClock { testScheduler.currentTime.toDouble() }, {0.0}, {0}, {false}, {sent += it}, {})
        owner.schedule(action("PLAY", 1, 1000.0)); advanceUntilIdle()
        assertEquals(0, p.plays); assertEquals(listOf("SYNC"), sent.map { it.type }); owner.close()
    }
    @Test fun nudgeReplacesFuturePlanInsteadOfAlsoSeekingImmediately() = runTest {
        val p = Port(); var nudge = 0
        val owner = LanPlaybackScheduler(this, p, LanClock { testScheduler.currentTime.toDouble() }, {0.0}, {nudge}, {true}, {}, {})
        owner.schedule(action("PLAY", 1, 1000.0)); runCurrent()
        nudge = 100; owner.nudgeChanged(100); runCurrent()
        advanceTimeBy(859); assertEquals(0, p.plays); advanceTimeBy(1); runCurrent()
        assertEquals(1, p.plays); assertEquals(10.0, p.position, .00001)
        owner.close(); advanceUntilIdle(); assertEquals(1, p.plays)
    }
    @Test fun audioEngineReceivesAbsoluteScheduleImmediatelyWithoutNativeSleepOrSeek() = runTest {
        val p = Port()
        val submitted = mutableListOf<LanMessage>()
        val audio = object : LanPlaybackPort by p, LanScheduledAudioPort {
            override val outputLatencyMs = 27.0
            override suspend fun initializeAudio() = Unit
            override suspend fun resetAudioClock() = Unit
            override suspend fun receiveProbe(message: LanMessage, receivedAt: Double) = null
            override suspend fun scheduleAudio(message: LanMessage, offsetMs: Double, nudgeMs: Int): Boolean {
                assertEquals(50.0, offsetMs, .0001); assertEquals(0, nudgeMs)
                submitted += message; return true
            }
            override fun cancelAudioSchedule() = Unit
            override fun changeAudioNudge(value: Int, delta: Int) = Unit
        }
        val owner = LanPlaybackScheduler(this, audio, LanClock { testScheduler.currentTime.toDouble() }, {50.0}, {0}, {true}, {}, {})
        val message = action("PLAY", 1, 1000.0)
        owner.schedule(message); runCurrent()
        assertEquals(listOf(message), submitted)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0, p.plays); assertEquals(0.0, p.position, .0001)
        owner.close()
    }

}
