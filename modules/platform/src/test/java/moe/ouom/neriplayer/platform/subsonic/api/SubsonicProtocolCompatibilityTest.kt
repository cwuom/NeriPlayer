package moe.ouom.neriplayer.platform.subsonic.api

import moe.ouom.neriplayer.common.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubsonicProtocolCompatibilityTest {
    @Test fun `unsupported authentication is not presented as a bad password`() {
        for (code in listOf(41, 42, 43)) {
            val error = SubsonicException.protocol(code)
            assertEquals(SubsonicFailureKind.UNSUPPORTED, error.kind)
            assertNotEquals(R.string.server_error_auth, subsonicErrorMessageRes(error))
            assertFalse(error.retryable)
        }
    }

    @Test fun `invalid api key remains an authentication failure`() {
        val error = SubsonicException.protocol(44)
        assertEquals(SubsonicFailureKind.AUTHENTICATION, error.kind)
        assertFalse(error.retryable)
    }

    @Test fun `generic server errors are not globally treated as optional capabilities`() {
        assertEquals(SubsonicFailureKind.INVALID_RESPONSE, SubsonicException.protocol(0).kind)
        assertEquals(SubsonicFailureKind.FORBIDDEN, SubsonicException.protocol(50).kind)
    }
}
