package moe.ouom.neriplayer.data.lyrics.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicLyricSelectionTest {
    @Test
    fun amllReplacementDropsTranslationFromAnotherTimeline() {
        val selected = chooseQQMusicLyrics(
            qqLyric = "[00:00.00]QQ",
            qqTranslatedLyric = "[00:00.00]翻译",
            amllLyric = "[00:00.00]AMLL"
        )

        assertEquals("[00:00.00]AMLL", selected.first)
        assertNull(selected.second)
    }
}
