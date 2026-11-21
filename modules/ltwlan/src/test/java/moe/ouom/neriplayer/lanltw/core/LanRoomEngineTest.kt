package moe.ouom.neriplayer.lanltw.core

import org.junit.Assert.*
import org.junit.Test
import moe.ouom.neriplayer.data.model.lan.*
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack

class LanRoomEngineTest {
    @Test fun roomVolumeStartsAtHostMediaVolumeAndOnlyChangesWithGroupCommands() {
        val sent = mutableListOf<LanMessage>()
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }) { _, message -> sent += message }
        room.join("host", "Host", true, .4f)
        assertEquals(.4f, sent.first().room!!.volume)
        room.join("member", "Member", false, .2f)
        assertEquals(.4f, room.snapshot().volume)
        assertEquals(.2f, room.snapshot().members.last().volume)
        room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "host", volume = .6f))
        assertEquals(.4f, room.snapshot().volume)
        assertEquals(.6f, room.snapshot().members.first().volume)
        assertEquals(.2f, room.snapshot().members.last().volume)
        room.request("member", LanMessage("LAN_MEMBER_VOLUME", clientId = "member", volume = .3f))
        assertEquals(.4f, room.snapshot().volume)
        room.request("host", LanMessage("SET_GLOBAL_VOLUME", volume = .5f))
        assertEquals(.5f, room.snapshot().volume)
        assertTrue(room.snapshot().members.all { it.volume == .5f })
        val deviceStep = 8f / 15f
        room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "host", volume = deviceStep))
        assertEquals(.5f, room.snapshot().volume)
        assertEquals(deviceStep, room.snapshot().members.first().volume)
        assertEquals(.5f, room.snapshot().members.last().volume)
    }
    @Test fun savedPermissionsArePresentInFirstWelcomeAndOnlyHostCanChangeThem() {
        val messages = mutableListOf<LanMessage>()
        val saved = LanPermissions(playPause = false, volume = true)
        val room = LanRoomEngine("r", "Room", LanClock { 0.0 }, saved) { _, message -> messages += message }
        room.join("host", "Host", true)
        room.join("member", "Member", false)
        assertTrue(messages.filter { it.type == "WELCOME" }.all { it.room?.permissions == saved })
        room.request("member", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions()))
        assertEquals(saved, room.snapshot().permissions)
        room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions()))
        assertEquals(LanPermissions(), room.snapshot().permissions)
        assertEquals(LanPermissions(), messages.last().event?.room?.permissions)
    }
    @Test fun volumeGrantLetsMembersAdjustTheGroupAndHostButNotOtherMembers() {
        val f = Fixture()
        f.room.join("other", "Other", false, .2f)
        val group = LanMessage("SET_GLOBAL_VOLUME", volume = .5f)
        val hostVolume = LanMessage("LAN_MEMBER_VOLUME", clientId = "host", volume = .7f)
        fun deviceVolume(id: String) = f.room.snapshot().members.first { it.id == id }.volume

        f.room.request("member", group)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("member", hostVolume)
        assertEquals("DENIED", f.sent.last().second.code)
        assertEquals(1f, f.room.snapshot().volume)
        assertEquals(1f, deviceVolume("host"))

        val granted = LanPermissions(playPause = false, seek = false, volume = true)
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = granted))
        f.room.request("member", group)
        assertEquals(.5f, f.room.snapshot().volume)
        assertTrue(f.room.snapshot().members.all { it.volume == .5f })
        f.room.request("member", hostVolume)
        assertEquals(.7f, deviceVolume("host"))
        f.room.request("member", hostVolume.copy(clientId = "other"))
        assertEquals("DENIED", f.sent.last().second.code)
        assertEquals(.5f, deviceVolume("other"))
        f.room.request("member", hostVolume.copy(clientId = "member", volume = .3f))
        assertEquals(.3f, deviceVolume("member"))
        assertEquals(.5f, f.room.snapshot().volume)

        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = granted.copy(volume = false)))
        f.room.request("member", group.copy(volume = .8f))
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("member", hostVolume.copy(volume = .9f))
        assertEquals("DENIED", f.sent.last().second.code)
        assertEquals(.5f, f.room.snapshot().volume)
        assertEquals(.7f, deviceVolume("host"))
        f.room.request("member", hostVolume.copy(clientId = "member", volume = .4f))
        assertEquals(.4f, deviceVolume("member"))
        assertEquals(.5f, f.room.snapshot().volume)
    }
    private class Fixture {
        var now = 1000.0
        val sent = mutableListOf<Pair<String, LanMessage>>()
        val room = LanRoomEngine("r", "room", LanClock { now }, LanPermissions(playPause = false, seek = false)) { peer, message -> sent += peer to message }
        val song = ListenTogetherTrack("netease:1", "netease", "1", name = "Track", artist = "Artist", durationMs = 120_000)
        val stream = LanStreamResource("12345678-1234-1234-1234-123456789abc", "audio/mpeg")
        init { room.join("host", "Host", true); room.join("member", "Member", false); sent.clear() }
        fun requestStream(song: ListenTogetherTrack = this.song): Long {
            room.request("host", LanMessage("PLAY", intent = "NEXT", track = song))
            return checkNotNull(sent.last().second.loadGeneration)
        }
        fun publish(generation: Long, resource: LanStreamResource? = stream) {
            room.request("host", LanMessage("LAN_STREAM_PUBLISHED", loadGeneration = generation, streamResource = resource))
        }
        fun play(song: ListenTogetherTrack = this.song, resource: LanStreamResource? = stream) = publish(requestStream(song), resource)
        fun loaded(peer: String, generation: Long = 1) {
            room.request(peer, LanMessage("AUDIO_SOURCE_READY", mediaId = stream.mediaId, loadGeneration = generation))
        }
        fun loads() = sent.filter { it.second.event?.type == "LOAD_AUDIO_SOURCE" }
        fun actions() = sent.filter { it.second.type == "SCHEDULED_ACTION" }
    }
    @Test fun waitsForActualInitiatorAndCorrectLoadGeneration() {
        val f = Fixture(); f.play()
        f.room.request("host", LanMessage("AUDIO_SOURCE_READY", loadGeneration = 1))
        f.loaded("member"); f.loaded("host", 0)
        assertTrue(f.actions().isEmpty())
        f.room.request("host", LanMessage("AUDIO_SOURCE_READY", mediaId = "wrong", loadGeneration = 1))
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
        f.now += 12_001; f.room.tick(); assertTrue(f.actions().isEmpty())
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
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions()))
        f.room.request("host", LanMessage("SET_GLOBAL_VOLUME", volume = .5f))
        f.room.request("host", LanMessage("LAN_MEMBER_VOLUME", clientId = "member", volume = .4f))
        assertEquals(.5f, f.room.snapshot().volume)
        assertEquals(.4f, f.room.snapshot().members.last().volume)
        f.room.request("member", LanMessage("PLAY", intent = "NEXT", track = f.song))
        assertEquals("LAN_HOST_PLAY", f.sent.last().second.type)
    }
    @Test fun revokingProgressBlocksBothQueueDirectionsTrackSelectionAndSeekButAllowsPlayPause() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val changed = f.song.copy(stableKey = "netease:2", audioId = "2")
        val seek = LanMessage("PLAY", intent = "SEEK", track = f.song, trackTimeSeconds = 30.0)
        val steps = listOf(-1.0, 1.0).map { LanMessage("LAN_REQUEST_STEP", audioSource = f.song.stableKey, trackTimeSeconds = it) }
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(playPause = false)))
        for (step in steps) {
            f.room.request("member", step)
            assertEquals("host" to LanMessage("LAN_HOST_STEP", trackTimeSeconds = step.trackTimeSeconds), f.sent.last())
        }
        f.room.request("member", LanMessage("PAUSE"))
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("member", seek)
        f.loaded("host", 2); f.loaded("member", 2)
        assertEquals(30.0, f.room.snapshot().playback.positionSeconds, .0001)

        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(seek = false)))
        for (request in steps + listOf(seek, LanMessage("PLAY", intent = "NEXT", track = changed),
                LanMessage("PLAY", intent = "PLAY_PAUSE", track = changed))) {
            f.sent.clear()
            f.room.request("member", request)
            assertEquals("DENIED", f.sent.single().second.code)
        }
        f.room.request("member", LanMessage("PAUSE"))
        assertFalse(f.room.snapshot().playback.playing)
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
        val f = Fixture()
        f.play(f.song.copy(streamUrl = "https://secret", streamUrls = listOf("https://secret"),
            mediaUri = "content://private", coverUrl = "https://private/cover"))
        val track = f.loads().first().second.event!!.track!!
        assertNull(track.streamUrl); assertTrue(track.streamUrls.isEmpty()); assertNull(track.mediaUri); assertNull(track.coverUrl)
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
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(playPause = false)))
        f.room.request("member", request.copy(audioSource = "stale")); assertEquals("STALE", f.sent.last().second.code)
        f.room.request("member", request.copy(trackTimeSeconds = 4.0)); assertEquals("INVALID", f.sent.last().second.code)
        f.room.request("member", request)
        assertEquals("host", f.sent.last().first); assertEquals("LAN_HOST_STEP", f.sent.last().second.type)
        f.room.leave("host"); f.room.request("member", request); assertEquals("STALE", f.sent.last().second.code)
    }
    @Test fun pauseDuringReplacementLoadKeepsTheNewTrackAndDoesNotStartIt() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val next = f.song.copy(stableKey = "netease:2", audioId = "2", name = "Next")
        f.play(next)
        f.room.request("host", LanMessage("PAUSE"))
        val paused = f.room.snapshot().playback
        assertFalse(paused.playing); assertEquals(next.stableKey, paused.track?.stableKey)
        assertEquals(0.0, paused.positionSeconds, .0001)
        f.loaded("host", 2)
        assertEquals(paused, f.room.snapshot().playback)
        f.loaded("member", 2)
        assertFalse(f.room.snapshot().playback.playing)
        assertEquals("PAUSE", f.actions().last().second.scheduledAction?.type)
    }
    @Test fun livenessAndInputLimitsAreBounded() {
        val f = Fixture(); assertFalse(f.room.join("x", " ", false))
        assertFalse(f.room.join("x", "x".repeat(25), false))
        repeat(14) { assertTrue(f.room.join("p$it", "Peer", false)) }; assertFalse(f.room.join("overflow", "Peer", false))
        f.now += 60_001
        assertTrue("the first tick after sleep must give connected peers time to reply", f.room.tick().isEmpty())
        assertEquals(16, f.sent.count { it.second.type == "LIVENESS_PING" })
        f.room.request("host", LanMessage("LIVENESS_PONG"))
        f.now += 2_500
        assertTrue(f.room.tick().isEmpty())
        f.now += 2_500
        assertEquals(15, f.room.tick().size)
        assertEquals(listOf("host"), f.room.snapshot().members.map { it.id })
    }
    @Test fun aPeerCanDisconnectDuringAProbeWithoutSkippingOtherMembers() {
        var now = 0.0
        lateinit var room: LanRoomEngine
        val probed = mutableListOf<String>()
        room = LanRoomEngine("r", "Room", LanClock { now }) { id, message ->
            if (message.type == "LIVENESS_PING") {
                probed.add(id)
                if (id == "member") room.leave(id)
            }
        }
        room.join("host", "Host", true)
        room.join("member", "Member", false)
        room.join("other", "Other", false)
        now = 90_000.0
        assertTrue(room.tick().isEmpty())
        assertEquals(listOf("host", "member", "other"), probed)
        assertEquals(listOf("host", "other"), room.snapshot().members.map { it.id })
    }
    @Test fun lateJoinReceivesTheCanonicalRelayIdentityWithoutAFileHash() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.room.join("late", "Late", false)
        f.room.request("late", LanMessage("SYNC"))
        val (peer, plan) = f.actions().last()
        assertEquals("late", peer)
        assertEquals(f.stream, plan.streamResource)
        assertNull(plan.streamResource?.byteLength)
    }

    @Test fun loadFailureIsScopedToCurrentMediaAndGeneration() {
        val f = Fixture(); f.play()
        val failure = LanMessage("LAN_LOAD_FAILED", mediaId = f.stream.mediaId, loadGeneration = 1, code = "AUDIO_FORMAT")
        f.room.request("host", failure.copy(loadGeneration = 0))
        f.room.request("host", failure.copy(mediaId = "wrong"))
        assertTrue(f.room.snapshot().playback.preparing)
        f.room.request("host", failure)
        assertFalse(f.room.snapshot().playback.preparing)
        f.room.request("member", LanMessage("SYNC"))
        assertEquals("AUDIO_FORMAT", f.sent.last().second.code)
        assertTrue(f.actions().isEmpty())
    }

    @Test fun invalidStreamPublicationsCannotStartPlayback() {
        val fixture = Fixture()
        val invalid = listOf(null, fixture.stream.copy(mediaId = "https://private/audio"),
            fixture.stream.copy(mimeType = "text/html"), fixture.stream.copy(byteLength = 0))
        for (resource in invalid) {
            val f = Fixture()
            f.play(resource = resource)
            assertEquals("FULL_AUDIO_REQUIRED", f.sent.first { it.second.type == "LAN_ERROR" }.second.code)
            assertFalse(f.room.snapshot().playback.playing)
        }
    }

    @Test fun progressOnlyMemberCanMovePausedPositionWithoutStartingPlayback() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.room.request("host", LanMessage("PAUSE"))
        f.room.request("host", LanMessage("LAN_PERMISSIONS", permissions = LanPermissions(playPause = false)))
        f.room.request("member", LanMessage("PLAY", intent = "SEEK", track = f.song, trackTimeSeconds = 25.0))
        assertEquals(1, f.sent.count { it.second.type == "LAN_HOST_PLAY" })
        assertEquals(f.stream, f.loads().last().second.event?.streamResource)
        f.loaded("host", 3); f.loaded("member", 3)
        assertFalse(f.room.snapshot().playback.playing)
        assertEquals(25.0, f.room.snapshot().playback.positionSeconds, .0001)
        assertEquals("PAUSE", f.actions().last().second.scheduledAction!!.type)
    }

    @Test fun seekDuringPlaybackBuffersTheNewPositionUsingTheExistingStream() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.room.request("host", LanMessage("PLAY", intent = "SEEK", track = f.song, trackTimeSeconds = 40.0))
        f.loaded("host", 2); f.loaded("member", 2)
        assertTrue(f.room.snapshot().playback.playing)
        assertEquals(40.0, f.room.snapshot().playback.positionSeconds, .0001)
        assertEquals(f.stream, f.actions().last().second.streamResource)
    }

    @Test fun repeatedPlayWhilePreparingAndResumeDoNotPublishOrReloadTheStream() {
        val f = Fixture()
        val generation = f.requestStream()
        val resume = LanMessage("PLAY", intent = "PLAY_PAUSE", track = f.song)
        f.room.request("host", resume)
        assertTrue(f.room.snapshot().playback.preparing)
        f.publish(generation)
        f.room.request("host", resume)
        f.loaded("host"); f.loaded("member")
        f.room.request("host", LanMessage("PAUSE"))
        f.room.request("host", resume)
        f.room.request("host", resume)
        assertEquals(1, f.sent.count { it.second.type == "LAN_HOST_PLAY" })
        assertEquals(2, f.loads().size)
        assertTrue(f.room.snapshot().playback.playing)
    }

    @Test fun onlyHostCanPublishAndRetriedPreparationIgnoresTheOldGeneration() {
        val f = Fixture()
        val first = f.requestStream()
        val published = LanMessage("LAN_STREAM_PUBLISHED", loadGeneration = first, streamResource = f.stream)
        f.room.request("member", published)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", LanMessage("LAN_STREAM_FAILED", loadGeneration = first))
        val retry = LanMessage("PLAY", intent = "RETRY", track = f.song)
        f.room.request("member", retry)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", retry)
        val second = checkNotNull(f.sent.last().second.loadGeneration)
        f.publish(first)
        assertTrue(f.loads().isEmpty())
        val replacement = f.stream.copy(mediaId = "87654321-1234-1234-1234-123456789abc")
        f.publish(second, replacement)
        f.loaded("host", second)
        assertTrue(f.actions().isEmpty())
        for (id in listOf("host", "member")) {
            f.room.request(id, LanMessage("AUDIO_SOURCE_READY", loadGeneration = second, mediaId = replacement.mediaId))
        }
        assertEquals(replacement, f.actions().last().second.streamResource)
    }

    @Test fun slowMetadataIsBroadcastIndependentlyAndIncludedWhenALateMemberSyncs() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        assertTrue(f.room.snapshot().playback.playing)
        assertNull(f.actions().last().second.presentationResource)
        val resource = LanFileResource("a".repeat(36), "b".repeat(64), 96_000, "application/json")
        val published = LanMessage("LAN_PRESENTATION_PUBLISHED", mediaId = f.stream.mediaId, presentationResource = resource)
        f.room.request("host", published.copy(mediaId = "wrong"))
        f.room.request("host", published.copy(presentationResource = null))
        f.room.request("host", published.copy(presentationResource = resource.copy(byteLength = 0)))
        assertTrue(f.sent.none { it.second.type == "LAN_PRESENTATION" })
        f.room.request("host", published)
        val presentation = f.sent.last().second
        assertEquals("LAN_PRESENTATION", presentation.type)
        assertEquals(f.stream.mediaId, presentation.mediaId)
        assertEquals(f.song.stableKey, presentation.track?.stableKey)
        val lateCover = resource.copy(mediaId = "c".repeat(36), sha256 = "d".repeat(64))
        f.room.request("host", published.copy(presentationResource = lateCover))
        assertEquals(lateCover, f.sent.last().second.presentationResource)
        f.room.join("late", "Late", false)
        f.room.request("late", LanMessage("SYNC"))
        assertEquals(lateCover, f.actions().last().second.presentationResource)

        f.room.request("host", LanMessage("PAUSE"))
        f.room.join("late-paused", "Late while paused", false)
        f.room.request("late-paused", LanMessage("SYNC"))
        assertEquals("LAN_PAUSED_STATE", f.sent.last().second.type)
        assertEquals(lateCover, f.sent.last().second.presentationResource)
    }

    @Test fun invalidPresentationDescriptorDoesNotBlockOnlineAudio() {
        val valid = LanFileResource("a".repeat(36), "b".repeat(64), 96_000, "application/json")
        for (invalid in listOf(valid.copy(mimeType = "image/png"), valid.copy(byteLength = 0))) {
            val f = Fixture()
            f.play(resource = f.stream.copy(presentation = invalid))
            assertNull(f.loads().first().second.presentationResource)
            f.loaded("host"); f.loaded("member")
            assertTrue(f.room.snapshot().playback.playing)
        }
    }

    @Test fun activeHostStreamFailureReleasesRoomForRetry() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val failure = LanMessage("LAN_STREAM_FAILED", mediaId = f.stream.mediaId, code = "LOCAL_TRANSFER")
        f.room.request("host", failure.copy(mediaId = "wrong"))
        assertTrue(f.room.snapshot().playback.playing)
        f.room.request("host", failure)
        assertFalse(f.room.snapshot().playback.playing)
        assertTrue(f.sent.any { it.second.code == "LOCAL_TRANSFER" })
    }

    @Test fun firstHostPlaybackKeepsItsPositionAndRejectsOtherStartCommands() {
        val f = Fixture()
        val start = LanMessage("PLAY", intent = "START", track = f.song, trackTimeSeconds = 31.0)
        f.room.request("member", start)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", start)
        val generation = checkNotNull(f.sent.last().second.loadGeneration)
        f.publish(generation)
        f.loaded("host"); f.loaded("member")
        assertTrue(f.room.snapshot().playback.playing)
        assertEquals(31.0, f.actions().last().second.scheduledAction!!.trackTimeSeconds, .0001)
        f.room.request("host", start)
        assertEquals("DENIED", f.sent.last().second.code)
    }

    @Test fun pausedHostPreparesAtItsPositionAndWaitsForExplicitPlay() {
        val f = Fixture()
        val start = LanMessage("PLAY", intent = "START_PAUSED", track = f.song, trackTimeSeconds = 31.0)
        f.room.request("member", start)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", start)
        val generation = checkNotNull(f.sent.last().second.loadGeneration)
        f.publish(generation)
        f.loaded("host", generation); f.loaded("member", generation)
        assertFalse(f.room.snapshot().playback.playing)
        assertEquals(31.0, f.room.snapshot().playback.positionSeconds, .0001)
        assertEquals(listOf("PAUSE", "PAUSE"), f.actions().map { it.second.scheduledAction!!.type })
        f.room.request("host", start)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", LanMessage("PLAY", intent = "PLAY_PAUSE", track = f.song))
        assertTrue(f.room.snapshot().playback.playing)
        assertEquals("PLAY", f.actions().last().second.scheduledAction!!.type)
    }

    @Test fun initialPlaybackRestartsForAbsentOrInvalidPreviousPosition() {
        val f = Fixture()
        for (position in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 120.0)) {
            assertEquals(0.0, LanRoomPolicy.initialPosition(f.song, position), .0001)
        }
    }

    @Test fun pauseAndPlayCanChangeTheRequestedStateDuringHostPreparation() {
        val f = Fixture()
        val first = f.requestStream()
        f.room.request("host", LanMessage("PAUSE"))
        f.room.request("host", LanMessage("PLAY", track = f.song))
        f.publish(first)
        f.loaded("host"); f.loaded("member")
        assertTrue(f.room.snapshot().playback.playing)
        assertEquals(1, f.sent.count { it.second.type == "LAN_HOST_PLAY" })
    }

    @Test fun callbacksForOldRelayDoNotInterruptResolutionOfTheNextTrack() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val next = f.song.copy(stableKey = "netease:2", audioId = "2")
        val generation = f.requestStream(next)
        f.room.request("host", LanMessage("LAN_STREAM_FAILED", loadGeneration = generation - 1,
            mediaId = f.stream.mediaId, code = "SOURCE"))
        f.room.request("host", LanMessage("LAN_PRESENTATION_PUBLISHED", mediaId = f.stream.mediaId))
        assertTrue(f.room.snapshot().playback.preparing)
        assertEquals(next.stableKey, f.room.snapshot().playback.track?.stableKey)
        f.publish(generation)
        f.loaded("host", generation); f.loaded("member", generation)
        assertTrue(f.room.snapshot().playback.playing)
    }

    @Test fun ordinaryPlayAfterFailureRequestsANewRelay() {
        val f = Fixture()
        val initial = f.requestStream()
        f.room.request("host", LanMessage("LAN_STREAM_FAILED", loadGeneration = initial, code = "SOURCE"))
        f.room.request("host", LanMessage("PLAY", track = f.song))
        assertEquals("LAN_HOST_PLAY", f.sent.last().second.type)
        assertTrue(f.room.snapshot().playback.preparing)
    }

    @Test fun timeoutExcludesSlowMemberFromInitialStartAndItCanCatchUp() {
        val f = Fixture(); f.play(); f.loaded("host")
        f.room.observe("member", 4500.0, 5000.0)
        f.now += 15_000; f.room.tick()
        assertEquals(listOf("host"), f.actions().map { it.first })
        assertEquals(16_400.0, f.room.snapshot().playback.executeAtMs, .0001)
        assertTrue(f.sent.any { it.first == "member" && it.second.code == "LOAD_TIMEOUT" })
        f.room.request("member", LanMessage("SYNC"))
        assertEquals("member", f.actions().last().first)
        assertEquals(f.stream, f.actions().last().second.streamResource)
    }

    @Test fun failedGuestDoesNotBlockTheReadyHost() {
        val f = Fixture(); f.play()
        f.room.request("member", LanMessage("LAN_LOAD_FAILED", loadGeneration = 1,
            mediaId = f.stream.mediaId, code = "AUDIO_FORMAT"))
        assertTrue(f.actions().isEmpty())
        assertTrue(f.sent.any { it.first == "member" && it.second.code == "AUDIO_FORMAT" })
        f.loaded("host")
        assertEquals(listOf("host"), f.actions().map { it.first })
    }

    @Test fun unseekableHostCanRestartFromZeroAfterRejectingTheRequestedPosition() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.room.request("host", LanMessage("PLAY", intent = "SEEK", track = f.song, trackTimeSeconds = 31.0))
        assertEquals(31.0, f.loads().last().second.event!!.trackTimeSeconds!!, .0001)
        f.room.request("host", LanMessage("LAN_LOAD_FAILED", loadGeneration = 2,
            mediaId = f.stream.mediaId, code = "AUDIO_NOT_SEEKABLE"))
        val failed = f.room.snapshot().playback
        assertFalse(failed.playing)
        assertEquals(0.0, failed.positionSeconds, .0001)
        assertTrue(f.sent.any { it.first == "member" && it.second.code == "AUDIO_NOT_SEEKABLE" })
        f.room.request("host", LanMessage("PLAY", intent = "RETRY", track = f.song))
        val retry = checkNotNull(f.sent.last().second.loadGeneration)
        f.publish(retry)
        f.loaded("host", retry); f.loaded("member", retry)
        assertTrue(f.room.snapshot().playback.playing)
        assertEquals(0.0, f.actions().last().second.scheduledAction!!.trackTimeSeconds, .0001)
    }

    @Test fun unseekableGuestCannotAbortTheHostSeek() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.room.request("host", LanMessage("PLAY", intent = "SEEK", track = f.song, trackTimeSeconds = 31.0))
        f.room.request("member", LanMessage("LAN_LOAD_FAILED", loadGeneration = 2,
            mediaId = f.stream.mediaId, code = "AUDIO_NOT_SEEKABLE"))
        f.loaded("host", 2)
        assertEquals("host", f.actions().last().first)
        assertEquals(31.0, f.actions().last().second.scheduledAction!!.trackTimeSeconds, .0001)
        val recipients = f.sent.filter { it.second.code == "AUDIO_NOT_SEEKABLE" }.map { it.first }
        assertEquals(listOf("member"), recipients)
    }

    @Test fun hostNaturalEndResetsTheNextPlayEvenWhenCatalogDurationIsLonger() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        f.now += 30_000
        f.room.request("host", LanMessage("LAN_TRACK_FINISHED", audioSource = f.song.stableKey,
            mediaId = f.stream.mediaId, revision = 1))
        val stopped = f.room.snapshot().playback
        assertFalse(stopped.playing)
        assertEquals(0.0, stopped.positionSeconds, .0001)
        assertEquals("PAUSE", f.actions().last().second.scheduledAction?.type)
        f.room.request("host", LanMessage("PLAY", track = f.song))
        assertEquals(0.0, f.room.snapshot().playback.positionSeconds, .0001)
        assertEquals("PLAY", f.actions().last().second.scheduledAction?.type)
    }

    @Test fun guestAndStaleFinishedReportsLeaveTheCurrentPlaybackIntact() {
        val f = Fixture(); f.play(); f.loaded("host"); f.loaded("member")
        val finished = LanMessage("LAN_TRACK_FINISHED", audioSource = f.song.stableKey,
            mediaId = f.stream.mediaId, revision = 1)
        f.room.request("member", finished)
        assertEquals("DENIED", f.sent.last().second.code)
        f.room.request("host", finished.copy(audioSource = "old-track"))
        f.room.request("host", finished.copy(mediaId = "old-media"))
        f.room.request("host", finished.copy(revision = 0))
        assertTrue(f.room.snapshot().playback.playing)
        f.now += 5_000
        f.room.request("host", LanMessage("PAUSE"))
        val paused = f.room.snapshot().playback
        f.room.request("host", finished.copy(revision = paused.revision))
        assertTrue(paused.positionSeconds > 0)
        assertEquals(paused, f.room.snapshot().playback)
    }

    @Test fun subsonicRequiresAnOpaqueAliasBeforeItCanPublishRelayAudio() {
        val f = Fixture()
        val secret = "private-media-id"
        val alias = "a".repeat(64)
        val track = f.song.copy(channelId = "subsonic", stableKey = "subsonic:$secret", audioId = secret)
        f.room.request("host", LanMessage("PLAY", intent = "NEXT", track = track))
        assertEquals("SOURCE", f.sent.last().second.code)
        f.play(track.copy(stableKey = "subsonic:$alias", audioId = alias))
        f.loaded("host"); f.loaded("member")
        assertEquals(alias, f.room.snapshot().playback.track?.audioId)
        assertTrue(f.room.snapshot().playback.playing)
    }
}
