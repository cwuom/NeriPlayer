package moe.ouom.neriplayer.platform.netease.api.client

import org.junit.Assert.assertThrows
import org.junit.Test

class NeteaseClientAlbumValidationTest {

    @Test
    fun `album detail rejects zero id before network request`() {
        assertThrows(IllegalArgumentException::class.java) {
            NeteaseClient { error("Comment token is outside this test") }.getAlbumDetail(0L)
        }
    }
}
