package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStoragePendingAudioPromotionRecoveryContractTest {
    @Test
    fun `metadata staging verifies the resolved audio name without consuming pending metadata`() {
        val helper = readStorageSource()
            .substringAfter("private fun promotePendingCoreMetadata")
            .substringBefore("private fun cleanupPendingCoreMetadataAfterAudioPromotion")
        val rewriteIndex = helper.indexOf("rewritePendingMetadataAudioFileName")
        val finalMetadataNameIndex = helper.indexOf("val finalMetadataName")
        val writeIndex = helper.indexOf("writeRootText(")
        val verificationIndex = helper.indexOf("readTextInternal(context, written.reference)")

        assertTrue(rewriteIndex >= 0)
        assertTrue(finalMetadataNameIndex >= 0)
        assertTrue(
            helper.substring(finalMetadataNameIndex)
                .contains("finalAudioName")
        )
        assertTrue(writeIndex > rewriteIndex)
        assertTrue(
            helper.substring(rewriteIndex, writeIndex)
                .contains("finalAudioName")
        )
        assertTrue(verificationIndex > writeIndex)
        assertTrue(
            helper.substring(verificationIndex)
                .contains("audioFileName")
        )
        assertTrue(
            helper.substring(verificationIndex)
                .contains("finalAudioName")
        )
        assertFalse(helper.contains("deleteReferencesInternal("))
    }

    @Test
    fun `core promotion stages recoverable metadata before mutating pending audio`() {
        val source = readStorageSource()
        val promotion = source
            .substringAfter("internal suspend fun promoteCoreCommittedPendingAudio")
            .substringBefore("private fun promotePendingCoreMetadata")
        val plan = source
            .substringAfter("private suspend fun resolvePendingCorePromotionFinalName")
            .substringBefore("private suspend fun pendingAudioPromotionExpectedSizeForPlanning")
        val stagedPlanIndex = plan.indexOf("resolveStagedPendingPromotionFinalName")
        val allocationIndex = plan.indexOf("resolvePendingAudioPromotionFinalName")
        val metadataStagingIndex = promotion.indexOf("promotePendingCoreMetadata")
        val audioPromotionIndex = promotion.indexOf("promotePendingAudio(")

        assertTrue(stagedPlanIndex >= 0)
        assertTrue(allocationIndex > stagedPlanIndex)
        assertTrue(metadataStagingIndex >= 0)
        assertTrue(audioPromotionIndex > metadataStagingIndex)
        assertTrue(
            promotion.substring(metadataStagingIndex, audioPromotionIndex)
                .contains("finalAudioName")
        )
        assertTrue(promotion.contains("resolvePendingCorePromotionFinalName"))
        assertTrue(
            promotion.substring(audioPromotionIndex)
                .contains("finalAudioName")
        )
        assertFalse(
            promotion.substring(0, audioPromotionIndex)
                .contains("deletePendingAudioMetadata")
        )
    }

    @Test
    fun `core promotion resolves staged names only through the pending artifact identity`() {
        val source = readStorageSource()
        val resolver = source
            .substringAfter("internal fun resolveStagedPendingPromotionFinalName")
            .substringBefore("internal fun resolvePendingAudioPromotionFinalName")
        val identity = source
            .substringAfter("private fun matchesPendingPromotionIdentity")
            .substringBefore("private fun isPendingAudioPromotionFinalNameCandidate")

        assertTrue(resolver.contains("matchesPendingPromotionIdentity"))
        assertTrue(resolver.contains("stagedMetadata.audioFileName"))
        assertTrue(identity.contains("expectedStableKey"))
        assertTrue(identity.contains("expectedOperationId"))
        assertTrue(identity.contains("stagedMetadata.stableKey"))
        assertTrue(identity.contains("stagedMetadata.operationId"))
    }

    @Test
    fun `pending metadata cleanup waits until its paired audio is no longer pending`() {
        val source = readStorageSource()
        val cleanup = source
            .substringAfter("private suspend fun cleanupPendingCoreMetadataAfterAudioPromotion")
            .substringBefore("private suspend fun isPendingAudioPromotionSourceReleased")
        val releaseCheckIndex = cleanup.indexOf("isPendingAudioPromotionSourceReleased")
        val deleteIndex = cleanup.indexOf("deleteReferencesInternal(")

        assertTrue(releaseCheckIndex >= 0)
        assertTrue(deleteIndex > releaseCheckIndex)
    }

    @Test
    fun `promotion reuses an exact target before resolving a pending source`() {
        val source = readStorageSource()
        val promotion = source
            .substringAfter("private suspend fun promotePendingAudio")
            .substringBefore("internal fun resolvePendingTreeAudioPromotionExpectedSize")
        val targetIndex = promotion.indexOf("val exactTargetCandidates")
        val pendingIndex = promotion.indexOf("val pending = when", targetIndex)

        assertTrue(targetIndex >= 0)
        assertTrue(pendingIndex > targetIndex)
        assertTrue(
            promotion.substring(targetIndex, pendingIndex)
                .contains("reconcileExistingTreePromotionTargetLocked")
        )
        assertTrue(
            promotion.substring(targetIndex, pendingIndex)
                .contains("StorageLookupResult.Missing -> audio.sizeBytes")
        )
    }

    @Test
    fun `existing target recovery remains conservative and cleans pending after verification`() {
        val helper = readStorageSource()
            .substringAfter("private fun reconcileExistingTreePromotionTargetLocked")
            .substringBefore("private suspend fun copyPendingTreeAudioWithoutReplacing")
        val verificationIndex = helper.indexOf("verifiedTreeStoredEntry(")
        val deleteIndex = helper.indexOf("deleteTrustedReference(")

        assertTrue(helper.contains("if (!refresh.isComplete)"))
        assertTrue(helper.contains("if (exactTargets.size != 1)"))
        assertTrue(helper.contains("exactTarget.isDirectory"))
        assertTrue(helper.contains("isTreePromotionBackupName"))
        assertTrue(helper.contains("exactTarget.sizeBytes != expectedSizeBytes"))
        assertTrue(verificationIndex >= 0)
        assertTrue(deleteIndex > verificationIndex)
    }

    @Test
    fun `copy path rechecks an existing target while holding the tree mutation lock`() {
        val helper = readStorageSource()
            .substringAfter("private suspend fun copyPendingTreeAudioWithoutReplacing")
            .substringBefore("private fun discardNewTreePromotionTarget")
        val recoveryIndex = helper.indexOf("val recovered = beforeCreate.children")
        val createIndex = helper.indexOf("val createdUri =", recoveryIndex)

        assertTrue(recoveryIndex >= 0)
        assertTrue(createIndex > recoveryIndex)
        assertTrue(
            helper.substring(recoveryIndex, createIndex)
                .contains("reconcileExistingTreePromotionTargetLocked")
        )
    }

    private fun readStorageSource(): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(
                directory,
                "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
            )
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: ManagedDownloadStorage.kt")
    }
}
