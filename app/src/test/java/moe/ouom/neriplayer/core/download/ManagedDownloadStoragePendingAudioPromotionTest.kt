package moe.ouom.neriplayer.core.download

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStoragePendingAudioPromotionTest {
    @Test
    fun `tree promotion uses the counted tagged pending size instead of stale provider data`() {
        assertEquals(
            5_060_359L,
            ManagedDownloadStorage.resolvePendingTreeAudioPromotionExpectedSize(
                reportedSizeBytes = 4_955_786L,
                countedSizeBytes = 5_060_359L
            )
        )
    }

    @Test
    fun `tree promotion counts pending audio when the provider omits its size`() {
        assertEquals(
            5_060_359L,
            ManagedDownloadStorage.resolvePendingTreeAudioPromotionExpectedSize(
                reportedSizeBytes = null,
                countedSizeBytes = 5_060_359L
            )
        )
        assertNull(
            ManagedDownloadStorage.resolvePendingTreeAudioPromotionExpectedSize(
                reportedSizeBytes = 0L,
                countedSizeBytes = 0L
            )
        )
    }

    @Test
    fun `tree promotion only creates a final target after a complete vacant directory check`() {
        val targetName = "song.mp3"

        assertFalse(
            ManagedDownloadStorage.canCreateTreePromotionTargetWithoutReplacing(
                enumerationComplete = false,
                existingNames = emptyList(),
                targetName = targetName
            )
        )
        assertFalse(
            ManagedDownloadStorage.canCreateTreePromotionTargetWithoutReplacing(
                enumerationComplete = true,
                existingNames = listOf(targetName),
                targetName = targetName
            )
        )
        assertFalse(
            ManagedDownloadStorage.canCreateTreePromotionTargetWithoutReplacing(
                enumerationComplete = true,
                existingNames = listOf(".$targetName.backup"),
                targetName = targetName
            )
        )
        assertFalse(
            ManagedDownloadStorage.canCreateTreePromotionTargetWithoutReplacing(
                enumerationComplete = true,
                existingNames = listOf(
                    ".${targetName}.123e4567-e89b-12d3-a456-426614174000.backup"
                ),
                targetName = targetName
            )
        )
        assertTrue(
            ManagedDownloadStorage.canCreateTreePromotionTargetWithoutReplacing(
                enumerationComplete = true,
                existingNames = listOf("$targetName.npdl_pending.123.pending"),
                targetName = targetName
            )
        )
    }

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

    @Test
    fun `demoting a published file moves it to pending without replacing an existing pending target`() = runBlocking {
        val root = Files.createTempDirectory("neriplayer-published-demote").toFile()
        try {
            val published = File(root, "song.mp3").apply { writeText("published") }
            val pendingName = "song.mp3.npdl_pending.recovery.pending"

            val demoted = ManagedDownloadStorage.demotePublishedFileAudio(
                root = root,
                publishedName = published.name,
                pendingName = pendingName
            )

            assertFalse(published.exists())
            assertEquals("published", requireNotNull(demoted).readText())

            val replacement = File(root, "song.mp3").apply { writeText("new-published") }
            val existingPending = File(root, pendingName).apply { writeText("protected-pending") }

            val rejected = ManagedDownloadStorage.demotePublishedFileAudio(
                root = root,
                publishedName = replacement.name,
                pendingName = existingPending.name
            )

            assertNull(rejected)
            assertEquals("new-published", replacement.readText())
            assertEquals("protected-pending", existingPending.readText())
        } finally {
            root.deleteRecursively()
        }
    }

}
