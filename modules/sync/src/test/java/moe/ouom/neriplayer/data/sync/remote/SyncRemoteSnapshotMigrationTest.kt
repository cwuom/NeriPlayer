package moe.ouom.neriplayer.data.sync.remote

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.Assert.*
import org.junit.Test

class SyncRemoteSnapshotMigrationTest {
    private val data = SyncData(deviceId = "remote", lastModified = 10)
    private val content = SyncDataSerializer.serialize(data, false)
    private val challenge = SyncProtocolUpgradeChallenge("a".repeat(64), "1".repeat(64))

    @Test fun `valid legacy content cannot retain or normalize data before approval`() = runTest {
        val required = SyncProtocolUpgradeRequiredException("upgrade", challenge)
        val decoder = SyncRemoteSnapshotDecoder({ error("unapproved data must not normalize") }, { it }, { it },
            beforeSanitize = { error("unapproved lyrics must not be cached") })
        val failure = decoder.decodeForMigration(content, { IOException("empty") }, { throw required },
            beforeNormalization = { error("unapproved archive must not be captured") }).exceptionOrNull()
        assertSame(required, failure)
    }

    @Test fun `authorized legacy content retains original data before normalizing`() = runTest {
        val steps = mutableListOf<String>()
        val decoder = SyncRemoteSnapshotDecoder({ steps += "normalize"; it }, { it }, { it },
            beforeSanitize = { assertEquals(data, it); steps += "retain" })
        val decoded = decoder.decodeForMigration(content, { IOException("empty") },
            authorizeMigration = { assertArrayEquals(content, it); steps += "approve" },
            beforeNormalization = { assertEquals(data, it); steps += "capture" }).getOrThrow()
        assertEquals(data, decoded)
        assertEquals(listOf("approve", "capture", "retain", "normalize"), steps)
    }

    @Test fun `corrupt and empty content cannot request an upgrade`() = runTest {
        val decoder = SyncRemoteSnapshotDecoder { it }
        for (invalid in listOf(byteArrayOf(), byteArrayOf(1, 2, 3))) {
            val failure = decoder.decodeForMigration(invalid, { IOException("empty") },
                authorizeMigration = { error("corrupt content must not request upgrade") }).exceptionOrNull()
            assertTrue(failure is Exception)
            assertFalse(failure is SyncProtocolUpgradeRequiredException)
        }
    }

    @Test fun `migration authorization cancellation propagates before caching`() = runTest {
        val cancellation = CancellationException("cancelled")
        val decoder = SyncRemoteSnapshotDecoder({ error("must not normalize") }, { it }, { it },
            beforeSanitize = { error("must not cache") })
        assertSame(cancellation, runCatching {
            decoder.decodeForMigration(content, { IOException("empty") }, { throw cancellation })
        }.exceptionOrNull())
    }

    @Test fun `cancellation after authorization stops capture and local normalization`() = runTest {
        val cancellation = CancellationException("cancelled after approval")
        var authorized = false
        var activityChecks = 0
        val decoder = SyncRemoteSnapshotDecoder({ error("cancelled data must not normalize") }, { it }, { it },
            beforeSanitize = { error("cancelled lyrics must not be cached") })
        assertSame(cancellation, runCatching {
            decoder.decodeForMigration(content, { IOException("empty") },
                authorizeMigration = { authorized = true },
                beforeNormalization = { error("cancelled archive must not be captured") },
                checkActive = { activityChecks++; if (authorized) throw cancellation })
        }.exceptionOrNull())
        assertTrue(authorized)
        assertEquals(2, activityChecks)
    }
}
