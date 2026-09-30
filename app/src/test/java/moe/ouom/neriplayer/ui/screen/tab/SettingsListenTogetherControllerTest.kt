package moe.ouom.neriplayer.ui.screen.tab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.ltw.ListenTogetherSessionManager
import moe.ouom.neriplayer.data.model.ltw.ListenTogetherServerTestResult
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.SettingsListenTogetherController
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.isDefaultListenTogetherSettingsServer
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherIdentityDescriptionId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherJoinButtonLabelId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherJoinDescriptionId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherNicknameDescription
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherServerDescriptionId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.listenTogetherServerTestMessageId
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.normalizedSettingsListenTogetherServerInput
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.readSettingsListenTogetherClipboardText
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.settingsListenTogetherServerProbeRequest
import moe.ouom.neriplayer.ui.screen.tab.settings.listentogether.validListenTogetherClipboardInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsListenTogetherControllerTest {
    @Test
    fun `server description follows normalized default address`() {
        assertTrue(isDefaultListenTogetherSettingsServer(""))
        assertTrue(isDefaultListenTogetherSettingsServer("https://neriplayer.hancat.work/"))
        assertFalse(isDefaultListenTogetherSettingsServer("https://example.com"))
        assertFalse(isDefaultListenTogetherSettingsServer("not-a-url"))
        assertEquals("", normalizedSettingsListenTogetherServerInput(" "))
        assertEquals("https://example.com",
            normalizedSettingsListenTogetherServerInput("https://example.com/")
        )
        assertEquals(null, normalizedSettingsListenTogetherServerInput("not-a-url"))
    }

    @Test
    fun `server probe request uses resolved default or normalized custom endpoint`() {
        val defaultRequest = settingsListenTogetherServerProbeRequest("")
        assertEquals("https://neriplayer.hancat.work", defaultRequest.endpoint)
        assertTrue(defaultRequest.usingDefaultServer)

        val customRequest = settingsListenTogetherServerProbeRequest("https://example.com")
        assertEquals("https://example.com", customRequest.endpoint)
        assertFalse(customRequest.usingDefaultServer)
    }

    @Test
    fun `join prefill only accepts a complete invitation`() {
        val invite = "neriplayer://listen-together/join?roomId=GTV42X&secret=secret-value"
        assertEquals(invite, validListenTogetherClipboardInvite(invite))
        assertEquals("", validListenTogetherClipboardInvite("roomId=GTV42X"))
        assertEquals("", validListenTogetherClipboardInvite(null))
    }

    @Test
    fun `clipboard reader tolerates unavailable and empty clips`() {
        val context = mock(Context::class.java)
        assertEquals(null, readSettingsListenTogetherClipboardText(context))

        val clipboard = mock(ClipboardManager::class.java)
        val clip = mock(ClipData::class.java)
        `when`(context.getSystemService(ClipboardManager::class.java)).thenReturn(clipboard)
        `when`(clipboard.primaryClip).thenReturn(clip)
        `when`(clip.itemCount).thenReturn(0)
        assertEquals(null, readSettingsListenTogetherClipboardText(context))
    }

    @Test
    fun `clipboard reader extracts the first text item and handles provider failure`() {
        val context = mock(Context::class.java)
        val clipboard = mock(ClipboardManager::class.java)
        val clip = mock(ClipData::class.java)
        val item = mock(ClipData.Item::class.java)
        `when`(context.getSystemService(ClipboardManager::class.java)).thenReturn(clipboard)
        `when`(clipboard.primaryClip).thenReturn(clip)
        `when`(clip.itemCount).thenReturn(1)
        `when`(clip.getItemAt(0)).thenReturn(item)
        `when`(item.coerceToText(context)).thenReturn("invite text")
        assertEquals("invite text", readSettingsListenTogetherClipboardText(context))

        `when`(clipboard.primaryClip).thenThrow(SecurityException("clipboard denied"))
        assertEquals(null, readSettingsListenTogetherClipboardText(context))
    }

    @Test
    fun `server test messages distinguish default custom and malformed responses`() {
        val reachable = ListenTogetherServerTestResult(ok = true, message = "reachable")
        val malformed = ListenTogetherServerTestResult(ok = false, message = "invalid_response")
        val unavailable = ListenTogetherServerTestResult(ok = false, message = "timeout")

        assertEquals(
            R.string.settings_listen_together_server_test_success_default,
            listenTogetherServerTestMessageId(reachable, usingDefaultServer = true)
        )
        assertEquals(
            R.string.settings_listen_together_server_test_success_custom,
            listenTogetherServerTestMessageId(reachable, usingDefaultServer = false)
        )
        assertEquals(
            R.string.settings_listen_together_server_test_invalid,
            listenTogetherServerTestMessageId(malformed, usingDefaultServer = false)
        )
        assertEquals(
            R.string.settings_listen_together_server_test_failed,
            listenTogetherServerTestMessageId(unavailable, usingDefaultServer = true)
        )
    }

    @Test
    fun `room and server rows display the active restriction and endpoint`() {
        assertEquals(
            R.string.settings_listen_together_join_room_disabled,
            listenTogetherJoinDescriptionId(isInRoom = true)
        )
        assertEquals(
            R.string.settings_listen_together_join_room_desc,
            listenTogetherJoinDescriptionId(isInRoom = false)
        )
        assertEquals(
            R.string.listen_together_reset_uuid_disabled,
            listenTogetherIdentityDescriptionId(isInRoom = true)
        )
        assertEquals(
            R.string.settings_listen_together_reset_identity_desc,
            listenTogetherIdentityDescriptionId(isInRoom = false)
        )
        assertEquals(
            R.string.settings_listen_together_server_default_desc,
            listenTogetherServerDescriptionId(usingDefault = true)
        )
        assertEquals(
            R.string.settings_listen_together_server_custom_desc,
            listenTogetherServerDescriptionId(usingDefault = false)
        )
        assertEquals(R.string.listen_together_joining_room,
            listenTogetherJoinButtonLabelId(joining = true)
        )
        assertEquals(R.string.listen_together_join_room,
            listenTogetherJoinButtonLabelId(joining = false)
        )
    }

    @Test
    fun `nickname description uses room restriction then current or fallback value`() {
        assertEquals("room locked",
            listenTogetherNicknameDescription(true, "Neri", "room locked", "unset")
        )
        assertEquals("Neri",
            listenTogetherNicknameDescription(false, "Neri", "room locked", "unset")
        )
        assertEquals("unset", listenTogetherNicknameDescription(false, "", "room locked", "unset"))
    }

    @Test
    fun `join dialog rejects invalid invite and a room already in progress`() {
        val fixture = controllerFixture()
        `when`(fixture.resources.getString(R.string.settings_listen_together_join_invite_invalid))
            .thenReturn("invalid invite")
        `when`(fixture.resources.getString(R.string.settings_listen_together_join_room_disabled))
            .thenReturn("already in room")

        fixture.controller.openJoinDialog()
        assertTrue(fixture.controller.showJoinDialog)
        assertEquals("", fixture.controller.inviteInput)
        fixture.controller.confirmJoin()
        assertEquals("invalid invite", fixture.controller.inviteError)

        fixture.controller.updateInviteInput(
            "neriplayer://listen-together/join?roomId=GTV42X&secret=secret-value"
        )
        fixture.session.value = ListenTogetherSessionState(roomId = "OTHER1")
        fixture.controller.confirmJoin()
        assertEquals("already in room", fixture.controller.inviteError)
        fixture.controller.dismissJoinDialog()
        assertFalse(fixture.controller.showJoinDialog)
        assertEquals("", fixture.controller.inviteInput)
    }

    @Test
    fun `join entry remains closed while a room is active`() {
        val fixture = controllerFixture()
        fixture.session.value = ListenTogetherSessionState(roomId = "OTHER1")

        fixture.controller.openJoinDialog()

        assertFalse(fixture.controller.showJoinDialog)
    }

    @Test
    fun `nickname edits stay intact while dialog is open and resync after dismiss`() {
        val fixture = controllerFixture()
        fixture.nickname.value = "savedName"
        fixture.controller.syncNicknameInput()
        assertEquals("savedName", fixture.controller.nicknameInput)

        fixture.controller.openNicknameDialog()
        fixture.controller.updateNicknameInput("editedName")
        fixture.nickname.value = "changedElsewhere"
        fixture.controller.syncNicknameInput()
        assertEquals("editedName", fixture.controller.nicknameInput)

        fixture.controller.dismissNicknameDialog()
        assertEquals("changedElsewhere", fixture.controller.nicknameInput)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `server probe reports custom endpoint success and clears loading state`() = runTest {
        val result = ListenTogetherServerTestResult(ok = true, message = "reachable")
        val fixture = controllerFixture(scope = this, serverProbe = { baseUrl ->
            assertEquals("https://example.com", baseUrl)
            result
        })
        `when`(fixture.resources.getString(R.string.settings_listen_together_server_test_success_custom))
            .thenReturn("custom server available")

        fixture.controller.updateServerInput("https://example.com/")
        fixture.controller.testServer()
        testScheduler.advanceUntilIdle()

        assertEquals("custom server available", fixture.controller.serverTestMessage)
        assertFalse(fixture.controller.serverTesting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `server probe uses default endpoint for empty configuration`() = runTest {
        val fixture = controllerFixture(scope = this, serverProbe = { baseUrl ->
            assertEquals("https://neriplayer.hancat.work", baseUrl)
            ListenTogetherServerTestResult(ok = true, message = "reachable")
        })
        `when`(fixture.resources.getString(R.string.settings_listen_together_server_test_success_default))
            .thenReturn("default server available")

        fixture.controller.testServer()
        testScheduler.advanceUntilIdle()

        assertEquals("default server available", fixture.controller.serverTestMessage)
        assertFalse(fixture.controller.serverTesting)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `server probe keeps loading visible while suspended then clears it`() = runTest {
        val fixture = controllerFixture(scope = this, serverProbe = {
            delay(10)
            ListenTogetherServerTestResult(ok = true, message = "reachable")
        })
        `when`(fixture.resources.getString(R.string.settings_listen_together_server_test_success_custom))
            .thenReturn("custom server available")

        fixture.controller.updateServerInput("https://example.com")
        fixture.controller.testServer()
        testScheduler.runCurrent()
        assertTrue(fixture.controller.serverTesting)
        testScheduler.advanceUntilIdle()

        assertEquals("custom server available", fixture.controller.serverTestMessage)
        assertFalse(fixture.controller.serverTesting)
    }

    @Test
    fun `server probe clears loading when transport throws`() = runTest {
        val fixture = controllerFixture(scope = this, serverProbe = {
            throw IllegalStateException("offline")
        })

        val error = runCatching {
            fixture.controller.testValidatedServer("https://example.com")
        }.exceptionOrNull()

        assertEquals("offline", error?.message)
        assertFalse(fixture.controller.serverTesting)
    }

    private fun controllerFixture(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        serverProbe: suspend (String) -> ListenTogetherServerTestResult = {
            error("server probe was not expected")
        }
    ): ControllerFixture {
        val context = mock(Context::class.java)
        val resources = mock(Resources::class.java)
        val session = mutableStateOf(ListenTogetherSessionState())
        val nickname = mutableStateOf("")
        val controller = SettingsListenTogetherController(
            context = context,
            resources = resources,
            scope = scope,
            preferences = mock(ListenTogetherPreferences::class.java),
            serverProbe = serverProbe,
            sessionManager = mock(ListenTogetherSessionManager::class.java),
            sessionState = session,
            workerBaseUrl = mutableStateOf(""),
            workerBaseUrlInput = mutableStateOf(""),
            nickname = nickname,
            serverInputState = mutableStateOf(""),
            nicknameInputState = mutableStateOf(""),
            onMessage = {}
        )
        return ControllerFixture(resources, session, nickname, controller)
    }

    private data class ControllerFixture(
        val resources: Resources,
        val session: androidx.compose.runtime.MutableState<ListenTogetherSessionState>,
        val nickname: androidx.compose.runtime.MutableState<String>,
        val controller: SettingsListenTogetherController
    )
}
