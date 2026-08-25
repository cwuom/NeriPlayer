package moe.ouom.neriplayer.core.download

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStoragePendingAudioPromotionTest {
    @Test
    fun `concurrent FileRoot pending promotions preserve the first committed target`() = runBlocking {
        val root = Files.createTempDirectory("neriplayer-pending-promote").toFile()
        try {
            val finalName = "song.mp3"
            val firstPending = File(root, "$finalName.npdl_pending.first.pending")
                .apply { writeText("first") }
            val secondPending = File(root, "$finalName.npdl_pending.second.pending")
                .apply { writeText("second-payload") }
            val gate = CompletableDeferred<Unit>()
            val firstReady = CompletableDeferred<Unit>()
            val secondReady = CompletableDeferred<Unit>()

            val results = listOf(
                async(Dispatchers.Default) {
                    firstReady.complete(Unit)
                    gate.await()
                    runCatching {
                        ManagedDownloadStorage.promotePendingFileAudio(
                            root = root,
                            pendingName = firstPending.name,
                            finalName = finalName
                        )
                    }
                },
                async(Dispatchers.Default) {
                    secondReady.complete(Unit)
                    gate.await()
                    runCatching {
                        ManagedDownloadStorage.promotePendingFileAudio(
                            root = root,
                            pendingName = secondPending.name,
                            finalName = finalName
                        )
                    }
                }
            ).also {
                firstReady.await()
                secondReady.await()
                gate.complete(Unit)
            }.awaitAll()

            val failures = results.mapNotNull { result -> result.exceptionOrNull() }
            assertEquals(1, failures.count { it is java.io.IOException })
            assertEquals(1, results.count { it.isSuccess })
            assertTrue(File(root, finalName).isFile)
            assertTrue(
                File(root, finalName).readText() in setOf("first", "second-payload")
            )
            assertEquals(1, listOf(firstPending, secondPending).count(File::isFile))
        } finally {
            root.deleteRecursively()
        }
    }

}
