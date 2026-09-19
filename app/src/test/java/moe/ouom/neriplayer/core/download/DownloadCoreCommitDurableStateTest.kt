package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.policy.isDurableCoreArtifactState
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoreCommitDurableStateTest {
    @Test
    fun `complete metadata state is treated as a durable core commit`() {
        assertTrue(isDurableCoreArtifactState("COMPLETE"))
    }
}
