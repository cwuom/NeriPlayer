package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.operation.content.copyPendingTreeAudioWithoutReplacing
import moe.ouom.neriplayer.core.download.storage.operation.content.deleteReferencesInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.deleteTrustedReference
import moe.ouom.neriplayer.core.download.storage.operation.content.isTreePromotionBackupName
import moe.ouom.neriplayer.core.download.storage.operation.content.promotePendingAudio
import moe.ouom.neriplayer.core.download.storage.operation.content.readTextInternal
import moe.ouom.neriplayer.core.download.storage.operation.content.reconcileExistingTreePromotionTargetLocked
import moe.ouom.neriplayer.core.download.storage.operation.content.verifiedTreeStoredEntry
import moe.ouom.neriplayer.core.download.storage.operation.content.writeRootText
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.cleanupPendingCoreMetadataAfterAudioPromotion
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.isPendingAudioPromotionSourceReleased
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.matchesPendingPromotionIdentity
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.promotePendingCoreMetadata
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.resolvePendingCorePromotionFinalName
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStoragePendingAudioPromotionRecoveryContractTest {
    @Test
    fun `metadata staging verifies the resolved audio name without consuming pending metadata`() {
        val helper = methodBody(readStorageSource(), "promotePendingCoreMetadata")
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
        val promotion = methodBody(source, "promoteCoreCommittedPendingAudio")
        val plan = methodBody(source, "resolvePendingCorePromotionFinalName")
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
        val resolver = methodBody(source, "resolveStagedPendingPromotionFinalName")
        val identity = methodBody(source, "matchesPendingPromotionIdentity")

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
        val releaseCheck = methodBody(source, "cleanupPendingCoreMetadataAfterAudioPromotion")
        val cleanup = source.substringAfter(
            "internal fun ManagedDownloadStorage.cleanupPendingCoreMetadataAfterAudioPromotion("
        ).substringBefore(
            "internal suspend fun ManagedDownloadStorage.isPendingAudioPromotionSourceReleased("
        )

        assertTrue(releaseCheck.contains("isPendingAudioPromotionSourceReleased"))
        assertTrue(releaseCheck.contains("sourceReleased = sourceReleased"))
        assertTrue(cleanup.contains("if (!sourceReleased)"))
        assertTrue(cleanup.contains("deleteReferencesInternal("))
    }

    @Test
    fun `promotion reuses an exact target before resolving a pending source`() {
        val source = readStorageSource()
        val promotion = methodBody(source, "promotePendingAudio")
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
        val helper = methodBody(
            readStorageSource(),
            "reconcileExistingTreePromotionTargetLocked"
        )
        val receiptVerificationIndex = helper.indexOf("isVerifiedAudioPublicationTarget(")
        val ownershipGuardIndex = helper.indexOf("if (!sameDocument && !verifiedPublication &&")
        val copyRecoveryIndex = helper.indexOf("resumeAudioPublicationCopy(")
        val targetResolutionIndex = helper.indexOf("val target = resolveNewTreePromotionDocument(")
        val verificationIndex = helper.indexOf("verifiedTreeStoredEntry(")
        val deleteIndex = helper.indexOf("deleteTrustedReference(")

        assertTrue(helper.contains("if (!refresh.isComplete)"))
        assertTrue(helper.contains("if (exactTargets.size != 1)"))
        assertTrue(helper.contains("exactTarget.isDirectory"))
        assertTrue(helper.contains("isTreePromotionBackupName"))
        assertTrue(receiptVerificationIndex >= 0)
        assertTrue(ownershipGuardIndex > receiptVerificationIndex)
        assertTrue(copyRecoveryIndex > ownershipGuardIndex)
        assertTrue(targetResolutionIndex > copyRecoveryIndex)
        assertTrue(helper.substring(ownershipGuardIndex, targetResolutionIndex).contains("return null"))
        assertTrue(helper.contains("pendingUri == null && verifiedPublication"))
        assertTrue(helper.contains("expectedSizeBytes = verifiedSizeBytes"))
        assertTrue(verificationIndex > targetResolutionIndex)
        assertTrue(deleteIndex > verificationIndex)
    }

    @Test
    fun `copy path rechecks an existing target while holding the tree mutation lock`() {
        val helper = methodBody(readStorageSource(), "copyPendingTreeAudioWithoutReplacing")
        val recoveryIndex = helper.indexOf("val recovered = beforeCreate.children")
        val createIndex = helper.indexOf("val createdUri =", recoveryIndex)

        assertTrue(recoveryIndex >= 0)
        assertTrue(createIndex > recoveryIndex)
        assertTrue(
            helper.substring(recoveryIndex, createIndex)
                .contains("reconcileExistingTreePromotionTargetLocked")
        )
    }

    @Test
    fun `copy path measures current pending size and checks copied bytes before cleanup`() {
        val source = readStorageSource()
        val promotion = methodBody(source, "promotePendingAudio")
        val sizeResolver = methodBody(source, "resolveCurrentTreePendingAudioSize")
        val copy = methodBody(source, "copyPendingTreeAudioWithoutReplacing")
        val currentSizeIndex = promotion.indexOf("val expectedSizeBytes = resolveCurrentTreePendingAudioSize(")
        val copyInvocationIndex = promotion.indexOf("copyPendingTreeAudioWithoutReplacing(")
        val copyIndex = copy.indexOf("val copiedBytes = source.copyTo")
        val byteCountCheckIndex = copy.indexOf("copiedBytes != expectedSizeBytes")
        val verificationIndex = copy.indexOf("val entry = verifiedTreeStoredEntry(")
        val receiptVerificationIndex = copy.indexOf("if (!isVerifiedAudioPublicationTarget(")
        val deleteIndex = copy.indexOf("deleteTrustedReference(")

        assertTrue(currentSizeIndex >= 0)
        assertTrue(copyInvocationIndex > currentSizeIndex)
        assertTrue(sizeResolver.contains("countInputStreamBytes("))
        assertTrue(sizeResolver.contains("countedSizeBytes = countedSizeBytes"))
        assertTrue(copyIndex >= 0)
        assertTrue(byteCountCheckIndex > copyIndex)
        assertTrue(verificationIndex > byteCountCheckIndex)
        assertTrue(receiptVerificationIndex > verificationIndex)
        assertTrue(deleteIndex > receiptVerificationIndex)
    }

    private fun readStorageSource(): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(
                directory,
                "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
            )
            if (candidate.isFile) return moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.resolve(candidate).readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: ManagedDownloadStorage.kt")
    }

    private fun methodBody(source: String, methodName: String): String =
        moe.ouom.neriplayer.architecture.RefactoredSourceFamilyResolver.functionBody(
            source,
            methodName
        )
}
