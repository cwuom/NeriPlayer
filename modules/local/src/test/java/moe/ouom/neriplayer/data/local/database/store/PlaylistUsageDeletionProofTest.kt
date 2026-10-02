package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlaylistUsageDeletionProofTest {
    @Test fun proofUsesStableJsonFieldsAndLegacyNullHasNoObservation() {
        val token = SyncCausalToken("usage-delete:a", Long.MAX_VALUE)
        val entry = UsageEntry(7, "playlist", null, 2, "netease", 1, 1, observedDeletionTokens = listOf(token, token))
        val json = entry.toEntity().usageDeletionTokensJson
        assertEquals("[{\"deviceId\":\"usage-delete:a\",\"counter\":9223372036854775807}]", json)
        assertEquals(listOf(token), decodeUsageDeletionTokens(json))
        assertTrue(decodeUsageDeletionTokens(null).isEmpty())
        assertTrue(decodeUsageDeletionTokens("[]").isEmpty())
    }

    @Test fun malformedProofCannotBeTreatedAsAnEmptyOrSuccessfulObservation() {
        for (json in listOf("broken", "null", "[null]", "[{}]", "[{\"deviceId\":\"x\",\"counter\":0}]", "[{\"deviceId\":\"x\",\"counter\":1.5}]", "[{\"deviceId\":\"x\",\"counter\":9223372036854775808}]", "[{deviceId:'x',counter:1}]", "[] []")) {
            assertTrue("Unexpectedly accepted $json", runCatching { decodeUsageDeletionTokens(json) }.exceptionOrNull() is IOException)
        }
    }

    @Test fun wronglyTypedProofFieldsCannotBecomeAnObservationThroughJsonCoercion() {
        val malformed = listOf(
            "[{\"counter\":1}]",
            "[{\"deviceId\":\"x\"}]",
            "[{\"deviceId\":null,\"counter\":1}]",
            "[{\"deviceId\":{},\"counter\":1}]",
            "[{\"deviceId\":1,\"counter\":1}]",
            "[{\"deviceId\":true,\"counter\":1}]",
            "[{\"deviceId\":\" \",\"counter\":1}]",
            "[{\"deviceId\":\"x\",\"counter\":null}]",
            "[{\"deviceId\":\"x\",\"counter\":[]}]",
            "[{\"deviceId\":\"x\",\"counter\":\"1\"}]",
            "[{\"deviceId\":\"x\",\"counter\":true}]",
            "[{\"deviceId\":\"x\",\"counter\":-1}]"
        )
        for (json in malformed) {
            assertThrows("Unexpectedly accepted $json", IOException::class.java) { decodeUsageDeletionTokens(json) }
        }
    }
}
