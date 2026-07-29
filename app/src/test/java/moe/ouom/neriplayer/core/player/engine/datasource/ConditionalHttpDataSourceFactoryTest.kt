package moe.ouom.neriplayer.core.player.engine.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConditionalHttpDataSourceFactoryTest {

    @Test
    fun `youtube requests leave range construction to Media3`() {
        val headers = removeExplicitRangeHeader(
            linkedMapOf(
                "Authorization" to "Bearer token",
                "range" to "bytes=1048576-2097151",
                "Range" to "bytes=2097152-3145727",
                "User-Agent" to "NeriPlayer"
            )
        )

        assertFalse(headers.keys.any { it.equals("Range", ignoreCase = true) })
        assertEquals("Bearer token", headers["Authorization"])
        assertEquals("NeriPlayer", headers["User-Agent"])
    }
}
