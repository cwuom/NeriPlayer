package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalMediaLyricSourcePriorityTest {
    @Test
    fun `an existing sidecar wins even when it is empty`() {
        assertEquals("[00:01]sidecar", resolve(sidecar = "[00:01]sidecar", embedded = "[00:01]embedded", metadata = "[00:01]metadata"))
        assertEquals("", resolve(sidecar = "", embedded = "[00:01]embedded", metadata = "[00:01]metadata"))
    }

    @Test
    fun `embedded lyrics are used only when they contain text`() {
        assertEquals("[00:01]embedded", resolve(sidecar = null, embedded = "[00:01]embedded", metadata = "[00:01]metadata"))
        assertEquals("[00:01]metadata", resolve(sidecar = null, embedded = "  ", metadata = "[00:01]metadata"))
        assertEquals("[00:01]metadata", resolve(sidecar = null, embedded = null, metadata = "[00:01]metadata"))
        assertNull(resolve(sidecar = null, embedded = null, metadata = null))
    }

    private fun resolve(sidecar: String?, embedded: String?, metadata: String?) =
        LocalMediaSupport.resolveLocalLyricContentByPriority(
            sidecarContent = sidecar,
            embeddedContent = embedded,
            metadataFallback = metadata
        )
}
