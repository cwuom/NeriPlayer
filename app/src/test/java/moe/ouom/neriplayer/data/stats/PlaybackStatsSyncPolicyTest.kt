package moe.ouom.neriplayer.data.stats

import androidx.work.ExistingWorkPolicy
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatsSyncPolicyTest {
    @Test
    fun `automatic playback stats sync requests use one minute delay`() {
        val githubRequest = GitHubSyncWorker.buildDelayedSyncRequest(
            triggerByUserAction = false,
            initialDelayMs = PLAYBACK_STATS_SYNC_DELAY_MS
        )
        val webDavRequest = WebDavSyncWorker.buildDelayedSyncRequest(
            triggerByUserAction = false,
            initialDelayMs = PLAYBACK_STATS_SYNC_DELAY_MS
        )

        assertEquals(PLAYBACK_STATS_SYNC_DELAY_MS, githubRequest.workSpec.initialDelay)
        assertEquals(PLAYBACK_STATS_SYNC_DELAY_MS, webDavRequest.workSpec.initialDelay)
    }

    @Test
    fun `automatic playback stats sync keeps one pending request per backend`() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            GitHubSyncWorker.delayedSyncWorkPolicy(
                triggerByUserAction = false,
                appendToCurrentWork = false
            )
        )
        assertEquals(
            ExistingWorkPolicy.KEEP,
            WebDavSyncWorker.delayedSyncWorkPolicy(
                triggerByUserAction = false,
                appendToCurrentWork = false
            )
        )
    }

    @Test
    fun `user and in-flight mutation retries append after current work`() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            GitHubSyncWorker.delayedSyncWorkPolicy(
                triggerByUserAction = true,
                appendToCurrentWork = false
            )
        )
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            WebDavSyncWorker.delayedSyncWorkPolicy(
                triggerByUserAction = false,
                appendToCurrentWork = true
            )
        )
    }
}
