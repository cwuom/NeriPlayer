package moe.ouom.neriplayer.data.sync.webdav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConcurrencyFallbackPolicyTest {
    @Test
    fun `matching fingerprint allows fallback write`() {
        assertTrue(shouldAllowUnconditionalWebDavWrite("same", "same"))
    }

    @Test
    fun `changed or missing fingerprint rejects fallback write`() {
        assertFalse(shouldAllowUnconditionalWebDavWrite("old", "new"))
        assertFalse(shouldAllowUnconditionalWebDavWrite("old", null))
        assertFalse(shouldAllowUnconditionalWebDavWrite(null, "old"))
    }
}
