package moe.ouom.neriplayer.data.local.database.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDownloadUpgradePlanTest {
    @Test
    fun `pending and cancelled legacy rows do not require a SAF snapshot`() {
        assertFalse(
            legacyPayloadNeedsManagedRootSnapshot(
                """{"download_pending_queue":{"stable_key":"song"}}"""
            )
        )
        assertFalse(
            legacyPayloadNeedsManagedRootSnapshot(
                """{"download_cancelled_key":{"stable_key":"song"}}"""
            )
        )
    }

    @Test
    fun `metadata rows and malformed rows remain blocked without a snapshot`() {
        assertTrue(
            legacyPayloadNeedsManagedRootSnapshot(
                """{"download_snapshot_metadata":{"audio_name":"song.mp3"}}"""
            )
        )
        assertTrue(legacyPayloadNeedsManagedRootSnapshot("{"))
    }
}
