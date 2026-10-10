package moe.ouom.neriplayer.core.download.storage.delete

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.storage.backend.TrustedManagedRef
import moe.ouom.neriplayer.data.model.download.storage.StorageReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class ManagedDownloadFileReferenceDeleteConfirmationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @Test
    fun `deleted and already missing file references are both confirmed`() {
        val existing = temporaryFolder.newFile("song.flac").apply { writeText("audio") }
        val references = fileReferences(existing, File(temporaryFolder.root, "gone.flac"))
        val finished = mutableListOf<Pair<String, Boolean>>()

        val result = executor().deleteReferences(
            context = context,
            references = references,
            deletePolicy = trustedPolicy(references),
            onDeleteAttemptFinished = { reference, deleted ->
                finished += reference.externalReference to deleted
            }
        )

        assertEquals(references.map(TrustedManagedRef::externalReference).toSet(), result.deletedReferences)
        assertFalse(result.hasUnconfirmedDeletes)
        assertFalse(existing.exists())
        assertEquals(references.map { it.externalReference to true }, finished)
    }

    @Test
    fun `concurrent deletes confirm deleted and missing file references in one round`() = runTest {
        val existing = temporaryFolder.newFile("cover.jpg").apply { writeText("cover") }
        val references = fileReferences(existing, File(temporaryFolder.root, "gone.jpg"))
        val finished = mutableMapOf<String, Boolean>()

        val result = executor(StandardTestDispatcher(testScheduler)).deleteReferencesConcurrently(
            context = context,
            references = references,
            deletePolicy = trustedPolicy(references),
            onDeleteAttemptFinished = { reference, deleted ->
                finished[reference.externalReference] = deleted
            }
        )

        assertEquals(references.map(TrustedManagedRef::externalReference).toSet(), result.deletedReferences)
        assertFalse(result.hasUnconfirmedDeletes)
        assertFalse(existing.exists())
        assertEquals(references.associate { it.externalReference to true }, finished)
    }

    private fun executor(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) =
        ManagedDownloadReferenceDeleteExecutor(
            tag = "ManagedDownloadFileReferenceDeleteConfirmationTest",
            isReferenceAllowed = { reference, enumerated, _, _ ->
                enumerated.any { it.externalReference == reference }
            },
            workerDispatcher = dispatcher
        )

    private fun fileReferences(vararg files: File) = files.map { file ->
        TrustedManagedRef(StorageReference.FileRef(file.absolutePath))
    }

    private fun trustedPolicy(references: List<TrustedManagedRef>) = ManagedDownloadDeletePolicy(
        managedFileRoots = emptyList(),
        managedTreeRoots = emptyList(),
        trustedReferences = references.toSet()
    )
}
