package moe.ouom.neriplayer.listentogether.session.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import moe.ouom.neriplayer.core.player.url.ShareableListenTogetherStreamResolution
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherConnectionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomSettings
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherControllerLinkOwnerTest {
    private val song = SongItem(
        id = 42L,
        name = "Track",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 1_000L,
        coverUrl = null,
        channelId = "netease",
        audioId = "42"
    )
    private val track = requireNotNull(song.toListenTogetherTrackOrNull())

    @Test
    fun `listener requests missing link once during throttle and explicit refresh bypasses it`() = runTest {
        val session = FakeSession(role = "listener", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song)
        var nowMs = 1_000L
        val owner = owner(session, playback) { nowMs }

        owner.maybeRequest(session.room, "join")
        owner.maybeRequest(session.room, "duplicate")
        assertEquals(listOf("REQUEST_LINK"), session.sent.map { it.type })
        owner.maybeRequest(session.room, "refresh", force = true, bypassThrottle = true)
        assertEquals(2, session.sent.size)
        assertEquals(true, session.sent.last().forceRefresh)

        nowMs += 4_000L
        owner.maybeRequest(session.room, "later")
        assertEquals(3, session.sent.size)
    }

    @Test
    fun `listener does not request link already supplied by room or local playback`() = runTest {
        val session = FakeSession(role = "listener", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song)
        val owner = owner(session, playback) { 1_000L }

        val resolvedTrack = track.copy(streamUrl = "https://music.126.net/audio.mp3")
        owner.maybeRequest(session.room.copy(queue = listOf(resolvedTrack), track = resolvedTrack), "room")
        playback.hasLocalDirectStream = true
        owner.maybeRequest(session.room, "local")
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun `listener requests only while connected to an active sharing room`() = runTest {
        val session = FakeSession(role = "listener", room = roomState(controller = "host"))
        val owner = owner(session, FakePlayback(song = song)) { 1_000L }

        session.snapshot = session.snapshot.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        owner.maybeRequest(session.room, "disconnected")
        session.snapshot = session.snapshot.copy(connectionState = ListenTogetherConnectionState.CONNECTED)
        owner.maybeRequest(session.room.copy(roomStatus = "closed"), "closed")
        owner.maybeRequest(session.room.copy(settings = ListenTogetherRoomSettings(shareAudioLinks = false)), "private")
        owner.maybeRequest(session.room.copy(queue = emptyList(), track = null), "empty")
        val blankKey = track.copy(stableKey = "")
        owner.maybeRequest(session.room.copy(queue = listOf(blankKey), track = blankKey), "invalid")
        session.snapshot = session.snapshot.copy(userUuid = "host", role = "controller")
        owner.maybeRequest(session.room, "controller")

        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun `two distinct unavailable signals suppress requests until room stream is restored`() = runTest {
        val session = FakeSession(role = "listener", room = roomState(controller = "host"))
        val owner = owner(session, FakePlayback(song = song)) { 1_000L }

        assertTrue(owner.markUnavailable(session.room, track.stableKey, "first"))
        assertFalse(owner.markUnavailable(session.room, track.stableKey, "second"))
        assertTrue(owner.isUnavailable(session.room.roomId, track.stableKey))
        owner.maybeRequest(session.room, "unavailable")
        assertTrue(session.sent.isEmpty())
        owner.maybeRequest(session.room, "confirmed_refresh", force = true, bypassThrottle = true)
        assertEquals(listOf("REQUEST_LINK"), session.sent.map { it.type })

        val resolvedTrack = track.copy(streamUrl = "https://music.126.net/audio.mp3")
        owner.reconcileAvailability(session.room.copy(queue = listOf(resolvedTrack), track = resolvedTrack))
        assertFalse(owner.isUnavailable(session.room.roomId, track.stableKey))
    }

    @Test
    fun `unavailable signal ignores stale target and already shared stream`() = runTest {
        val session = FakeSession(role = "listener", room = roomState(controller = "host"))
        val owner = owner(session, FakePlayback(song = song)) { 1_000L }
        val resolvedTrack = track.copy(streamUrl = "https://music.126.net/audio.mp3")

        assertFalse(owner.markUnavailable(session.room, "different", "stale"))
        assertFalse(owner.markUnavailable(session.room.copy(track = resolvedTrack), track.stableKey, "shared"))
        assertFalse(owner.markUnavailable(session.room.copy(queue = emptyList(), track = null), null, "empty"))
        session.snapshot = session.snapshot.copy(userUuid = "host", role = "controller")
        assertFalse(owner.markUnavailable(session.room, track.stableKey, "controller"))
        assertFalse(owner.isUnavailable(session.room.roomId, track.stableKey))
    }

    @Test
    fun `unavailable signal without explicit target applies to current track`() = runTest {
        val session = FakeSession(role = "listener", room = roomState(controller = "host"))
        val owner = owner(session, FakePlayback(song = song)) { 1_000L }

        assertTrue(owner.markUnavailable(session.room, null, "first"))
        assertFalse(owner.markUnavailable(session.room, "  ", "second"))
        assertTrue(owner.isUnavailable(session.room.roomId, track.stableKey))
    }

    @Test
    fun `resolved stream publishes only for the still current controller track`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song)
        playback.resolution = ShareableListenTogetherStreamResolution(listOf("https://audio"), false)
        val owner = owner(session, playback) { 1_000L }

        owner.resolveAndPublish(track.stableKey, "ready")
        runCurrent()
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })
        assertEquals(listOf("https://audio"), session.sent.single().track?.streamUrls)

        session.sent.clear()
        playback.onResolve = { playback.song = null }
        owner.resolveAndPublish(track.stableKey, "stale")
        runCurrent()
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun `controller ignores disconnected and stale song link requests`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song)
        val owner = owner(session, playback) { 1_000L }

        owner.resolveAndPublish("other", "stale")
        playback.song = null
        owner.resolveAndPublish(track.stableKey, "missing")
        session.snapshot = session.snapshot.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        playback.song = song
        owner.resolveAndPublish(track.stableKey, "disconnected")
        runCurrent()

        assertEquals(0, playback.resolveCount)
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun `controller does not start duplicate resolution for the same track`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song).apply {
            resolutionPending = true
            resolution = ShareableListenTogetherStreamResolution(listOf("https://music.126.net/audio.mp3"), false)
        }
        val owner = owner(session, playback, timing = ListenTogetherControllerLinkTiming(
            playbackResolutionPollMs = 10L,
            playbackResolutionPollCount = 3
        )) { 1_000L }

        owner.resolveAndPublish(track.stableKey, "first")
        runCurrent()
        owner.resolveAndPublish(track.stableKey, "duplicate")
        playback.resolutionPending = false
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(1, playback.resolveCount)
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })
    }

    @Test
    fun `controller publishes current link when sharing is enabled for matching track`() = runTest {
        val room = roomState(controller = "host")
        val session = FakeSession(role = "controller", room = room)
        val playback = FakePlayback(song = song).apply {
            resolution = ShareableListenTogetherStreamResolution(listOf("https://music.126.net/audio.mp3"), false)
        }
        val owner = owner(session, playback) { 1_000L }

        owner.maybePublishAfterAudioSharingEnabled(null, room, "initial")
        owner.maybePublishAfterAudioSharingEnabled(
            room.copy(settings = ListenTogetherRoomSettings(shareAudioLinks = false)),
            room.copy(track = track.copy(stableKey = "different"), queue = emptyList()),
            "other_track"
        )
        assertTrue(session.sent.isEmpty())

        owner.maybePublishAfterAudioSharingEnabled(
            room.copy(settings = ListenTogetherRoomSettings(shareAudioLinks = false)),
            room,
            "enabled"
        )
        runCurrent()
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })

        session.sent.clear()
        owner.maybePublishCurrentLink("current")
        runCurrent()
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })
    }

    @Test
    fun `controller link publication respects connection and sharing state`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song).apply {
            resolution = ShareableListenTogetherStreamResolution(listOf("https://music.126.net/audio.mp3"), false)
        }
        val owner = owner(session, playback) { 1_000L }

        session.snapshot = session.snapshot.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        owner.maybePublishCurrentLink("disconnected")
        session.snapshot = session.snapshot.copy(connectionState = ListenTogetherConnectionState.CONNECTED)
        session.room = session.room.copy(settings = ListenTogetherRoomSettings(shareAudioLinks = false))
        owner.maybePublishCurrentLink("private")
        session.room = session.room.copy(settings = ListenTogetherRoomSettings(shareAudioLinks = true))
        session.snapshot = session.snapshot.copy(userUuid = "listener", role = "listener")
        owner.maybePublishCurrentLink("listener")
        runCurrent()

        assertTrue(session.sent.isEmpty())
        assertEquals(0, playback.resolveCount)
    }

    @Test
    fun `controller waits for active playback resolution before publishing`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song).apply {
            resolutionPending = true
            resolution = ShareableListenTogetherStreamResolution(listOf("https://music.126.net/audio.mp3"), false)
        }
        val owner = owner(session, playback, timing = ListenTogetherControllerLinkTiming(
            playbackResolutionPollMs = 10L,
            playbackResolutionPollCount = 3
        )) { 1_000L }

        owner.resolveAndPublish(track.stableKey, "pending")
        runCurrent()
        assertEquals(0, playback.resolveCount)
        playback.resolutionPending = false
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(1, playback.resolveCount)
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })
    }

    @Test
    fun `controller retries missing stream before publishing unavailable`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song)
        val owner = owner(session, playback, timing = ListenTogetherControllerLinkTiming(
            resolutionRetryDelayMs = 10L,
            resolutionAttempts = 2
        )) { 1_000L }

        owner.resolveAndPublish(track.stableKey, "missing")
        runCurrent()
        assertEquals(1, playback.resolveCount)
        assertTrue(session.sent.isEmpty())
        advanceTimeBy(10L)
        runCurrent()
        assertEquals(2, playback.resolveCount)
        assertEquals(listOf("LINK_UNAVAILABLE"), session.sent.map { it.type })
    }

    @Test
    fun `clearing owner cancels a pending retry`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song)
        val owner = owner(session, playback, timing = ListenTogetherControllerLinkTiming(
            resolutionRetryDelayMs = 10L,
            resolutionAttempts = 2
        )) { 1_000L }

        owner.resolveAndPublish(track.stableKey, "closing")
        runCurrent()
        owner.clear()
        advanceTimeBy(10L)
        runCurrent()
        assertEquals(1, playback.resolveCount)
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun `link request resolves only a controller target with a stable key`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        val playback = FakePlayback(song = song).apply {
            resolution = ShareableListenTogetherStreamResolution(listOf("https://audio.test/track"), false)
        }
        val owner = owner(session, playback) { 1_000L }
        owner.onLinkRequested(ListenTogetherSocketEnvelope(type = "link_requested"))
        owner.onLinkRequested(ListenTogetherSocketEnvelope(type = "link_requested", requestTrackStableKey = "other"))
        runCurrent()
        assertTrue(session.sent.isEmpty())

        owner.onLinkRequested(ListenTogetherSocketEnvelope(type = "link_requested", requestTrackStableKey = track.stableKey))
        runCurrent()
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })

        session.sent.clear()
        owner.onLinkRequested(ListenTogetherSocketEnvelope(type = "link_requested", track = track))
        runCurrent()
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })

        session.sent.clear()
        session.snapshot = session.snapshot.copy(userUuid = "listener", role = "listener")
        owner.onLinkRequested(ListenTogetherSocketEnvelope(type = "link_requested", requestTrackStableKey = track.stableKey))
        runCurrent()
        assertTrue(session.sent.isEmpty())
    }

    @Test
    fun `resolved media url triggers the current controller link or heartbeat fallback`() = runTest {
        val session = FakeSession(role = "controller", room = roomState(controller = "host"))
        session.snapshot = session.snapshot.copy(roomId = "room")
        val playback = FakePlayback(song = song).apply {
            resolution = ShareableListenTogetherStreamResolution(listOf("https://audio.test/track"), false)
        }
        val owner = owner(session, playback) { 1_000L }
        owner.onResolvedStreamUrlChanged(null)
        owner.onResolvedStreamUrlChanged("content://media/track")
        assertTrue(session.sent.isEmpty())

        owner.onResolvedStreamUrlChanged("https://audio.test/track")
        runCurrent()
        assertEquals(listOf("LINK_READY"), session.sent.map { it.type })

        session.sent.clear()
        session.snapshot = session.snapshot.copy(roomId = null)
        owner.onResolvedStreamUrlChanged("https://audio.test/track")
        assertTrue(session.sent.isEmpty())
        session.snapshot = session.snapshot.copy(roomId = "room")
        session.room = session.room.copy(settings = session.room.settings.copy(shareAudioLinks = false))
        owner.onResolvedStreamUrlChanged("https://audio.test/track")
        assertTrue(session.sent.isEmpty())
        session.room = session.room.copy(settings = session.room.settings.copy(shareAudioLinks = true))
        session.snapshot = session.snapshot.copy(userUuid = "listener", role = "listener")
        owner.onResolvedStreamUrlChanged("https://audio.test/track")
        assertTrue(session.sent.isEmpty())
        session.snapshot = session.snapshot.copy(userUuid = "host", role = "controller")

        session.sent.clear()
        playback.song = null
        owner.onResolvedStreamUrlChanged("https://audio.test/track")
        assertEquals(listOf("stream_url_resolved"), session.heartbeatReasons)

        session.heartbeatReasons.clear()
        session.snapshot = session.snapshot.copy(connectionState = ListenTogetherConnectionState.DISCONNECTED)
        owner.onResolvedStreamUrlChanged("https://audio.test/track")
        assertTrue(session.heartbeatReasons.isEmpty())
    }

    private fun TestScope.owner(
        session: FakeSession,
        playback: FakePlayback,
        timing: ListenTogetherControllerLinkTiming = ListenTogetherControllerLinkTiming(),
        nowMs: () -> Long
    ) = ListenTogetherControllerLinkOwner(
        scope = this,
        session = session,
        playback = playback,
        events = FakeEvents(),
        timing = timing,
        elapsedRealtimeMs = nowMs
    )

    private fun roomState(controller: String): ListenTogetherRoomState = ListenTogetherRoomState(
        roomId = "room",
        version = 1L,
        controllerUserUuid = controller,
        queue = listOf(track),
        track = track
    )

    private class FakeSession(
        role: String,
        var room: ListenTogetherRoomState
    ) : ListenTogetherLinkSessionPort {
        val sent = mutableListOf<ListenTogetherEvent>()
        val heartbeatReasons = mutableListOf<String>()
        var snapshot = ListenTogetherSessionState(
            userUuid = if (role == "controller") "host" else "listener",
            role = role,
            connectionState = ListenTogetherConnectionState.CONNECTED
        )

        override fun sessionState(): ListenTogetherSessionState = snapshot

        override fun roomState(): ListenTogetherRoomState = room

        override fun publish(event: ListenTogetherEvent, reason: String, noteSync: Boolean): Boolean {
            sent += event
            return true
        }

        override fun publishControllerHeartbeat(reason: String) {
            heartbeatReasons += reason
        }
    }

    private class FakePlayback(var song: SongItem?) : ListenTogetherLinkPlaybackPort {
        var hasLocalDirectStream = false
        var resolutionPending = false
        var resolution = ShareableListenTogetherStreamResolution(emptyList(), false)
        var resolveCount = 0
        var onResolve: () -> Unit = {}

        override fun currentSong(): SongItem? = song

        override fun playbackPositionMs(): Long = 123L

        override fun playbackResolutionPending(): Boolean = resolutionPending

        override suspend fun resolveShareableStreamUrls(song: SongItem): ShareableListenTogetherStreamResolution {
            resolveCount++
            onResolve()
            return resolution
        }

        override fun hasUsableLocalDirectStream(track: ListenTogetherTrack): Boolean =
            hasLocalDirectStream
    }

    private class FakeEvents : ListenTogetherLinkEventPort {
        override fun requestLink(
            stableKey: String,
            currentIndex: Int,
            track: ListenTogetherTrack,
            forceRefresh: Boolean
        ) = ListenTogetherEvent(
            type = "REQUEST_LINK",
            eventId = "request",
            currentIndex = currentIndex,
            track = track,
            requestTrackStableKey = stableKey,
            forceRefresh = forceRefresh
        )

        override fun linkReady(
            stableKey: String,
            positionMs: Long,
            streamUrlsOverride: List<String>
        ): ListenTogetherEvent? = streamUrlsOverride.takeIf { it.isNotEmpty() }?.let { urls ->
            ListenTogetherEvent(
                type = "LINK_READY",
                eventId = "ready",
                positionMs = positionMs,
                track = ListenTogetherTrack(
                    stableKey = stableKey,
                    channelId = "netease",
                    audioId = "42",
                    streamUrls = urls,
                    name = "Track",
                    artist = "Artist"
                )
            )
        }

        override fun linkUnavailable(stableKey: String) = ListenTogetherEvent(
            type = "LINK_UNAVAILABLE",
            eventId = "unavailable",
            requestTrackStableKey = stableKey
        )
    }
}
