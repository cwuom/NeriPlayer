package moe.ouom.neriplayer.core.download

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStoragePendingAudioPromotionRecoveryContractTest {
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
