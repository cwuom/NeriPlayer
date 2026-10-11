package moe.ouom.neriplayer.ui.screen.debug

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherMember
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherPlaybackState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomSettings
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherConnectionState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class ListenTogetherDebugPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val track = ListenTogetherTrack(
        stableKey = "netease:42",
        channelId = "netease",
        audioId = "42",
        subAudioId = "7",
        playlistContextId = "list-1",
        mediaUri = "content://media/42",
        streamUrl = "https://example.com/42.mp3",
        name = "Remote Track",
        artist = "Artist",
        durationMs = 65_000L,
        coverUrl = "https://example.com/42.jpg"
    )

    private val room = ListenTogetherRoomState(
        roomId = "room1",
        version = 3L,
        schemaVersion = 2,
        controllerUserUuid = " host-uuid ",
        controllerUserId = "host-id",
        controllerHeartbeatAt = 1_000L,
        members = listOf(
            ListenTogetherMember(userUuid = "host-uuid", nickname = "Host", role = "controller", joinedAt = 1L),
            ListenTogetherMember(userUuid = "guest-uuid", nickname = "", role = "listener", joinedAt = 2L),
            ListenTogetherMember(userUuid = "", nickname = "", userId = "anon-id", role = "", joinedAt = 3L)
        ),
        queue = listOf(track),
        track = track,
        playback = ListenTogetherPlaybackState(state = "playing", basePositionMs = 5_000L, baseTimestampMs = 2_000L, playbackRate = 1.5),
        roomStatus = ListenTogetherRoomStatuses.CONTROLLER_OFFLINE,
        closedReason = "host left",
        updatedAt = 3_000L
    )

    private val session = ListenTogetherSessionState(
        roomId = "room1",
        userUuid = "host-uuid",
        nickname = "Host",
        wsUrl = "wss://example.com/ws",
        connectionState = ListenTogetherConnectionState.CONNECTED,
        expectedPositionMs = 125_000L
    )

    @Test
    fun `role compares trimmed session and controller identities`() {
        assertEquals("controller", resolveListenTogetherRole(" host-uuid", "listener", room))
        assertEquals("listener", resolveListenTogetherRole("guest-uuid", "controller", room))
        val idOnlyRoom = room.copy(controllerUserUuid = "  ", controllerUserId = "host-id")
        assertEquals("controller", resolveListenTogetherRole("host-id", null, idOnlyRoom))
        assertEquals("fallback", resolveListenTogetherRole(" ", "fallback", room))
        assertEquals("fallback", resolveListenTogetherRole("host-uuid", "fallback", null))
        assertNull(resolveListenTogetherRole("host-uuid", null, room.copy(controllerUserUuid = null, controllerUserId = "")))
    }

    @Test
    fun `notices map prefixes exact codes and known fragments to localized text`() {
        val plural = { minutes: Int ->
            context.resources.getQuantityString(
                CoreCommonR.plurals.listen_together_notice_controller_offline,
                minutes,
                minutes
            )
        }
        assertEquals(plural(5), "controller_offline:5".toDisplayNotice(context))
        assertEquals(plural(10), "controller_offline:soon".toDisplayNotice(context))
        assertEquals(plural(0), "controller_offline:-3".toDisplayNotice(context))
        assertEquals(plural(Int.MAX_VALUE), "controller_offline:99999999999".toDisplayNotice(context))
        assertEquals(
            string(CoreCommonR.string.listen_together_notice_member_joined, "Alice"),
            "member_joined:Alice".toDisplayNotice(context)
        )
        assertEquals(
            string(CoreCommonR.string.listen_together_notice_member_left, "Bob"),
            "member_left:Bob".toDisplayNotice(context)
        )
        val expected = mapOf(
            "controller_reconnected" to CoreCommonR.string.listen_together_notice_controller_reconnected,
            "controller_left" to CoreCommonR.string.listen_together_notice_controller_left,
            "controller_timeout" to CoreCommonR.string.listen_together_notice_room_closed,
            "room_closed" to CoreCommonR.string.listen_together_notice_room_closed,
            "Room Closed by host" to CoreCommonR.string.listen_together_notice_room_closed,
            "Unauthorized" to CoreCommonR.string.listen_together_error_unauthorized,
            "request failed HTTP=401" to CoreCommonR.string.listen_together_error_unauthorized,
            "join failed (401)" to CoreCommonR.string.listen_together_error_unauthorized,
            "Room not initialized" to CoreCommonR.string.listen_together_error_room_not_found,
            "room not found in DO" to CoreCommonR.string.listen_together_error_room_not_found,
            "Controller offline" to CoreCommonR.string.listen_together_error_controller_offline,
            "Member control disabled" to CoreCommonR.string.listen_together_error_member_control_disabled,
            "unauthorized: room closed" to CoreCommonR.string.listen_together_notice_room_closed
        )
        expected.forEach { (notice, resId) ->
            assertEquals(notice, string(resId), notice.toDisplayNotice(context))
        }
        assertEquals("custom server notice", "custom server notice".toDisplayNotice(context))
    }

    @Test
    fun `member join and leave notices are not shown as banners`() {
        assertNull(displayableListenTogetherNotice(null))
        assertNull(displayableListenTogetherNotice("  "))
        assertNull(displayableListenTogetherNotice("member_joined:Alice"))
        assertNull(displayableListenTogetherNotice("member_left:Bob"))
        assertEquals("room_closed", displayableListenTogetherNotice("room_closed"))
    }

    @Test
    fun `token preview masks long tokens only`() {
        assertEquals("-", null.maskedTokenPreview())
        assertEquals("-", " ".maskedTokenPreview())
        assertEquals("0123456789", "0123456789".maskedTokenPreview())
        assertEquals("abcdef...wxyz", "abcdefghijklmnopqrstuvwxyz".maskedTokenPreview())
    }

    @Test
    fun `labels cover every connection state role and room status`() {
        assertEquals(
            listOf(
                CoreCommonR.string.listen_together_connection_disconnected,
                CoreCommonR.string.listen_together_connection_connecting,
                CoreCommonR.string.listen_together_connection_connected
            ),
            ListenTogetherConnectionState.entries.map { it.labelResId() }
        )
        assertEquals(CoreCommonR.string.listen_together_role_controller, roleLabelResId("controller"))
        assertEquals(CoreCommonR.string.listen_together_role_listener, roleLabelResId("listener"))
        assertEquals(CoreCommonR.string.listen_together_role_none, roleLabelResId(null))
        assertEquals(
            CoreCommonR.string.listen_together_room_status_controller_offline,
            roomStatusLabelResId(ListenTogetherRoomStatuses.CONTROLLER_OFFLINE)
        )
        assertEquals(
            CoreCommonR.string.listen_together_room_status_closed,
            roomStatusLabelResId(ListenTogetherRoomStatuses.CLOSED)
        )
        assertEquals(CoreCommonR.string.listen_together_room_status_active, roomStatusLabelResId(null))
    }

    @Test
    fun `controllers show local playback while listeners follow the room`() {
        assertEquals("paused", resolveDisplayedPlaybackState(room, "controller", isPlaying = false))
        assertEquals("playing", resolveDisplayedPlaybackState(room, "listener", isPlaying = false))
        assertEquals("playing", resolveDisplayedPlaybackState(null, "controller", isPlaying = true))
        assertEquals("paused", resolveDisplayedPlaybackState(null, null, isPlaying = false))
        assertEquals(
            CoreCommonR.string.listen_together_playback_playing,
            listenTogetherPlaybackLabelResId(room, "listener", isPlaying = false)
        )
        assertEquals(
            CoreCommonR.string.listen_together_playback_paused,
            listenTogetherPlaybackLabelResId(room, "controller", isPlaying = false)
        )
    }

    @Test
    fun `session summary falls back to placeholders without a room`() {
        val fields = listenTogetherSessionSummaryFields(
            context.resources,
            ListenTogetherSessionState(),
            roomState = null,
            role = null,
            isPlaying = true
        )

        assertEquals(
            listOf(
                CoreCommonR.string.listen_together_connection to string(CoreCommonR.string.listen_together_connection_disconnected),
                CoreCommonR.string.listen_together_role to string(CoreCommonR.string.listen_together_role_none),
                CoreCommonR.string.listen_together_room_status to string(CoreCommonR.string.listen_together_room_status_active),
                CoreCommonR.string.listen_together_room_id to "-",
                CoreCommonR.string.listen_together_version to "-",
                CoreCommonR.string.listen_together_members to "0",
                CoreCommonR.string.listen_together_queue_size to "0",
                CoreCommonR.string.listen_together_playback to string(CoreCommonR.string.listen_together_playback_playing)
            ).map { (label, value) -> DebugField(string(label), value) },
            fields
        )
    }

    @Test
    fun `session details expose room payload and optional diagnostics`() {
        val fields = listenTogetherSessionDetailFields(
            context.resources,
            session.copy(token = "secret", lastError = "boom", roomNotice = "member_joined:Alice"),
            room,
            fallbackTrackName = "Local Track",
            effectiveBaseUrl = "https://worker.example.com",
            tokenPreview = "secret"
        ).associate { it.label to it.value }

        assertEquals("Remote Track", fields[string(CoreCommonR.string.listen_together_track)])
        assertEquals("https://worker.example.com", fields[string(CoreCommonR.string.listen_together_debug_base_url)])
        assertEquals("wss://example.com/ws", fields[string(CoreCommonR.string.listen_together_debug_ws_url)])
        assertEquals("2", fields[string(CoreCommonR.string.listen_together_debug_schema)])
        assertEquals("2:05 (125000 ms)", fields[string(CoreCommonR.string.listen_together_debug_expected_position)])
        assertEquals("0:05 (5000 ms)", fields[string(CoreCommonR.string.listen_together_debug_playback_base_position)])
        assertEquals("1.5", fields[string(CoreCommonR.string.listen_together_debug_playback_rate)])
        assertEquals(" host-uuid ", fields[string(CoreCommonR.string.listen_together_debug_controller_uuid)])
        assertEquals("-", fields[string(CoreCommonR.string.listen_together_debug_controller_offline_since)])
        assertEquals(true, fields[string(CoreCommonR.string.listen_together_debug_controller_heartbeat)]?.endsWith("(1000)"))
        assertEquals(true, fields[string(CoreCommonR.string.listen_together_debug_updated_at)]?.endsWith("(3000)"))
        assertEquals("host left", fields[string(CoreCommonR.string.listen_together_debug_closed_reason)])
        assertEquals("boom", fields[string(CoreCommonR.string.listen_together_last_error)])
        assertEquals("member_joined:Alice", fields[string(CoreCommonR.string.listen_together_debug_raw_notice)])

        val bare = listenTogetherSessionDetailFields(
            context.resources,
            ListenTogetherSessionState(lastError = " ", roomNotice = ""),
            roomState = null,
            fallbackTrackName = null,
            effectiveBaseUrl = "",
            tokenPreview = "-"
        )
        assertEquals(17, bare.size)
        assertEquals(listOf("-"), bare.map { it.value }.filterNot { it.isEmpty() }.distinct())
    }

    @Test
    fun `simple status lists room timing and the fallback track`() {
        val fields = listenTogetherSimpleStatusFields(
            context.resources,
            session,
            room.copy(track = null),
            role = "controller",
            fallbackTrackName = "Local Track",
            isPlaying = false
        ).map { it.label }

        assertEquals(
            listOf(
                CoreCommonR.string.listen_together_connection,
                CoreCommonR.string.listen_together_role,
                CoreCommonR.string.listen_together_room_status,
                CoreCommonR.string.listen_together_room_id,
                CoreCommonR.string.listen_together_version,
                CoreCommonR.string.listen_together_debug_updated_at,
                CoreCommonR.string.listen_together_members,
                CoreCommonR.string.listen_together_queue_size,
                CoreCommonR.string.listen_together_track,
                CoreCommonR.string.listen_together_playback
            ).map(::string),
            fields
        )
        assertEquals("Local Track", listenTogetherTrackName(null, "Local Track"))
        assertEquals("-", listenTogetherTrackName(null, null))
    }

    @Test
    fun `track fields show payload values or placeholders`() {
        assertEquals(
            listOf("Remote Track", "netease", "1:05 (65000 ms)", "netease:42"),
            listenTogetherTrackSummaryFields(context.resources, track, "Local").map { it.value }
        )
        assertEquals(
            listOf("7", "list-1", "content://media/42", "https://example.com/42.mp3", "https://example.com/42.jpg"),
            listenTogetherTrackDetailFields(context.resources, track).drop(1).map { it.value }
        )
        assertEquals(
            listOf("Local", "-", "-", "-"),
            listenTogetherTrackSummaryFields(context.resources, null, "Local").map { it.value }
        )
        assertEquals(List(6) { "-" }, listenTogetherTrackDetailFields(context.resources, null).map { it.value })
    }

    @Test
    fun `component activity is found through context wrappers`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        assertSame(activity, activity.findComponentActivity())
        assertSame(activity, ContextWrapper(ContextWrapper(activity)).findComponentActivity())
        assertNull(context.findComponentActivity())
    }

    @Test
    fun `status section toggles details and shows error and notice banners`() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatusSection(
                    sessionState = session.copy(lastError = "socket closed", roomNotice = "room_closed"),
                    roomState = room,
                    role = "controller",
                    fallbackTrackName = null,
                    isPlaying = true,
                    effectiveBaseUrl = "https://worker.example.com",
                    tokenPreview = "abcdef...wxyz",
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded }
                )
            }
        }

        composeRule.onNodeWithText(string(CoreCommonR.string.listen_together_debug_session_title)).assertExists()
        composeRule.onNodeWithText("socket closed").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.listen_together_notice_room_closed)).assertExists()
        composeRule.onNodeWithText("abcdef...wxyz").assertDoesNotExist()

        composeRule.onNodeWithText(string(CoreCommonR.string.action_expand)).performClick()

        composeRule.onNodeWithText("abcdef...wxyz").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.action_collapse)).assertExists()
    }

    @Test
    fun `simple status and member sections render room members`() {
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SimpleStatusSection(
                    sessionState = session.copy(roomNotice = "member_left:Guest"),
                    roomState = room.copy(track = null),
                    role = "listener",
                    fallbackTrackName = "Local Track",
                    isPlaying = false
                )
                SimpleMemberSection(room.members)
                SimpleMemberSection(emptyList())
            }
        }

        composeRule.onNodeWithText("Local Track").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.listen_together_notice)).assertDoesNotExist()
        composeRule.onNodeWithText("Host").assertExists()
        composeRule.onNodeWithText("guest-uuid").assertExists()
        composeRule.onNodeWithText("anon-id").assertExists()
        composeRule.onNodeWithText(string(CoreCommonR.string.listen_together_role_none)).assertExists()
    }

    @Test
    fun `expanded member and track sections show per item details`() {
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DebugHeader(
                    connectionState = ListenTogetherConnectionState.CONNECTING,
                    role = "listener",
                    roomStatus = ListenTogetherRoomStatuses.CLOSED,
                    roomVersion = null,
                    roomId = "room1"
                )
                DebugHeader(
                    connectionState = ListenTogetherConnectionState.CONNECTED,
                    role = null,
                    roomStatus = null,
                    roomVersion = 7L,
                    roomId = " "
                )
                DebugHeader(
                    connectionState = ListenTogetherConnectionState.DISCONNECTED,
                    role = null,
                    roomStatus = null,
                    roomVersion = 8L,
                    roomId = null
                )
                TrackDebugSection(track = track, fallbackTrackName = null, expanded = true, onToggleExpanded = {})
                MemberSection(members = room.members, expanded = true, onToggleExpanded = {})
                MemberSection(members = emptyList(), expanded = true, onToggleExpanded = {})
            }
        }

        composeRule.onNodeWithText("#room1").assertExists()
        composeRule.onNodeWithText("v-1").assertExists()
        composeRule.onNodeWithText("v7").assertExists()
        composeRule.onNodeWithText("v8").assertExists()
        composeRule.onAllNodesWithText("#", substring = true).assertCountEquals(1)
        composeRule.onNodeWithText(string(CoreCommonR.string.listen_together_room_status_closed)).assertExists()
        composeRule.onNodeWithText("https://example.com/42.mp3").assertExists()
        composeRule.onAllNodesWithText("anon-id").assertCountEquals(2)
        composeRule.onAllNodesWithText("3").assertCountEquals(2)
    }

    @Test
    fun `settings switches report the toggled room setting`() {
        val changes = mutableListOf<ListenTogetherRoomSettings>()
        val settings = ListenTogetherRoomSettings(
            allowMemberControl = true,
            autoPauseOnMemberChange = false,
            shareAudioLinks = true
        )
        val onSettingsChange: (ListenTogetherRoomSettings) -> Unit = { changes += it }
        var enabled by mutableStateOf(true)
        composeRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsSection(settings = settings, enabled = enabled, onSettingsChange = onSettingsChange)
            }
        }

        val switches = composeRule.onAllNodes(isToggleable())
        switches[0].assertIsOn()
        switches[1].assertIsOff()
        switches[0].performClick()
        switches[1].performClick()
        switches[2].performClick()

        assertEquals(
            listOf(
                settings.copy(allowMemberControl = false),
                settings.copy(autoPauseOnMemberChange = true),
                settings.copy(shareAudioLinks = false)
            ),
            changes
        )

        enabled = false
        composeRule.waitForIdle()
        switches[0].assertIsNotEnabled()
        switches[0].performClick()
        assertEquals(3, changes.size)
    }
}
