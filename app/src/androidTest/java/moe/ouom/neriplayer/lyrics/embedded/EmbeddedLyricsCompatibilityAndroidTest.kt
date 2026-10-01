package moe.ouom.neriplayer.lyrics.embedded

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedLyricsCompatibilityAndroidTest {

    @Test
    fun unicodeDecimalTimestampsKeepTheirOriginalNotation() {
        listOf("[٠٠:٠١]", "[００:０１]", "[٠٠:٠١.١]").forEach { timestamp ->
            assertEquals(
                "${timestamp}original\n${timestamp}translated",
                mergeLyricsForExternalPlayers("${timestamp}original", "[00:01.100]translated")
            )
        }
    }

    @Test
    fun supplementaryDigitsPreserveUnparseableLinesWithoutThrowing() {
        listOf("[𝟘𝟘:𝟘𝟙]", "[𝟘:01]", "[00:𝟘𝟙]").forEach { timestamp ->
            assertEquals(
                "${timestamp}original\n[00:01]translated",
                mergeLyricsForExternalPlayers("${timestamp}original", "[00:01]translated")
            )
        }
    }

    @Test
    fun supplementaryFractionsRemainUnmatchedRegardlessOfUtf16Length() {
        listOf("[00:01.𝟙]", "[00:01.𝟙2]", "[00:01.1𝟚]", "[00:01.12𝟛]").forEach { timestamp ->
            assertEquals(
                "${timestamp}original\n[00:01]translated",
                mergeLyricsForExternalPlayers("${timestamp}original", "[00:01]translated")
            )
        }
    }

    @Test
    fun validTimestampMatchesAlongsideUnparseableTokensWithoutDroppingThePrefix() {
        assertEquals(
            "[𝟘𝟘:𝟘𝟙][00:01]original\n[𝟘𝟘:𝟘𝟙][00:01]translated",
            mergeLyricsForExternalPlayers(
                "[𝟘𝟘:𝟘𝟙][00:01]original",
                "[𝟘𝟘:𝟘𝟙][00:01]translated"
            )
        )
    }
}
