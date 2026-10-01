package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.model.sync.transport.WebDavConcurrencyToken
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTransportModelsTest {
    @Test
    fun `conditional tokens require at least one nonblank validator`() {
        for (etag in listOf(null, "", " ")) for (modified in listOf(null, "", " ")) {
            assertFalse(WebDavConcurrencyToken(etag, modified).hasConditionToken())
        }
        assertTrue(WebDavConcurrencyToken("v", null).hasConditionToken())
        assertTrue(WebDavConcurrencyToken(null, "date").hasConditionToken())
        assertTrue(WebDavConcurrencyToken("v", "date").hasConditionToken())
    }
}
