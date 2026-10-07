package moe.ouom.neriplayer.core.download.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedAudioCreatedAtConfidenceTest {
    @Test
    fun `creation time sources map to their confidence level`() {
        mapOf(
            "CORE_COMMIT" to "EXACT",
            " filesystem_birth " to "EXACT",
            "MediaStore_Date_Added" to "PROVIDER_REPORTED",
            "PROVIDER_NATIVE" to "PROVIDER_REPORTED",
            "mtime" to "INFERRED",
            "LEGACY_V15" to "INFERRED",
            "IMPORT_TIME" to "INFERRED",
            "SERVER_CLOCK" to "UNKNOWN",
            "" to "UNKNOWN"
        ).forEach { (source, expected) ->
            assertEquals(source, expected, resolveCreatedAtConfidence(source))
        }
        assertEquals("UNKNOWN", resolveCreatedAtConfidence(null))
    }
}
