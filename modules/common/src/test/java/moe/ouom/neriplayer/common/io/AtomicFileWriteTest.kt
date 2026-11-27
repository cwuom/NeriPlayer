package moe.ouom.neriplayer.common.io

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicFileWriteTest {
    @Test
    fun interruptedStreamingWriteKeepsPreviousFile() {
        val directory = Files.createTempDirectory("neriplayer-atomic-write").toFile()
        try {
            val target = directory.resolve("state.json")
            target.writeText("previous")

            assertThrows(IOException::class.java) {
                target.writeUtf8Atomically { writer ->
                    writer.write("partial replacement")
                    writer.flush()
                    throw IOException("injected write failure")
                }
            }

            assertEquals("previous", target.readText())
            assertEquals(listOf(target), directory.listFiles()?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

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
