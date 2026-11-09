package moe.ouom.neriplayer.lanltw.core

import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

class LanRoomEngineTest {
    @Test fun roomVolumeStartsAtHostMediaVolumeAndFollowsHostReports() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, message -> sent += message }
        room.join("host", "Host", true, .4f)
        assertEquals(.4f, sent.first().room!!.volume)
        room.join("member", "Member", false, .2f)
        assertEquals(.4f, room.snapshot().volume)
        assertEquals(.2f, room.snapshot().members.last().volume)
        room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "host", volume = .6f))
        assertEquals(.6f, room.snapshot().volume)
        assertEquals(.2f, room.snapshot().members.last().volume)
        room.request("member", LanMessage("LAN_MEMBER_VOLUME", clientId = "member", volume = .3f))
        assertEquals(.6f, room.snapshot().volume)
        room.request("host", LanMessage("SET_GLOBAL_VOLUME", volume = .5f))
        assertEquals(.5f, room.snapshot().volume)
        assertTrue(room.snapshot().members.all { it.volume == .5f })
    }
    @Test fun savedPermissionsArePresentInFirstWelcomeAndOnlyHostCanChangeThem() {
        val messages = mutableListOf<LanMessage>()
        val saved = LanPermissions(next = true, seek = true)
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }, saved) { _, message -> messages += message }
        room.join("host", "Host", true)
        room.join("member", "Member", false)
        assertTrue(messages.filter { it.type == "WELCOME" }.all { it.room?.permissions == saved })
        room.request("member", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(true, true, true)))
        assertEquals(saved, room.snapshot().permissions)
        room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions()))
        assertEquals(LanPermissions(), room.snapshot().permissions)
        assertEquals(LanPermissions(), messages.last().event?.room?.permissions)
    }
    private class Fixture {
        var now = 1000.0
        val sent = mutableListOf<Pair<String, LanMessage>>()
        val room = LanRoomEngine("r", "room", LanClock { now }) { peer, message -> sent += peer to message }
        val song = ListenTogetherTrack("netease:1", "netease", "1", name = "Track", artist = "Artist", durationMs = 120_000)
        init { room.join("host", "Host", true); room.join("member", "Member", false); sent.clear() }
        fun play(song: ListenTogetherTrack = this.song) { room.request("host", LanMessage("PLAY", intent = "NEXT", track = song, sourceUrls = listOf("https://m.music.126.net/audio.mp3"))) }
        fun loaded(peer: String, generation: Long = 1) { room.request(peer, LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(song.stableKey), loadGeneration = generation)) }
        fun actions() = sent.filter { it.second.type == "SCHEDULED_ACTION" }
    }
    @Test fun waitsForActualInitiatorAndCorrectLoadGeneration() {
        val f = Fixture(); f.play()
        f.room.request("host", LanMessage("AUDIO_SOURCE_LOADED", loadGeneration = 1))
        f.loaded("member"); f.loaded("host", 0)
        assertTrue(f.actions().isEmpty())
        f.room.request("host", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource("wrong"), loadGeneration = 1))
        assertTrue(f.actions().isEmpty())
        f.loaded("host")
        assertEquals(2, f.actions().size)
        assertEquals(1400.0, f.actions().first().second.serverTimeToExecute!!, .0001)
        assertTrue(f.room.snapshot().playback.playing)
        f.loaded("host") // An acknowledgement after completion must be ignored.
        assertEquals(2, f.actions().size)
    }
    @Test fun sameTrackReloadRejectsPreviousGeneration() {
        val f = Fixture(); f.play(); f.play()
        f.loaded("host", 1); f.loaded("member", 1)
        assertTrue(f.actions().isEmpty())
        f.loaded("host", 2); f.loaded("member", 2)
        assertEquals(2L, f.room.snapshot().playback.revision)
        assertEquals(2, f.actions().size)
    }
    @Test fun timeoutNeverStartsPartiallyPreparedRoom() {
        val f = Fixture(); f.play(); f.now += 2999; f.room.tick(); assertTrue(f.actions().isEmpty())
        f.now += 87_001; f.room.observe("host", 0.0, 0.0); f.room.observe("member", 0.0, 0.0); f.room.tick(); assertTrue(f.actions().isEmpty())
        assertTrue(f.sent.any { it.second.code == "LOAD_TIMEOUT" })
        val empty = Fixture(); empty.play(); empty.room.leave("host"); empty.room.leave("member"); empty.now += 4000; empty.room.tick()
        assertTrue(empty.actions().isEmpty())
    }
    @Test fun departingMemberReleasesBarrier() {
        val f = Fixture(); f.play(); f.loaded("host"); f.room.leave("member")
        assertEquals(1, f.actions().size)
    }
    @Test fun permissionFailureDoesNotMutatePlaybackOrCancelPendingLoad() {
        val f = Fixture(); f.play(); val before = f.room.snapshot()
        f.room.request("member", LanMessage("PAUSE"))
        assertEquals(before.playback, f.room.snapshot().playback)
        assertEquals("DENIED", f.sent.last().second.code)
        f.loaded("host"); f.loaded("member"); assertTrue(f.room.snapshot().playback.playing)
    }
    @Test fun unauthorizedConfigurationAndUnknownCommandsAreRejected() {
        val f = Fixture(); f.room.request("member", LanMessage("SET_GLOBAL_VOLUME", volume = .5f))
        assertEquals(1f, f.room.snapshot().volume)
        f.room.request("host", LanMessage("UNSUPPORTED")); assertEquals("UNSUPPORTED", f.sent.last().second.code)
        f.room.request("host", LanMessage("SET_GLOBAL_VOLUME", volume = Float.NaN)); assertEquals(1f, f.room.snapshot().volume)
        f.room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "missing", volume = .4f)); assertEquals("STALE", f.sent.last().second.code)
    }
    @Test fun volumePermissionsAndHostIdentityAreServerOwned() {
        val f = Fixture()
        assertFalse(f.room.join("other", "Other", true))
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(true, true, true)))
        f.room.request("host", LanMessage("SET_GLOBAL_VOLUME", volume = .5f))
        f.room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "member", volume = .4f))
        assertEquals(.5f, f.room.snapshot().volume)
        assertEquals(.4f, f.room.snapshot().members.last().volume)
        f.room.request("member", LanMessage("PLAY", intent = "NEXT", track = f.song))
        assertEquals("LAN_HOST_PLAY", f.sent.last().second.type)
    }
    @Test fun nextPermissionCannotBeUsedToSeekAndPlayPermissionCannotSwitchTracks() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(next = true)))
        f.room.request("member", LanMessage("PLAY", intent = "SEEK", track = f.song, trackTimeSeconds = 30.0))
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(playPause = true)))
        f.room.request("member", LanMessage("PLAY", intent = "PLAY_PAUSE", track = f.song.copy(stableKey = "netease:2", audioId = "2")))
        assertEquals("DENIED", f.sent.last().second.code)
    }
    @Test fun pauseIsScheduledAndLateJoinGetsTrackAndPosition() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member"); f.now = 5000.0
        f.room.request("host", LanMessage("PAUSE"))
        val paused = f.room.snapshot().playback
        assertFalse(paused.playing); assertEquals(4.0, paused.positionSeconds, .0001)
        f.room.request("member", LanMessage("SYNC"))
        assertEquals("LAN_PAUSED_STATE", f.sent.last().second.type)
        assertEquals(f.song.stableKey, f.sent.last().second.track?.stableKey)
        assertEquals(paused.positionSeconds, f.sent.last().second.trackTimeSeconds!!, .0001)
    }
    @Test fun activeSyncProjectsToFutureExecutionTime() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member"); f.now = 5000.0
        f.room.request("member", LanMessage("SYNC"))
        val m = f.sent.last().second
        assertEquals(6900.0, m.serverTimeToExecute!!, .0001)
        assertEquals(5.5, m.scheduledAction!!.trackTimeSeconds, .0001)
    }
    @Test fun compensationCanReturnToZeroAndOfflinePeersStopInflatingDelay() {
        val f = Fixture(); f.room.observe("member", 500.0, 4000.0); f.play(); f.loaded("host"); f.loaded("member")
        assertEquals(5200.0, f.room.snapshot().playback.executeAtMs, .0001)
        f.room.observe("member", 500.0, 0.0); f.play(); f.loaded("host", 2); f.loaded("member", 2)
        assertEquals(1950.0, f.room.snapshot().playback.executeAtMs, .0001)
        f.room.leave("member"); f.play(); f.loaded("host", 3)
        assertEquals(1400.0, f.room.snapshot().playback.executeAtMs, .0001)
    }
    @Test fun outgoingMetadataNeverLeaksPlaybackLinks() {
        val f = Fixture(); f.play(f.song.copy(streamUrl = "https://secret", streamUrls = listOf("https://secret"), mediaUri = "content://private"))
        val track = f.sent.first().second.event!!.track!!
        assertNull(track.streamUrl); assertTrue(track.streamUrls.isEmpty()); assertNull(track.mediaUri)
    }
    @Test fun pauseAndConfigurationEdgeCasesKeepRoomConsistent() {
        val f = Fixture()
        f.room.request("host", LanMessage("PAUSE"))
        assertTrue(f.actions().isEmpty())
        f.room.request("host", LanMessage("LAN_PERMISSIONS"))
        assertEquals("INVALID", f.sent.last().second.code)
        f.room.request("host", LanMessage("SET_GLOBAL_VOLUME"))
        assertEquals("INVALID", f.sent.last().second.code)
        f.room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "member"))
        assertEquals("INVALID", f.sent.last().second.code)
        f.room.request("member", LanMessage("LIVENESS_PONG"))
        f.room.observe("missing", 30.0, 0.0)
        f.play(); f.room.request("host", LanMessage("PAUSE"))
        assertFalse(f.room.snapshot().playback.playing)
        f.room.request("host", LanMessage("PAUSE"))
        assertFalse(f.room.snapshot().playback.playing)
    }
    @Test fun mediaVolumeStartsAtDeviceLevelAndMembersCanChangeOnlyTheirOwnLevel() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "room", LanClock { 0.0 }) { _, m -> sent += m }
        assertTrue(room.join("host", "Host", true, .3f))
        assertTrue(room.join("member", "Member", false, .6f))
        assertEquals(.3f, room.snapshot().members.first().volume)
        room.request("host", LanMessage("SET_GLOBAL_VOLUME", volume = .4f))
        assertTrue(room.snapshot().members.all { it.volume == .4f })
        room.request("member", LanMessage("LAN_MEMBER_VOLUME", clientId = "member", volume = .2f))
        assertEquals(.2f, room.snapshot().members.last().volume)
        room.request("member", LanMessage("LAN_MEMBER_VOLUME", clientId = "host", volume = 1f))
        assertEquals("DENIED", sent.last().code)
        assertEquals(.4f, room.snapshot().members.first().volume)
    }
    @Test fun memberNextUsesHostQueueAndChecksPermissionAndTarget() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val request = LanMessage("LAN_REQUEST_STEP", audioSource = f.song.stableKey, trackTimeSeconds = 1.0)
        f.room.request("member", request); assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(next = true)))
        f.room.request("member", request.copy(audioSource = "stale")); assertEquals("STALE", f.sent.last().second.code)
        f.room.request("member", request.copy(trackTimeSeconds = 4.0)); assertEquals("INVALID", f.sent.last().second.code)
        f.room.request("member", request)
        assertEquals("host", f.sent.last().first); assertEquals("LAN_HOST_STEP", f.sent.last().second.type)
        f.room.leave("host"); f.room.request("member", request); assertEquals("STALE", f.sent.last().second.code)
    }
    @Test fun pauseDuringReplacementLoadKeepsTheNewTrackAndRejectsLateLoaded() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val next = f.song.copy(stableKey = "netease:2", audioId = "2", name = "Next")
        f.play(next)
        f.room.request("host", LanMessage("PAUSE"))
        val paused = f.room.snapshot().playback
        assertFalse(paused.playing); assertEquals(next.stableKey, paused.track?.stableKey)
        assertEquals(0.0, paused.positionSeconds, .0001)
        f.room.request("host", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(next.stableKey), loadGeneration = 2))
        assertEquals(paused, f.room.snapshot().playback)
    }
    @Test fun livenessAndInputLimitsAreBounded() {
        val f = Fixture(); assertFalse(f.room.join("x", " ", false))
        assertFalse(f.room.join("x", "x".repeat(25), false))
        repeat(14) { assertTrue(f.room.join("p$it", "Peer", false)) }; assertFalse(f.room.join("overflow", "Peer", false))
        f.now += 60_001; assertEquals(16, f.room.tick().size)
        assertTrue(f.room.snapshot().members.isEmpty())
    }
    @Test fun sharedCandidatesStayInSessionMessagesAndLateJoinReceivesThem() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        assertNull(f.room.snapshot().playback.track?.streamUrl)
        assertTrue(f.room.snapshot().playback.track!!.streamUrls.isEmpty())
        f.room.request("member", LanMessage("SYNC"))
        assertEquals(listOf("https://m.music.126.net/audio.mp3"), f.sent.last().second.sourceUrls)
    }
    @Test fun partialAudioAbortsBarrierAndDoesNotLoopOnSync() {
        val f = Fixture(); f.play()
        f.room.request("member", LanMessage("LAN_LOAD_FAILED", loadGeneration = 0, code = "AUDIO_DURATION"))
        assertTrue(f.sent.none { it.second.code == "AUDIO_DURATION" })
        f.room.request("member", LanMessage("LAN_LOAD_FAILED", loadGeneration = 1, code = "AUDIO_DURATION"))
        assertFalse(f.room.snapshot().playback.playing)
        f.room.request("member", LanMessage("SYNC"))
        assertEquals("AUDIO_DURATION", f.sent.last().second.code)
        assertTrue(f.actions().isEmpty())
        f.room.request("member", LanMessage("LAN_LOAD_FAILED"))
    }
    @Test fun absentOrOversizedSharedCandidatesAreRejected() {
        val f = Fixture()
        for (urls in listOf(emptyList(), listOf("file:///secret"), List(4) { "https://m.music.126.net/test" }, listOf("https://"+"x".repeat(12000)))) {
            f.room.request("host", LanMessage("PLAY", intent = "NEXT", track = f.song, sourceUrls = urls))
            assertEquals("FULL_AUDIO_REQUIRED", f.sent.last().second.code)
        }
    }

    @Test fun differentDownloadedContentCannotPassTheLoadingBarrier() {
        val f = Fixture(); f.play()
        f.room.request("host", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(f.song.stableKey), loadGeneration = 1, contentDigest = "a".repeat(64)))
        f.room.request("member", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(f.song.stableKey), loadGeneration = 1, contentDigest = "b".repeat(64)))
        assertTrue(f.actions().isEmpty()); assertTrue(f.sent.any { it.second.code == "AUDIO_CONTENT" })
    }

    @Test fun malformedFailureAndDigestReportsNeverUnlockPlayback() {
        val f = Fixture(); f.play()
        f.room.request("host", LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(f.song.stableKey), loadGeneration = 1, contentDigest = "bad"))
        f.loaded("member"); assertTrue(f.actions().isEmpty())
        f.room.request("host", LanMessage("LAN_LOAD_FAILED", loadGeneration = 1))
        assertEquals("SOURCE", f.sent.first { it.second.type == "LAN_ERROR" }.second.code)
        val second = Fixture(); second.play()
        second.room.request("host", LanMessage("LAN_LOAD_FAILED", loadGeneration = 1, code = "untrusted-code"))
        assertTrue(second.sent.any { it.second.code == "SOURCE" })
    }
    @Test fun sameCompleteFilePassesContentComparison() {
        val f = Fixture(); f.play()
        listOf("host", "member").forEach { id ->
            f.room.request(id, LanMessage("AUDIO_SOURCE_LOADED", source = LanSource(f.song.stableKey), loadGeneration = 1, contentDigest = "a".repeat(64)))
        }
        assertEquals(2, f.actions().size)
    }

}
