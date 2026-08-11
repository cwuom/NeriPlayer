package moe.ouom.neriplayer.util.io

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileCleanupTest {
    @Test
    fun clearAllFilesContinuesAfterAnEarlierFailure() {
        val attemptedNames = mutableListOf<String>()

        val cleared = clearAllFiles(
            files = listOf(File("first"), File("second"))
        ) { file ->
            attemptedNames += file.name
            file.name != "first"
        }

        assertFalse(cleared)
        assertEquals(listOf("first", "second"), attemptedNames)
    }

    @Test
    fun clearAllFilesSucceedsWhenEveryEntryIsCleared() {
        assertTrue(
            clearAllFiles(
                files = listOf(File("first"), File("second"))
            ) { true }
        )
    }
}
