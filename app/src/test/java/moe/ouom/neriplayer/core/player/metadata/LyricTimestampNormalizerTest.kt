package moe.ouom.neriplayer.core.player.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimestampNormalizerTest {

    @Test
    fun `normalizeLegacyLrcTimestamps converts legacy second separator to dot`() {
        val normalized = normalizeLegacyLrcTimestamps(
            """
            [00:00:15]The sky blue archive!
            [00:12:76]新しい景色が見たくて自転車を漕いだ
            [by:夜羽小猫]
            """.trimIndent()
        )

        assertEquals(
            """
            [00:00.15]The sky blue archive!
            [00:12.76]新しい景色が見たくて自転車を漕いだ
            [by:夜羽小猫]
            """.trimIndent(),
            normalized
        )
    }

    @Test
    fun `extractPreferredNeteaseLyricContent normalizes legacy lrc fallback`() {
        val preferred = extractPreferredNeteaseLyricContent(
            """
            {
              "lrc": {
                "lyric": "[00:00:15]The sky blue archive!\n[00:12:76]新しい景色が見たくて自転車を漕いだ"
              }
            }
            """.trimIndent()
        )

        assertEquals(
            "[00:00.15]The sky blue archive!\n[00:12.76]新しい景色が見たくて自転車を漕いだ",
            preferred
        )
    }
}
