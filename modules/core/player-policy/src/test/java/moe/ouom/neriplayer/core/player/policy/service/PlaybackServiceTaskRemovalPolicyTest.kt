package moe.ouom.neriplayer.core.player.policy.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceTaskRemovalPolicyTest {

    @Test
    fun `active transport is persisted before service stops when task is removed`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = true,
            playerTransportActive = true,
            listenTogetherRemotePlaying = false,
            hasItems = true,
        )

        assertTrue(action.stopPlaybackImmediately)
        assertTrue(action.persistPlaybackState)
        assertTrue(action.stopServiceAfterPersist)
        assertFalse(action.updateNotificationAfterPersist)
    }

    @Test
    fun `listen together transport is persisted before service stops when task is removed`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = true,
            playerTransportActive = false,
            listenTogetherRemotePlaying = true,
            hasItems = false,
        )

        assertTrue(action.stopPlaybackImmediately)
        assertTrue(action.persistPlaybackState)
        assertTrue(action.stopServiceAfterPersist)
        assertFalse(action.updateNotificationAfterPersist)
    }

    @Test
    fun `paused queue is persisted but service is retained when task is removed`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = true,
            playerTransportActive = false,
            listenTogetherRemotePlaying = false,
            hasItems = true,
        )

        assertFalse(action.stopPlaybackImmediately)
        assertTrue(action.persistPlaybackState)
        assertFalse(action.stopServiceAfterPersist)
        assertTrue(action.updateNotificationAfterPersist)
    }

    @Test
    fun `empty playback surface is ignored for task removal`() {
        val action = resolveTaskRemovedPlaybackAction(
            hasPlaybackSurfaceContent = false,
            playerTransportActive = true,
            listenTogetherRemotePlaying = true,
            hasItems = false,
        )

        assertFalse(action.stopPlaybackImmediately)
        assertFalse(action.persistPlaybackState)
        assertFalse(action.stopServiceAfterPersist)
        assertFalse(action.updateNotificationAfterPersist)
    }

    @Test
    fun `active task removal waits for persistence before stopping service`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = true,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(calls)
        )

        assertEquals(
            listOf(
                "stop_playback",
                "persist:task_removed",
                "stop_foreground:task_removed",
                "stop_self",
            ),
            calls
        )
    }

    @Test
    fun `task removal still persists and stops service after stop playback failure`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = true,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(
                calls = calls,
                stopPlaybackImmediately = {
                    calls += "stop_playback"
                    error("stop failed")
                }
            )
        )

        assertEquals(
            listOf(
                "stop_playback",
                "stop_error:IllegalStateException",
                "persist:task_removed",
                "stop_foreground:task_removed",
                "stop_self",
            ),
            calls
        )
    }

    @Test
    fun `inactive task removal persists before notification and keeps service`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = false,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(calls)
        )

        assertEquals(
            listOf(
                "persist:inactive_task_removed",
                "update_notification",
            ),
            calls
        )
    }

    @Test
    fun `empty task removal performs no playback side effects`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = false,
                playerTransportActive = true,
                listenTogetherRemotePlaying = true,
                hasItems = false,
            ),
            callbacks = taskRemovedCallbacks(calls)
        )

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `task removal stop predicate requires content and active transport`() {
        assertTrue(
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = true,
                transportActive = true,
            )
        )
        assertFalse(
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = true,
                transportActive = false,
            )
        )
        assertFalse(
            shouldStopPlaybackOnTaskRemoved(
                hasPlaybackSurfaceContent = false,
                transportActive = true,
            )
        )
    }

    @Test
    fun `remote listen together playback counts as active task removal transport`() {
        assertTrue(
            resolveTaskRemovedTransportActive(
                playerTransportActive = false,
                listenTogetherRemotePlaying = true,
            )
        )
        assertTrue(
            resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = false,
                listenTogetherRemotePlaying = true,
                hasItems = false,
            ).stopPlaybackImmediately
        )
    }

    @Test
    fun `active task removal keeps service when persistence fails`() = runTest {
        val calls = mutableListOf<String>()

        executeTaskRemovedPlaybackAction(
            action = resolveTaskRemovedPlaybackAction(
                hasPlaybackSurfaceContent = true,
                playerTransportActive = true,
                listenTogetherRemotePlaying = false,
                hasItems = true,
            ),
            callbacks = taskRemovedCallbacks(
                calls = calls,
                persistPlaybackState = { reason ->
                    calls += "persist:$reason"
                    false
                }
            )
        )

        assertEquals(
            listOf(
                "stop_playback",
                "persist:task_removed",
                "update_notification",
            ),
            calls
        )
    }

    private fun taskRemovedCallbacks(
        calls: MutableList<String>,
        stopPlaybackImmediately: () -> Unit = {
            calls += "stop_playback"
        },
        persistPlaybackState: suspend (String) -> Boolean = { reason ->
            calls += "persist:$reason"
            true
        },
    ): TaskRemovedPlaybackCallbacks {
        return TaskRemovedPlaybackCallbacks(
            stopPlaybackImmediately = stopPlaybackImmediately,
            persistPlaybackState = persistPlaybackState,
            stopForegroundIfStarted = { reason ->
                calls += "stop_foreground:$reason"
            },
            stopSelf = {
                calls += "stop_self"
            },
            updateNotification = {
                calls += "update_notification"
            },
            onPlaybackStopFailure = { error ->
                calls += "stop_error:${error::class.simpleName}"
            },
            onNotificationUpdateFailure = { error ->
                calls += "notification_error:${error::class.simpleName}"
            },
        )
    }

}
