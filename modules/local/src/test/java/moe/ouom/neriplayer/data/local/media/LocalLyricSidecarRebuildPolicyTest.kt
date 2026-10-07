package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricSidecarRebuildPolicyTest {
    private enum class Kind { ORIGINAL, TRANSLATED, ROMANIZED }

    @Test
    fun `sidecars are rebuilt when an expected lyric kind has no sidecar`() {
        assertTrue(shouldRebuild(expected = setOf(Kind.ORIGINAL), present = emptySet()))
        assertTrue(shouldRebuild(expected = setOf(Kind.ORIGINAL, Kind.TRANSLATED), present = setOf(Kind.ORIGINAL)))
        assertTrue(
            shouldRebuild(
                expected = Kind.entries.toSet(),
                present = setOf(Kind.ORIGINAL, Kind.TRANSLATED)
            )
        )
    }

    @Test
    fun `sidecars are kept when every expected lyric kind already has one`() {
        assertFalse(shouldRebuild(expected = emptySet(), present = emptySet()))
        assertFalse(shouldRebuild(expected = setOf(Kind.ROMANIZED), present = setOf(Kind.ROMANIZED)))
        assertFalse(shouldRebuild(expected = Kind.entries.toSet(), present = Kind.entries.toSet()))
    }

    private fun shouldRebuild(expected: Set<Kind>, present: Set<Kind>) =
        LocalMediaSupport.shouldRebuildLyricSidecarsImpl(
            expectedOriginal = Kind.ORIGINAL in expected,
            expectedTranslated = Kind.TRANSLATED in expected,
            expectedRomanized = Kind.ROMANIZED in expected,
            hasOriginalSidecar = Kind.ORIGINAL in present,
            hasTranslatedSidecar = Kind.TRANSLATED in present,
            hasRomanizedSidecar = Kind.ROMANIZED in present
        )
}
