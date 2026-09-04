package moe.ouom.neriplayer.core.download.execution

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadClearFenceStoreContractTest {

    @Test
    fun `begin clear reuses a pending in process epoch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "beginClear")

        assertTrue(body.contains("currentEpoch > clearedRequestEpoch.get()"))
        assertTrue(body.contains("return@synchronized currentEpoch"))
        assertTrue(body.contains("requestedPurpose = purpose"))
    }

    @Test
    fun `unpersisted epoch is abandoned only after conservative checks`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "abandonUnpersistedRequestIfCurrent")

        assertTrue(body.contains("clearRequestEpoch.get() != expectedEpoch"))
        assertTrue(body.contains("clearedRequestEpoch.get() >= expectedEpoch"))
        assertTrue(body.contains("isPersistedFenceActive(context)"))
        assertTrue(
            body.contains("PersistentDownloadedSongDeleteIntentStore.hasPending(context)")
        )
        assertTrue(body.contains("clearedRequestEpoch.accumulateAndGet"))
    }

    @Test
    fun `persisted fence probe is separate from delete intent`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "hasPersistedFence")
        assertTrue(body.contains("isPersistedFenceActive(context)"))
        assertTrue(
            body.indexOf("isPersistedFenceActive(context)") >= 0
        )
    }

    @Test
    fun `capture blocks known owners but permits unrelated keys`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val blockedBody = methodBody(source, "isBlocked")
        val identityCheck = blockedBody.indexOf(
            "val normalizedStableKey = stableKey.normalizeIdentity()"
        )
        val captureGuard = blockedBody.indexOf(
            "if (!isOwnershipCaptureCompleteLocked(context))"
        )
        assertTrue(identityCheck >= 0)
        assertTrue(captureGuard > identityCheck)
        val captureBody = blockedBody.substring(captureGuard)
        assertTrue(captureBody.contains("ownership.operationIds"))
        assertTrue(captureBody.contains("ownership.stableKeys"))
        assertTrue(captureBody.contains("return@synchronized ("))
        val activateBody = methodBody(source, "activate", occurrence = 2)
        assertTrue(
            activateBody.contains("putBoolean(OWNER_CAPTURE_COMPLETE_KEY, false)")
        )
        val ownershipBody = methodBody(source, "setOwnership")
        assertTrue(ownershipBody.contains("putBoolean(OWNER_CAPTURE_COMPLETE_KEY, true)"))
    }

    @Test
    fun `schedule tickets use a monotonic clear epoch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        assertTrue(source.contains("internal fun currentEpoch("))
        assertTrue(source.contains("clearRequestEpoch.get()"))
    }

    @Test
    fun `failed owner persistence cannot change capture state`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "setOwnership")
        val commitIndex = body.indexOf("commitFenceEdit(preferences)")
        val successMutationIndex = body.indexOf("if (persisted)")
        assertTrue(commitIndex >= 0)
        assertTrue(successMutationIndex > commitIndex)
        assertTrue(
            body.substring(commitIndex, successMutationIndex)
                .contains("putBoolean(OWNER_CAPTURE_COMPLETE_KEY, true)")
        )
        assertTrue(
            body.contains("val persistedOwnership = readOwnershipLocked(context)")
        )
        assertTrue(
            body.contains("if (hasOperationOwners != hasStableKeyOwners)")
        )
        assertTrue(
            body.contains("if (hasOperationOwners && persistedOwnership == null)")
        )
        assertTrue(
            body.contains("val effectiveOwnership = DownloadClearOwnership(")
        )
        assertTrue(body.contains("effectiveOwnership.operationIds"))
        assertTrue(body.contains("effectiveOwnership.stableKeys"))
        assertTrue(body.substring(successMutationIndex).contains("requestedOwnership"))
    }

    @Test
    fun `active fence retry preserves persisted timestamp and owners`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "activate", occurrence = 2)
        val activeBranch = body.substringAfter("if (persistedFenceWasActive)")
            .substringBefore("val effectivePurpose = requestedPurpose")
        assertTrue(activeBranch.contains("return@synchronized true"))
        assertTrue(!activeBranch.contains("putLong(REQUESTED_AT_MS_KEY"))
        assertTrue(!activeBranch.contains("putStringSet("))
        assertTrue(
            activeBranch.contains("requestedPurpose == DownloadClearPurpose.FULL_LIBRARY_DELETE")
        )
        assertTrue(activeBranch.contains("putString(\n                                PURPOSE_KEY"))
    }

    @Test
    fun `task fence cannot be released before owner capture completes`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "clearIfCurrent")
        val epochCheck = body.indexOf("clearRequestEpoch.get() != expectedEpoch")
        val captureGuard = body.indexOf(
            "!isOwnershipCaptureCompleteLocked(context)"
        )
        val removeFence = body.indexOf("remove(ACTIVE_KEY)")
        assertTrue(epochCheck >= 0)
        assertTrue(captureGuard > epochCheck)
        assertTrue(removeFence > captureGuard)
        assertTrue(
            body.substring(captureGuard, removeFence)
                .contains("DownloadClearFenceReleaseResult.FAILED")
        )
    }

    @Test
    fun `expired task fence has an explicit non blocking release path`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/execution/" +
                "DownloadClearFenceStore.kt"
        ).readText()
        val body = methodBody(source, "forceReleaseIfExpired")
        assertTrue(body.contains("hasDownloadClearExceededDeadline"))
        assertTrue(
            body.contains(
                "activePurposeLocked(context) != DownloadClearPurpose.TASK_PROGRESS"
            )
        )
        assertTrue(body.contains("remove(ACTIVE_KEY)"))
        assertTrue(body.contains("clearedRequestEpoch.accumulateAndGet"))
    }

    private fun methodBody(
        source: String,
        methodName: String,
        occurrence: Int = 0
    ): String {
        val signatureStart = Regex(
            "(?:private|internal|public)?\\s*(?:suspend\\s+)?fun\\s+$methodName\\b"
        ).findAll(source).elementAtOrNull(occurrence)?.range?.first
            ?: error("method not found: $methodName")
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $methodName")
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
