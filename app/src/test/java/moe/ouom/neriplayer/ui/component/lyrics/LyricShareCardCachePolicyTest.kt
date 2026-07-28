package moe.ouom.neriplayer.ui.component.lyrics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricShareCardCachePolicyTest {
    @Test
    fun `cache pruning skips files that may still be referenced`() {
        val files = listOf(
            file("new", 10_000L),
            file("recent", 9_900L),
            file("newer", 9_500L)
        )

        try {
            val deleted = lyricShareCardFilesToDelete(
                files = files,
                nowMs = 10_000L,
                maxFiles = 2,
                minAgeMs = 2_000L
            )

            assertEquals(emptyList<File>(), deleted)
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test
    fun `cache pruning removes only old files beyond retention count`() {
        val files = listOf(
            file("new", 10_000L),
            file("kept", 9_500L),
            file("old", 1_000L)
        )

        try {
            val deleted = lyricShareCardFilesToDelete(
                files = files,
                nowMs = 10_000L,
                maxFiles = 2,
                minAgeMs = 2_000L
            )

            assertEquals(listOf(files[2]), deleted)
        } finally {
            files.forEach(File::delete)
        }
    }

    private fun file(name: String, modifiedAt: Long): File {
        return File.createTempFile("lyric-$name-", ".png").apply {
            check(setLastModified(modifiedAt))
        }
    }
}
