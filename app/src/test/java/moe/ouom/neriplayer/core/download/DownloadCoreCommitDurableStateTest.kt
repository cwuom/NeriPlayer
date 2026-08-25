package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoreCommitDurableStateTest {
    @Test
    fun `complete metadata state is treated as a durable core commit`() {
        assertTrue(isDurableCoreArtifactState("COMPLETE"))
    }
}
