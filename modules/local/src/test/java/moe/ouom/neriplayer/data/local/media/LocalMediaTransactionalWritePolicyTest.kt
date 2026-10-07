package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaTransactionalWritePolicyTest {
    @Test
    fun `content and file sources always use staged transactional writes`() {
        assertTrue(usesStagedWrite(scheme = "content", path = null))
        assertTrue(usesStagedWrite(scheme = "CONTENT", path = "relative/a.flac"))
        assertTrue(usesStagedWrite(scheme = "File", path = null))
    }

    @Test
    fun `scheme-less sources need an absolute path`() {
        assertTrue(usesStagedWrite(scheme = null, path = "/music/a.flac"))
        assertTrue(usesStagedWrite(scheme = "  ", path = "/music/a.flac"))
        assertFalse(usesStagedWrite(scheme = null, path = "music/a.flac"))
        assertFalse(usesStagedWrite(scheme = "", path = null))
    }

    @Test
    fun `other schemes never use staged writes`() {
        assertFalse(usesStagedWrite(scheme = "https", path = "/music/a.flac"))
    }

    private fun usesStagedWrite(scheme: String?, path: String?) =
        LocalMediaSupport.shouldUseTransactionalStagedWriteImpl(sourceScheme = scheme, sourcePath = path)
}
