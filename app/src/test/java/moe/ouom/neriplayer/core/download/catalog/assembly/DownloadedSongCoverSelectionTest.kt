package moe.ouom.neriplayer.core.download.catalog.assembly

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedSongCoverSelectionTest {
    @Test
    fun `known indexed cover skips inspection only when snapshot verification is disabled`() {
        assertEquals("known", acceptIndexedDownloadedCover("known", setOf("known"), false) {
            throw AssertionError("trusted snapshot cover was inspected")
        })
        for (verify in listOf(true, false)) for (accessible in listOf(true, false)) {
            val inspected = mutableListOf<String>()
            val reference = if (verify) "known" else "unknown"
            val result = acceptIndexedDownloadedCover(reference, setOf("known"), verify) {
                inspected += it
                accessible
            }
            assertEquals(if (accessible) reference else null, result)
            assertEquals(listOf(reference), inspected)
        }
        assertEquals(null, acceptIndexedDownloadedCover(null, emptySet(), true) {
            throw AssertionError("missing cover was inspected")
        })
    }

    @Test
    fun `cover lookup stops at the first available candidate in order`() {
        for (availableAt in 0..4) {
            val reads = mutableListOf<Int>()
            fun read(index: Int): String? {
                reads += index
                return "cover-$index".takeIf { index == availableAt }
            }
            val cover = selectDownloadedSongCover({ read(0) }, { read(1) }, true, { read(2) }, { read(3) })
            assertEquals(if (availableAt < 4) "cover-$availableAt" else null, cover)
            assertEquals((0..minOf(availableAt, 3)).toList(), reads)
        }
    }

    @Test
    fun `fast lookup never evaluates slow sources`() {
        assertEquals(null, selectDownloadedSongCover(
            { null }, { null }, false,
            { throw AssertionError("cached embedded cover read") },
            { throw AssertionError("embedded cover read") }
        ))
    }
}
