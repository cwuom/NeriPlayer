package moe.ouom.neriplayer.core.download.storage.backend

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedTemporaryWriteArtifactsTest {
    @Test
    fun `terminal file cleanup removes only target bound post restart temporary writes`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-temporary-write").toFile()
            try {
                val backend = FileStorageBackend(root)
                val target = StorageTarget.FileTarget("album/song.mp3")
                val matchingLease = ManagedTemporaryWriteArtifacts.acquire(target)
                val matchingFile = root.resolve("album").apply { mkdirs() }
                    .resolve(matchingLease.displayName)
                matchingFile.writeText("stale")
                matchingLease.close()

                val otherLease = ManagedTemporaryWriteArtifacts.acquire(
                    StorageTarget.FileTarget("album/other.mp3")
                )
                val otherFile = root.resolve("album").resolve(otherLease.displayName)
                otherFile.writeText("other")
                otherLease.close()

                val legacyFile = root.resolve("album/.npdl_tmp_legacy.pending")
                legacyFile.writeText("legacy")

                val result = backend.cleanupTerminalTemporaryWrites(target)

                assertEquals(
                    ManagedTemporaryWriteCleanupResult.Completed(
                        deletedCount = 1,
                        missingCount = 0,
                        retainedActiveCount = 0,
                        failures = emptyList()
                    ),
                    result
                )
                assertFalse(matchingFile.exists())
                assertTrue(otherFile.exists())
                assertTrue(legacyFile.exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `terminal cleanup retains a currently active temporary file`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-temporary-write").toFile()
            try {
                val target = StorageTarget.FileTarget("song.mp3")
                val lease = ManagedTemporaryWriteArtifacts.acquire(target)
                val activeFile = root.resolve(lease.displayName)
                activeFile.writeText("active")
                val backend = FileStorageBackend(root)

                val activeResult = backend.cleanupTerminalTemporaryWrites(target)

                assertEquals(
                    ManagedTemporaryWriteCleanupResult.Completed(
                        deletedCount = 0,
                        missingCount = 0,
                        retainedActiveCount = 1,
                        failures = emptyList()
                    ),
                    activeResult
                )
                assertTrue(activeFile.exists())

                lease.close()
                val terminalResult = backend.cleanupTerminalTemporaryWrites(target)

                assertEquals(
                    ManagedTemporaryWriteCleanupResult.Completed(
                        deletedCount = 1,
                        missingCount = 0,
                        retainedActiveCount = 0,
                        failures = emptyList()
                    ),
                    terminalResult
                )
                assertFalse(activeFile.exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `batch terminal cleanup removes several target residues from one root`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-temporary-write").toFile()
            try {
                val firstTarget = StorageTarget.FileTarget("first.mp3")
                val secondTarget = StorageTarget.FileTarget("second.mp3")
                val firstLease = ManagedTemporaryWriteArtifacts.acquire(firstTarget)
                val secondLease = ManagedTemporaryWriteArtifacts.acquire(secondTarget)
                root.resolve(firstLease.displayName).writeText("first")
                root.resolve(secondLease.displayName).writeText("second")
                firstLease.close()
                secondLease.close()

                val result = FileStorageBackend(root).cleanupTerminalTemporaryWrites(
                    listOf(firstTarget, secondTarget)
                )

                assertEquals(
                    ManagedTemporaryWriteCleanupResult.Completed(
                        deletedCount = 2,
                        missingCount = 0,
                        retainedActiveCount = 0,
                        failures = emptyList()
                    ),
                    result
                )
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `batch terminal cleanup lists a SAF parent once`() {
        runBlocking {
            val parentUri = mock(Uri::class.java)
            `when`(parentUri.toString()).thenReturn("content://provider/tree/root")
            val firstTarget = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(parentUri),
                displayName = "first.mp3",
                mimeType = "audio/mpeg"
            )
            val secondTarget = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(parentUri),
                displayName = "second.mp3",
                mimeType = "audio/mpeg"
            )
            val firstLease = ManagedTemporaryWriteArtifacts.acquire(firstTarget)
            val secondLease = ManagedTemporaryWriteArtifacts.acquire(secondTarget)
            firstLease.close()
            secondLease.close()
            val backend = RecordingBackend(
                StorageDirectorySnapshot(
                    entries = listOf(
                        StorageStat(
                            reference = StorageReference.SafRef(mock(Uri::class.java)),
                            displayName = firstLease.displayName,
                            sizeBytes = 1L,
                            lastModifiedMs = null,
                            isDirectory = false
                        ),
                        StorageStat(
                            reference = StorageReference.SafRef(mock(Uri::class.java)),
                            displayName = secondLease.displayName,
                            sizeBytes = 1L,
                            lastModifiedMs = null,
                            isDirectory = false
                        )
                    ),
                    confidence = StorageConfidence.Complete
                )
            )

            val result = backend.cleanupTerminalTemporaryWrites(listOf(firstTarget, secondTarget))

            assertTrue(result is ManagedTemporaryWriteCleanupResult.Completed)
            assertEquals(1, backend.listCalls)
            assertEquals(2, backend.deletedReferences.size)
        }
    }

    @Test
    fun `old pending operation cleanup does not select new operation temporary write`() {
        runBlocking {
            val root = Files.createTempDirectory("neriplayer-temporary-write").toFile()
            try {
                val oldOperationTarget = StorageTarget.FileTarget(
                    logicalPath = "album/song.mp3",
                    temporaryWriteOwnerName = "song.mp3.npdl_pending."
                        + "c5da641b-aebd-420f-9a82-ab800ed7c02d.pending"
                )
                val newOperationTarget = StorageTarget.FileTarget(
                    logicalPath = "album/song.mp3",
                    temporaryWriteOwnerName = "song.mp3.npdl_pending."
                        + "493e2c83-40cc-4f4e-9d50-6794d61cd81a.pending"
                )
                val newLease = ManagedTemporaryWriteArtifacts.acquire(newOperationTarget)
                val newTemporaryFile = root.resolve("album").apply { mkdirs() }
                    .resolve(newLease.displayName)
                newTemporaryFile.writeText("new")
                newLease.close()

                assertTrue(
                    ManagedTemporaryWriteArtifacts.isManagedNameForTarget(
                        displayName = newLease.displayName,
                        target = newOperationTarget
                    )
                )
                assertFalse(
                    ManagedTemporaryWriteArtifacts.isManagedNameForTarget(
                        displayName = newLease.displayName,
                        target = oldOperationTarget
                    )
                )

                val result = FileStorageBackend(root)
                    .cleanupTerminalTemporaryWrites(oldOperationTarget)

                assertEquals(
                    ManagedTemporaryWriteCleanupResult.Completed(
                        deletedCount = 0,
                        missingCount = 0,
                        retainedActiveCount = 0,
                        failures = emptyList()
                    ),
                    result
                )
                assertTrue(newTemporaryFile.exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `pending audio target keeps its operation UUID when no owner is supplied`() {
        val oldOperationTarget = StorageTarget.FileTarget(
            "album/song.mp3.npdl_pending."
                + "c5da641b-aebd-420f-9a82-ab800ed7c02d.pending"
        )
        val newOperationTarget = StorageTarget.FileTarget(
            "album/song.mp3.npdl_pending."
                + "493e2c83-40cc-4f4e-9d50-6794d61cd81a.pending"
        )
        val newLease = ManagedTemporaryWriteArtifacts.acquire(newOperationTarget)
        try {
            assertTrue(
                ManagedTemporaryWriteArtifacts.isManagedNameForTarget(
                    displayName = newLease.displayName,
                    target = newOperationTarget
                )
            )
            assertFalse(
                ManagedTemporaryWriteArtifacts.isManagedNameForTarget(
                    displayName = newLease.displayName,
                    target = oldOperationTarget
                )
            )
        } finally {
            newLease.close()
        }
    }

    @Test
    fun `incomplete SAF listing never asks the backend to delete temporary writes`() {
        runBlocking {
            val parentUri = mock(Uri::class.java)
            `when`(parentUri.toString()).thenReturn("content://provider/tree/root")
            val target = StorageTarget.SafTarget(
                parent = StorageReference.SafRef(parentUri),
                displayName = "song.mp3",
                mimeType = "audio/mpeg"
            )
            val lease = ManagedTemporaryWriteArtifacts.acquire(target)
            lease.close()
            val childUri = mock(Uri::class.java)
            val backend = RecordingBackend(
                StorageDirectorySnapshot(
                    entries = listOf(
                        StorageStat(
                            reference = StorageReference.SafRef(childUri),
                            displayName = lease.displayName,
                            sizeBytes = 5L,
                            lastModifiedMs = null,
                            isDirectory = false
                        )
                    ),
                    confidence = StorageConfidence.ProviderFailure(
                        IllegalStateException("enumeration failed")
                    )
                )
            )

            val result = backend.cleanupTerminalTemporaryWrites(target)

            assertTrue(result is ManagedTemporaryWriteCleanupResult.Skipped)
            assertTrue(
                (result as ManagedTemporaryWriteCleanupResult.Skipped).reason
                    is ManagedTemporaryWriteCleanupSkipReason.IncompleteDirectory
            )
            assertTrue(backend.deletedReferences.isEmpty())
        }
    }

    private class RecordingBackend(
        private val directorySnapshot: StorageDirectorySnapshot
    ) : StorageBackend {
        val deletedReferences = mutableListOf<TrustedManagedRef>()
        var listCalls = 0

        override suspend fun list(directory: StorageReference): StorageDirectorySnapshot {
            listCalls += 1
            return directorySnapshot
        }

        override suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat> {
            return StorageLookupResult.Missing
        }

        override suspend fun <T> read(
            reference: StorageReference,
            block: suspend (InputStream) -> T
        ): StorageLookupResult<T> {
            return StorageLookupResult.Missing
        }

        override suspend fun writeRecoverable(
            target: StorageTarget,
            writer: suspend (OutputStream) -> Unit
        ): StorageWriteResult {
            return StorageWriteResult.Unsupported("not used")
        }

        override suspend fun delete(reference: TrustedManagedRef): StorageMutationResult {
            deletedReferences += reference
            return StorageMutationResult.Deleted
        }

        override suspend fun rename(
            reference: TrustedManagedRef,
            displayName: String
        ): StorageRenameResult {
            return StorageRenameResult.Unsupported("not used")
        }

        override suspend fun capabilities(reference: StorageReference): StorageCapabilities {
            return StorageCapabilities(
                canRead = false,
                canWrite = false,
                canCreate = false,
                canDelete = false,
                canRename = false,
                canMove = false,
                canCopy = false,
                hasReliableSize = false,
                hasReliableLastModified = false
            )
        }
    }
}
