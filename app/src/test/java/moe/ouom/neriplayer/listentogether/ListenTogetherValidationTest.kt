package moe.ouom.neriplayer.listentogether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTogetherValidationTest {

    @Test
    fun `nickname validation accepts han letters and digits without regex init crash`() {
        assertNull(validateListenTogetherNickname("灵梦Alice123"))
    }

    @Test
    fun `nickname validation rejects unsupported punctuation`() {
        assertNotNull(validateListenTogetherNickname("Alice_123"))
    }

    @Test
    fun `nickname sanitizer trims valid nickname`() {
        assertEquals("测试123", sanitizeListenTogetherNicknameOrNull("  测试123  "))
    }
}
