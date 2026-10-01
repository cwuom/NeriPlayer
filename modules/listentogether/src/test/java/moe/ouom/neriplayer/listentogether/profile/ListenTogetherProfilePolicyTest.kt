package moe.ouom.neriplayer.listentogether.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ListenTogetherProfilePolicyTest {
    @Test
    fun persistedNicknameKeepsTheExistingCharacterAndLengthRules() {
        assertEquals("灵梦Alice123", sanitizeListenTogetherNicknameOrNull(" 灵梦Alice123 "))
        assertNull(sanitizeListenTogetherNicknameOrNull("Alice_123"))
        assertNull(sanitizeListenTogetherNicknameOrNull("Alice😀"))
        assertNull(sanitizeListenTogetherNicknameOrNull(" "))
        assertNull(sanitizeListenTogetherNicknameOrNull("a".repeat(25)))
        assertEquals("a".repeat(24), sanitizeListenTogetherNicknameOrNull("a".repeat(24)))
    }

    @Test
    fun generatedProfileIsValidAndUsesAnIndependentUuid() {
        val first = buildListenTogetherUserUuid()
        val second = buildListenTogetherUserUuid()
        assertEquals(first, UUID.fromString(first).toString())
        assertNotEquals(first, second)
        val nickname = buildDefaultListenTogetherNickname()
        assertTrue(nickname.startsWith("Neri"))
        assertEquals(10, nickname.length)
        assertEquals(nickname, sanitizeListenTogetherNicknameOrNull(nickname))
    }
}
