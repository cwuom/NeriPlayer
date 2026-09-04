package moe.ouom.neriplayer.core.player.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadParallelismTest {

    @Test
    fun `missing bootstrap parallelism uses the product default`() {
        assertEquals(DEFAULT_DOWNLOAD_PARALLELISM, INITIAL_DOWNLOAD_PARALLELISM)
        assertEquals(
            DEFAULT_DOWNLOAD_PARALLELISM,
            resolveInitialDownloadParallelism(null)
        )
    }

    @Test
    fun `persisted bootstrap parallelism is retained`() {
        assertEquals(4, resolveInitialDownloadParallelism(4))
        assertEquals(MAX_DOWNLOAD_PARALLELISM, resolveInitialDownloadParallelism(9))
    }
}
