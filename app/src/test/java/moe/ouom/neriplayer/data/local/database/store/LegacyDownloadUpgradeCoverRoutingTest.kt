package moe.ouom.neriplayer.data.local.database.store

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadCoverAssetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyDownloadUpgradeCoverRoutingTest {
    private val managedCover = ManagedDownloadStorage.StoredEntry(
        name = "Artist - Song-12345678.jpg",
        reference = "content://managed/Covers/document-1",
        mediaUri = "content://media/document-1",
        localFilePath = "/managed/Covers/Artist - Song-12345678.jpg",
        sizeBytes = 128L,
        lastModifiedMs = 1L
    )
    private val covers = mapOf(managedCover.name to managedCover)

    @Test
    fun `managed cover aliases are reused without another materialization target`() {
        listOf(
            managedCover.reference,
            managedCover.mediaUri,
            managedCover.localFilePath
        ).forEach { reference ->
            assertEquals(
                managedCover,
                resolveLegacyManagedCoverEntry(
                    reference = reference,
                    persistedFileName = null,
                    coverEntriesByName = covers
                )
            )
        }
    }

    @Test
    fun `persisted short file name refreshes a stale provider reference`() {
        assertEquals(
            managedCover,
            resolveLegacyManagedCoverEntry(
                reference = "content://old-provider/stale-document",
                persistedFileName = managedCover.name,
                coverEntriesByName = covers
            )
        )
    }

    @Test
    fun `external cover is not mistaken for a managed entry`() {
        assertNull(
            resolveLegacyManagedCoverEntry(
                reference = "content://legacy/external-cover",
                persistedFileName = null,
                coverEntriesByName = covers
            )
        )
    }

    @Test
    fun `managed asset reference is replaced only by a durable source reference`() {
        assertEquals(
            "https://example.test/original.jpg",
            selectLegacyRestorableCoverReference(
                existingReference = managedCover.reference,
                sourceReference = "https://example.test/original.jpg",
                existingReferenceIsManaged = true
            )
        )
        assertEquals(
            "content://legacy/external-cover",
            selectLegacyRestorableCoverReference(
                existingReference = "content://legacy/external-cover",
                sourceReference = "https://example.test/original.jpg",
                existingReferenceIsManaged = false
            )
        )
    }

    @Test
    fun `fingerprint skips damaged short cover and accepts later pure hash cover`() = runBlocking {
        val expectedHash = ManagedDownloadCoverAssetStore.sha256("original".toByteArray())
        val legacyHashCover = managedCover.copy(
            name = "$expectedHash.jpg",
            reference = "content://managed/Covers/legacy-hash"
        )
        val visitedReferences = mutableListOf<String>()

        val selected = fingerprintFirstMatchingManagedCover(
            managedEntries = sequenceOf(managedCover, legacyHashCover),
            expectedHash = expectedHash
        ) { entry ->
            visitedReferences += entry.reference
            if (entry == managedCover) {
                ManagedDownloadCoverAssetStore.MaterializedCover(
                    reference = entry.reference,
                    assetHash = ManagedDownloadCoverAssetStore.sha256("overwritten".toByteArray()),
                    fileName = entry.name
                )
            } else {
                ManagedDownloadCoverAssetStore.MaterializedCover(
                    reference = entry.reference,
                    assetHash = expectedHash,
                    fileName = entry.name
                )
            }
        }

        assertEquals(listOf(managedCover.reference, legacyHashCover.reference), visitedReferences)
        assertEquals(legacyHashCover.reference, selected?.reference)
        assertEquals(legacyHashCover.name, selected?.fileName)
    }

    @Test
    fun `fingerprint skips unreadable managed cover and continues`() = runBlocking {
        val expectedHash = ManagedDownloadCoverAssetStore.sha256("original".toByteArray())
        val secondCover = managedCover.copy(
            name = "Artist - Song-87654321.jpg",
            reference = "content://managed/Covers/document-2"
        )

        val selected = fingerprintFirstMatchingManagedCover(
            managedEntries = sequenceOf(managedCover, secondCover),
            expectedHash = expectedHash
        ) { entry ->
            if (entry == managedCover) {
                null
            } else {
                ManagedDownloadCoverAssetStore.MaterializedCover(
                    reference = entry.reference,
                    assetHash = expectedHash,
                    fileName = entry.name
                )
            }
        }

        assertEquals(secondCover.reference, selected?.reference)
    }
}
