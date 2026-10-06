package moe.ouom.neriplayer.data.model.comments

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentImageTest {

    @Test
    fun `aspect ratio falls back to square for unknown sizes`() {
        assertEquals(16f / 9f, CommentImage("https://i0.hdslb.com/a.jpg", width = 1_600, height = 900).aspectRatio, 1e-6f)
        assertEquals(1f, CommentImage("https://i0.hdslb.com/a.jpg", width = 0, height = 900).aspectRatio, 0f)
        assertEquals(1f, CommentImage("https://i0.hdslb.com/a.jpg", width = 1_600, height = 0).aspectRatio, 0f)
    }
}
