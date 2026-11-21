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
    private val stream = LanStreamResource("12345678-1234-1234-1234-123456789abc", "audio/mpeg")
    private inner class Port : LanPlaybackPort {
        override val schedulingLeadMs = 40.0 // Explicit synthetic backend calibration, never a product default.
        var loaded = true; var plays = 0; var pauses = 0; var position = 0.0; var prepareDelay = 0L
        override var preparedMediaId: String? = stream.mediaId
        override suspend fun prepareSharedSource(track: ListenTogetherTrack, refresh: Boolean) = LanPreparedSource(track, stream)
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
        scheduledAction = LanAction(type, track.stableKey, 10.0, track), serverTimeToExecute = at,
        streamResource = stream)
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
    @Test fun safetyPauseCancelsFuturePlaybackButLetsRoomPreparationFinish() = runTest {
        val p = Port().apply { prepareDelay = 100 }
        val sent = mutableListOf<LanMessage>()
        val owner = LanPlaybackScheduler(this, p, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, { sent += it }, {})
        owner.load(LanMessage("ROOM_EVENT", revision = 1, track = track, streamResource = stream, loadGeneration = 1))
        owner.schedule(action("PLAY", 1, 1000.0))
        runCurrent()
        assertTrue(owner.playScheduled)
        owner.interruptPlayback()
        assertFalse(owner.playScheduled)
        advanceUntilIdle()
        assertEquals(0, p.plays)
        assertEquals(listOf("AUDIO_SOURCE_READY"), sent.map { it.type })
        owner.close()
    }
    @Test fun staleActionsAndCancelledLoadsNeverPlayOrAcknowledge() = runTest {
        val p = Port().apply { prepareDelay = 100 }
        val sent = mutableListOf<LanMessage>()
        val owner = LanPlaybackScheduler(this, p, LanClock { testScheduler.currentTime.toDouble() }, {0.0}, {0}, {true}, {sent += it}, {})
        fun load(rev: Long) = LanMessage("ROOM_EVENT", revision = rev, track = track, trackTimeSeconds = 0.0,
            loadGeneration = rev, streamResource = stream)
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

    @Test fun lateJoinCannotStartTheWrongRelayResource() = runTest {
        val p = Port().apply { preparedMediaId = "wrong" }
        val late = object : LanPlaybackPort by p {
            override fun ready(track: ListenTogetherTrack): Boolean = false
        }
        val errors = mutableListOf<String>()
        val owner = LanPlaybackScheduler(this, late, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, {}, { errors += it })
        owner.schedule(action("PLAY", 1, 1000.0))
        advanceUntilIdle()
        assertEquals(0, p.plays)
        assertEquals(listOf("LOADING", "AUDIO_CONTENT"), errors)
        owner.close()
    }

    @Test fun readyAcknowledgmentDoesNotWaitForThePresentation() = runTest {
        val presentation = LanSharedPresentation("Track", "Artist", "")
        val descriptor = LanFileResource("a".repeat(36), "b".repeat(64), 100, "application/json")
        val p = Port()
        val order = mutableListOf<String>()
        val sent = mutableListOf<LanMessage>()
        val port = object : LanPlaybackPort by p {
            override fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) {
                assertEquals(this@LanPlaybackSchedulerTest.track, track)
                assertEquals("Track", presentation?.name)
                order += "presentation"
            }

            override suspend fun prepare(track: ListenTogetherTrack, positionSeconds: Double): Boolean {
                order += "prepare"
                return true
            }
        }
        val owner = LanPlaybackScheduler(this, port, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, { sent += it }, {}, resolvePresentation = {
                assertEquals(descriptor, it)
                delay(1500)
                presentation
            })
        owner.load(LanMessage("ROOM_EVENT", revision = 1, track = track,
            loadGeneration = 1, streamResource = stream, presentationResource = descriptor))
        runCurrent()
        assertEquals("AUDIO_SOURCE_READY", sent.single().type)
        assertEquals(stream.mediaId, sent.single().mediaId)
        assertEquals(listOf("prepare"), order)
        advanceUntilIdle()
        assertEquals(listOf("prepare", "presentation"), order)
        owner.close()
    }

    @Test fun readyTrackRefreshesOnlinePresentationWithoutDelayingPlayback() = runTest {
        val p = Port()
        var shared: LanSharedPresentation? = null
        val port = object : LanPlaybackPort by p {
            override fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) {
                shared = presentation
            }
        }
        val presentation = LanSharedPresentation("Track", "Artist", "")
        val descriptor = LanFileResource("a".repeat(36), "b".repeat(64), 100, "application/json")
        val owner = LanPlaybackScheduler(this, port, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, {}, {}, resolvePresentation = {
                delay(1500)
                presentation
            })
        owner.schedule(action("PLAY", 1, 1000.0).copy(presentationResource = descriptor))
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, p.plays)
        assertNull(shared)
        advanceUntilIdle()
        assertEquals(presentation, shared)
        owner.close()
    }

    @Test fun nudgeDoesNotRestoreThePreCoverPresentationFromAPendingPlan() = runTest {
        val base = LanSharedPresentation("Track", "Artist", "", lyrics = listOf(LanSharedLyricLine("Line", 0, 500)))
        val withCover = base.copy(cover = LanFileResource("c".repeat(36), "c".repeat(64), 128, "image/png"))
        val initial = LanFileResource("a".repeat(36), "a".repeat(64), 100, "application/json")
        val updated = initial.copy(mediaId = "b".repeat(36), sha256 = "b".repeat(64))
        val applied = mutableListOf<LanSharedPresentation?>()
        val port = object : LanPlaybackPort by Port() {
            override fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) {
                applied += presentation
            }
        }
        val owner = LanPlaybackScheduler(this, port, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, {}, {}, resolvePresentation = { if (it == updated) withCover else base })
        owner.schedule(action("PLAY", 1, 1000.0).copy(presentationResource = initial))
        runCurrent()
        owner.presentation(LanMessage("LAN_PRESENTATION", track = track, mediaId = stream.mediaId,
            presentationResource = updated))
        runCurrent()
        owner.nudgeChanged(10)
        runCurrent()
        assertEquals(listOf(base, withCover), applied)
        owner.close()
    }

    @Test fun temporaryPresentationFailureRetriesWithoutRestartingAudioAndSyncCanRedeliver() = runTest {
        val value = LanSharedPresentation("Track", "Artist", "")
        val descriptor = LanFileResource("a".repeat(36), "b".repeat(64), 100, "application/json")
        val applied = mutableListOf<LanSharedPresentation?>()
        var attempts = 0
        val p = Port()
        val port = object : LanPlaybackPort by p {
            override fun sharedPresentation(track: ListenTogetherTrack, presentation: LanSharedPresentation?) {
                applied += presentation
            }
        }
        val owner = LanPlaybackScheduler(this, port, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, {}, {}, resolvePresentation = { if (++attempts == 1) null else value })
        owner.schedule(action("PLAY", 1, 200.0).copy(presentationResource = descriptor))
        advanceTimeBy(250)
        assertEquals(1, p.plays)
        assertTrue(applied.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf(value), applied)
        assertEquals(2, attempts)
        owner.close()

        owner.paused(LanMessage("LAN_PAUSED_STATE", track = track, revision = 2,
            streamResource = stream, presentationResource = descriptor))
        advanceUntilIdle()
        assertEquals(listOf(value, value), applied)
        assertEquals(1, p.plays)
        owner.close()
    }

    @Test fun slowLateJoinSubmitsTheAbsolutePlanAfterBufferingWithoutAnotherSyncLoop() = runTest {
        val p = Port().apply { prepareDelay = 1500 }
        val plans = mutableListOf<LanMessage>()
        val sent = mutableListOf<LanMessage>()
        val audio = object : LanPlaybackPort by p, LanScheduledAudioPort {
            override fun ready(track: ListenTogetherTrack) = false
            override val outputLatencyMs = 0.0
            override suspend fun initializeAudio() = Unit
            override suspend fun resetAudioClock() = Unit
            override suspend fun receiveProbe(message: LanMessage, receivedAt: Double) = null
            override suspend fun scheduleAudio(message: LanMessage, offsetMs: Double, nudgeMs: Int): Boolean {
                plans += message
                return true
            }
            override fun cancelAudioSchedule() = Unit
            override fun changeAudioNudge(value: Int, delta: Int) = Unit
        }
        val owner = LanPlaybackScheduler(this, audio, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, { sent += it }, {})
        val message = action("PLAY", 1, 1000.0)
        owner.schedule(message)
        advanceUntilIdle()
        assertEquals(listOf(message), plans)
        assertTrue(sent.isEmpty())
        owner.close()
    }

    @Test fun failedSynchronizationHasBoundedAutomaticRetries() = runTest {
        val sent = mutableListOf<LanMessage>()
        val errors = mutableListOf<String>()
        val owner = LanPlaybackScheduler(this, Port(), LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { false }, { sent += it }, { errors += it })
        repeat(4) {
            owner.schedule(action("PLAY", 1, 1000.0))
            advanceUntilIdle()
        }
        assertEquals(List(3) { "SYNC" }, sent.map { it.type })
        assertEquals("LOAD_TIMEOUT", errors.last())
        owner.close()
    }

    @Test fun unseekablePlanReportsFailureWithoutAutomaticallySyncingAgain() = runTest {
        val sent = mutableListOf<LanMessage>()
        val errors = mutableListOf<String>()
        val audio = object : LanPlaybackPort by Port(), LanScheduledAudioPort {
            override val preparationError: String = "AUDIO_NOT_SEEKABLE"
            override val outputLatencyMs = 0.0
            override suspend fun initializeAudio() = Unit
            override suspend fun resetAudioClock() = Unit
            override suspend fun receiveProbe(message: LanMessage, receivedAt: Double): Nothing? = null
            override suspend fun scheduleAudio(message: LanMessage, offsetMs: Double, nudgeMs: Int) = false
            override fun cancelAudioSchedule() = Unit
            override fun changeAudioNudge(value: Int, delta: Int) = Unit
        }
        val owner = LanPlaybackScheduler(this, audio, LanClock { testScheduler.currentTime.toDouble() },
            { 0.0 }, { 0 }, { true }, { sent += it }, { errors += it })
        owner.schedule(action("PLAY", 1, 1000.0))
        advanceUntilIdle()
        assertEquals(listOf("AUDIO_NOT_SEEKABLE"), errors)
        assertTrue(sent.isEmpty())
        owner.close()
    }
}
