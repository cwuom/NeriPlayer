package moe.ouom.neriplayer.ui.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperBackgroundTest {
    @Test
    fun `late palette from a previous song cannot commit`() {
        assertFalse(
            shouldCommitDynamicBackgroundPalette(
                requestIdentity = "song-a|cover-a|1|false|true",
                latestIdentity = "song-b|cover-b|2|false|true"
            )
        )
        assertTrue(
            shouldCommitDynamicBackgroundPalette(
                requestIdentity = "song-b|cover-b|2|false|true",
                latestIdentity = "song-b|cover-b|2|false|true"
            )
        )
    }
}
