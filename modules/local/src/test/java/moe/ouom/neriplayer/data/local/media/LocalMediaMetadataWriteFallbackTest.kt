package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome.FAILED
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome.NOT_WRITABLE
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome.SIDECAR_ONLY
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome.SUCCESS
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome.UNSUPPORTED_OR_UNREADABLE
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome.UNSUPPORTED_EMBEDDED_COVER
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMediaMetadataWriteFallbackTest {
    @Test
    fun `fallback writes keep the most informative outcome of both attempts`() {
        val expectations = listOf(
            Triple(SUCCESS, SIDECAR_ONLY, SIDECAR_ONLY),
            Triple(SIDECAR_ONLY, FAILED, SIDECAR_ONLY),
            Triple(NOT_WRITABLE, UNSUPPORTED_EMBEDDED_COVER, UNSUPPORTED_EMBEDDED_COVER),
            Triple(UNSUPPORTED_EMBEDDED_COVER, FAILED, FAILED),
            Triple(FAILED, NOT_WRITABLE, FAILED),
            Triple(UNSUPPORTED_OR_UNREADABLE, FAILED, FAILED),
            Triple(UNSUPPORTED_OR_UNREADABLE, SUCCESS, UNSUPPORTED_OR_UNREADABLE),
            Triple(NOT_WRITABLE, UNSUPPORTED_OR_UNREADABLE, UNSUPPORTED_OR_UNREADABLE),
            Triple(NOT_WRITABLE, SUCCESS, NOT_WRITABLE),
            Triple(SUCCESS, SUCCESS, NOT_WRITABLE)
        )

        expectations.forEach { (current, candidate, expected) ->
            assertEquals(
                "$current then $candidate",
                expected,
                LocalMediaSupport.selectEditableMetadataWriteFallback(current, candidate)
            )
        }
    }
}
