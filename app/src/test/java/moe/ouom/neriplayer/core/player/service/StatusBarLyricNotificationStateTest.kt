package moe.ouom.neriplayer.core.player.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusBarLyricNotificationStateTest {

    @Test
    fun `setting and lyric changes produce distinct notification states`() = runTest {
        val enabledFlow = MutableStateFlow(false)
        val lineFlow = MutableStateFlow<String?>("line A")
        val states = mutableListOf<StatusBarLyricNotificationState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            statusBarLyricNotificationStateFlow(enabledFlow, lineFlow).collect { state ->
                states += state
            }
        }

        assertEquals(
            listOf(resolveStatusBarLyricNotificationState(false, null)),
            states,
        )

        enabledFlow.value = true
        lineFlow.value = "line B"
        enabledFlow.value = false
        val stateCountAfterDisable = states.size
        lineFlow.value = "line C"
        assertEquals(stateCountAfterDisable, states.size)
        enabledFlow.value = true

        assertEquals(
            listOf(
                resolveStatusBarLyricNotificationState(false, null),
                resolveStatusBarLyricNotificationState(true, "line A"),
                resolveStatusBarLyricNotificationState(true, "line B"),
                resolveStatusBarLyricNotificationState(false, null),
                resolveStatusBarLyricNotificationState(true, "line C"),
            ),
            states,
        )
    }

    @Test
    fun `setting changes are emitted even without a lyric line`() = runTest {
        val enabledFlow = MutableStateFlow(false)
        val lineFlow = MutableStateFlow<String?>(null)
        val states = mutableListOf<StatusBarLyricNotificationState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            statusBarLyricNotificationStateFlow(enabledFlow, lineFlow).collect { state ->
                states += state
            }
        }

        enabledFlow.value = true
        enabledFlow.value = false

        assertEquals(
            listOf(
                resolveStatusBarLyricNotificationState(false, null),
                resolveStatusBarLyricNotificationState(true, null),
                resolveStatusBarLyricNotificationState(false, null),
            ),
            states,
        )
    }

    @Test
    fun `invalid lyric lines never create a ticker`() {
        listOf(null, "", "   ", "null").forEach { line ->
            val state = resolveStatusBarLyricNotificationState(enabled = true, line = line)

            assertTrue(state.enabled)
            assertNull(state.line)
            assertFalse(state.hasTicker)
        }
    }
}
