package moe.ouom.neriplayer.core.api.netease

import org.junit.Assert.assertThrows
import org.junit.Test

class NeteaseClientAlbumValidationTest {

    @Test
    fun `album detail rejects zero id before network request`() {
        assertThrows(IllegalArgumentException::class.java) {
            NeteaseClient().getAlbumDetail(0L)
        }
    }
}
