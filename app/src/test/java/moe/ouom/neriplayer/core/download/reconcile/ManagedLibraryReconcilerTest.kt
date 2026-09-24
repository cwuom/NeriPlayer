package moe.ouom.neriplayer.core.download.reconcile

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedLibraryReconcilerTest {
    @Test
    fun `first complete empty scan waits and independent empty scan clears`() {
        val reconciler = ManagedLibraryReconciler()
        val first = EmptyScanObservation(
            rootKey = "root",
            confidence = ScanConfidence.COMPLETE,
            isUncached = false,
            knownReferenceCount = 2,
            missingReferenceCount = 2,
            scanId = 1L
        )
        val second = first.copy(isUncached = true, scanId = 2L)

        assertEquals(
            EmptyScanDecision.WAIT_FOR_CONFIRMATION,
            reconciler.observeEmpty(first, existingCount = 2)
        )
        assertEquals(
            EmptyScanDecision.CLEAR_CONFIRMED,
            reconciler.observeEmpty(second, existingCount = 2)
        )
    }

    @Test
    fun `provider failures and partially missing references never clear`() {
        val reconciler = ManagedLibraryReconciler()
        val providerFailure = EmptyScanObservation(
            rootKey = "root",
            confidence = ScanConfidence.PROVIDER_ERROR,
            isUncached = true,
            knownReferenceCount = 3,
            missingReferenceCount = 3,
            scanId = 1L
        )
        val partialMissing = providerFailure.copy(
            confidence = ScanConfidence.COMPLETE,
            missingReferenceCount = 2,
            scanId = 2L
        )

        assertEquals(
            EmptyScanDecision.PRESERVE,
            reconciler.observeEmpty(providerFailure, existingCount = 3)
        )
        assertEquals(
            EmptyScanDecision.PRESERVE,
            reconciler.observeEmpty(partialMissing, existingCount = 3)
        )
    }

    @Test
    fun `same scan cannot confirm empty and missing references must be sampled`() {
        val reconciler = ManagedLibraryReconciler()
        val observation = EmptyScanObservation(
            rootKey = "root",
            confidence = ScanConfidence.COMPLETE,
            isUncached = true,
            knownReferenceCount = 0,
            missingReferenceCount = 0,
            scanId = 7L
        )

        assertEquals(
            EmptyScanDecision.PRESERVE,
            reconciler.observeEmpty(observation, existingCount = 1)
        )
        assertEquals(
            EmptyScanDecision.PRESERVE,
            reconciler.observeEmpty(
                observation.copy(
                    knownReferenceCount = 1,
                    missingReferenceCount = 1
                ),
                existingCount = 1
            )
        )
        assertEquals(
            EmptyScanDecision.WAIT_FOR_CONFIRMATION,
            reconciler.observeEmpty(
                observation.copy(
                    knownReferenceCount = 1,
                    missingReferenceCount = 1,
                    scanId = 8L
                ),
                existingCount = 1
            )
        )
        assertEquals(
            EmptyScanDecision.CLEAR_CONFIRMED,
            reconciler.observeEmpty(
                observation.copy(
                    knownReferenceCount = 1,
                    missingReferenceCount = 1,
                    scanId = 9L
                ),
                existingCount = 1
            )
        )
    }

    @Test
    fun `changing root never clears old root on first observation`() {
        val reconciler = ManagedLibraryReconciler()
        val first = EmptyScanObservation(
            rootKey = "old-root",
            confidence = ScanConfidence.COMPLETE,
            isUncached = false,
            knownReferenceCount = 1,
            missingReferenceCount = 1,
            scanId = 1L
        )
        val changedRoot = first.copy(rootKey = "new-root", isUncached = true, scanId = 2L)

        assertEquals(
            EmptyScanDecision.WAIT_FOR_CONFIRMATION,
            reconciler.observeEmpty(first, existingCount = 1)
        )
        assertEquals(
            EmptyScanDecision.WAIT_FOR_CONFIRMATION,
            reconciler.observeEmpty(changedRoot, existingCount = 1)
        )
    }
}
