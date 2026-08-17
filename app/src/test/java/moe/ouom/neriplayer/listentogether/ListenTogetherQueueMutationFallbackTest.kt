package moe.ouom.neriplayer.listentogether

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.listentogether.compat.buildListenTogetherLegacyQueueMutationFallback
import moe.ouom.neriplayer.listentogether.compat.isListenTogetherQueueMutationCompatibilityError
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueMutation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueOperation
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherQueueReference
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherQueueMutationFallbackTest {

    @Test
    fun `invalid mutation can retry the original queue intent as a legacy snapshot`() {
        val first = track("first")
        val selected = track("selected")
        val event = ListenTogetherEvent(
            type = "REQUEST_SET_TRACK",
            eventId = "mutation-event",
            currentIndex = 1,
            track = selected,
            queueMutation = ListenTogetherQueueMutation(
                baseRoomVersion = 9L,
                operations = listOf(
                    ListenTogetherQueueOperation(
                        type = "move",
                        target = ListenTogetherQueueReference(first.stableKey, 0),
                        placement = "append"
                    )
                ),
                targetCurrent = ListenTogetherQueueReference(selected.stableKey, 0)
            ),
            legacyQueueSnapshot = listOf(first, selected)
        )

        val fallback = buildListenTogetherLegacyQueueMutationFallback(
            event = event,
            fallbackEventId = "legacy-event"
        )

        requireNotNull(fallback)
        assertEquals("REQUEST_SET_TRACK", fallback.type)
        assertEquals("legacy-event", fallback.eventId)
        assertEquals(listOf(first, selected), fallback.queue)
        assertNull(fallback.queueMutation)
        assertNull(fallback.legacyQueueSnapshot)
    }

    @Test
    fun `events without a mutation snapshot cannot manufacture a legacy retry`() {
        assertNull(
            buildListenTogetherLegacyQueueMutationFallback(
                event = ListenTogetherEvent(type = "SET_TRACK", eventId = "plain"),
                fallbackEventId = "legacy-event"
            )
        )
    }

    @Test
    fun `legacy fallback rejects a blank replacement event id`() {
        val track = track("only")
        val event = ListenTogetherEvent(
            type = "SET_QUEUE",
            queueMutation = ListenTogetherQueueMutation(
                baseRoomVersion = 1L,
                operations = emptyList()
            ),
            legacyQueueSnapshot = listOf(track)
        )

        assertNull(buildListenTogetherLegacyQueueMutationFallback(event, " "))
        assertTrue(event.legacyQueueSnapshot.orEmpty().isNotEmpty())
    }

    @Test
    fun `legacy fallback snapshot remains local and never changes the wire protocol`() {
        val event = ListenTogetherEvent(
            type = "SET_QUEUE",
            eventId = "mutation-event",
            queueMutation = ListenTogetherQueueMutation(
                baseRoomVersion = 1L,
                operations = emptyList()
            ),
            legacyQueueSnapshot = listOf(track("local-only"))
        )

        val encoded = Json.encodeToString(event)
        val decoded = Json.decodeFromString<ListenTogetherEvent>(encoded)

        assertFalse(encoded.contains("legacyQueueSnapshot"))
        assertNull(decoded.legacyQueueSnapshot)
        assertEquals(event.queueMutation, decoded.queueMutation)
    }

    @Test
    fun `queue mutation compatibility errors include malformed and ahead version rejections`() {
        assertTrue(isListenTogetherQueueMutationCompatibilityError("queue mutation is invalid"))
        assertTrue(
            isListenTogetherQueueMutationCompatibilityError(
                "queue mutation base version is ahead"
            )
        )
        assertTrue(
            isListenTogetherQueueMutationCompatibilityError(
                "queue mutation event type unsupported"
            )
        )
        assertFalse(
            isListenTogetherQueueMutationCompatibilityError(
                "only controller can control playback"
            )
        )
    }

    @Test
    fun `control result decodes the rejected mutation event identity`() {
        val response = Json.decodeFromString<ListenTogetherSocketEnvelope>(
            """
            {
              "type": "control_result",
              "ok": false,
              "result": {"ok": false, "error": "queue mutation is invalid"},
              "causedBy": {
                "eventId": "mutation-event",
                "type": "REQUEST_SET_TRACK"
              }
            }
            """.trimIndent()
        )

        assertEquals("mutation-event", response.causedBy?.eventId)
        assertEquals("REQUEST_SET_TRACK", response.causedBy?.type)
        assertEquals("queue mutation is invalid", response.result?.error)
    }

    private fun track(id: String) = ListenTogetherTrack(
        stableKey = "netease:$id",
        channelId = "netease",
        audioId = id,
        name = id,
        artist = "artist"
    )
}
