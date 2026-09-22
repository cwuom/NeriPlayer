package moe.ouom.neriplayer.core.player.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiSuperIslandLyricLayoutTest {
    @Test
    fun takeByWeight_keepsConfiguredCjkCharacterCount() {
        assertEquals(
            "一二三四五六七",
            XiaomiSuperIslandLyricLayout.takeByWeight("一二三四五六七八九", 14)
        )
    }

    @Test
    fun splitFullLyric_keepsAContiguousPrefixAcrossBothColumns() {
        val lyric = "爱只能在回忆里完整"
        val split = XiaomiSuperIslandLyricLayout.splitFullLyric(
            text = lyric,
            showLeftCover = true,
            leftMaxWeight = 12,
            rightMaxWeight = 14
        )

        assertTrue(split.left.isNotBlank())
        assertTrue(split.right.isNotBlank())
        assertTrue(lyric.startsWith(split.left + split.right))
    }

    @Test
    fun takeByWeight_doesNotSkipAnOverflowingCharacter() {
        assertEquals("ab", XiaomiSuperIslandLyricLayout.takeByWeight("ab你c", 3))
    }
}
