package moe.ouom.neriplayer.util.io

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileWriteTest {
    @Test
    fun writeTextAtomically_replacesContentWithoutLeavingPendingFiles() {
        val directory = Files.createTempDirectory("neriplayer-atomic-write").toFile()
        try {
            val target = directory.resolve("state.json")
            target.writeTextAtomically("{\"version\":1}")
            target.writeTextAtomically("{\"version\":2}")

            assertEquals("{\"version\":2}", target.readText())
            assertTrue(directory.listFiles().orEmpty().all { it == target })
        } finally {
            directory.deleteRecursively()
        }
    }
}
