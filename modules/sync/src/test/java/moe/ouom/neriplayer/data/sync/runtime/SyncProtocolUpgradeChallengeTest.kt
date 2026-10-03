package moe.ouom.neriplayer.data.sync.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolUpgradeChallengeTest {
    private val target = "a".repeat(64)
    private val fingerprint = "1".repeat(64)
    private val invalidHashes = listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "../remote")

    @Test
    fun `challenge accepts canonical SHA256 identifiers`() {
        val challenge = SyncProtocolUpgradeChallenge(target, fingerprint)
        assertEquals(target, challenge.targetId)
        assertEquals(fingerprint, challenge.fingerprint)
        assertEquals(0, challenge.fromVersion)
        assertEquals(4, challenge.toVersion)
        assertEquals(challenge, challenge.copy())
    }

    @Test
    fun `invalid target cannot form a persisted permission key`() {
        for (invalid in invalidHashes) {
            assertTrue(runCatching { SyncProtocolUpgradeChallenge(invalid, fingerprint) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `invalid fingerprint cannot identify an approved remote generation`() {
        for (invalid in invalidHashes) {
            assertTrue(runCatching { SyncProtocolUpgradeChallenge(target, invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun `copy cannot bypass target or fingerprint validation`() {
        val challenge = SyncProtocolUpgradeChallenge(target, fingerprint)
        assertTrue(runCatching { challenge.copy(targetId = "other") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { challenge.copy(fingerprint = "F".repeat(64)) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `migration versions distinguish permissions and reject downgrade requests`() {
        val legacy = SyncProtocolUpgradeChallenge(target, fingerprint)
        val v3 = legacy.copy(fromVersion = 3)
        assertTrue(legacy != v3)
        for ((from, to) in listOf(-1 to 4, 4 to 4, 5 to 4, 0 to 0)) {
            assertTrue(runCatching { legacy.copy(fromVersion = from, toVersion = to) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
