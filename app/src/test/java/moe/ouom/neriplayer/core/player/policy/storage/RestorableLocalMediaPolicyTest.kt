package moe.ouom.neriplayer.core.player.policy.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class RestorableLocalMediaPolicyTest {
    @Test
    fun `readable files remain restorable`() {
        assertEquals(
            RestorableLocalMediaState.READABLE,
            resolveRestorableLocalMediaState(
                scheme = "file",
                localFileReadable = true,
            )
        )
    }

    @Test
    fun `content uri without a known grant stays unknown`() {
        assertEquals(
            RestorableLocalMediaState.UNKNOWN,
            resolveRestorableLocalMediaState(scheme = "content")
        )
    }

    @Test
    fun `known content grant is readable without opening provider`() {
        assertEquals(
            RestorableLocalMediaState.READABLE,
            resolveRestorableLocalMediaState(
                scheme = "content",
                hasPersistedReadPermission = true,
            )
        )
    }

    @Test
    fun `unsupported schemes are revoked`() {
        assertEquals(
            RestorableLocalMediaState.REVOKED,
            resolveRestorableLocalMediaState(scheme = "https")
        )
    }
}
