package moe.ouom.neriplayer.core.player.service.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class XiaomiSuperIslandLyricWeightTest {

    @Test
    fun `blank text has no visible prefix`() {
        assertEquals("", XiaomiSuperIslandLyricLayout.takeByWeight("   ", 4))
    }

    @Test
    fun `text within the budget is kept and trimmed`() {
        assertEquals("a b", XiaomiSuperIslandLyricLayout.takeByWeight(" a b ", 10))
        assertEquals("ab", XiaomiSuperIslandLyricLayout.takeByWeight("ab cd", 2))
        assertEquals("かな", XiaomiSuperIslandLyricLayout.takeByWeight("かなカナ", 5))
    }
}
