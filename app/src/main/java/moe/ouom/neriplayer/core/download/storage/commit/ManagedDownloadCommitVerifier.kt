package moe.ouom.neriplayer.core.download.storage.commit

import kotlin.math.abs

internal object ManagedDownloadCommitVerifier {
    fun isSizeWithinTolerance(
        actualSizeBytes: Long,
        expectedSizeBytes: Long,
        toleranceBytes: Long
    ): Boolean {
        return abs(actualSizeBytes - expectedSizeBytes) <= toleranceBytes.coerceAtLeast(0L)
    }

    fun verifiedCommittedByteCount(
        expectedSizeBytes: Long,
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?,
        toleranceBytes: Long = 0L
    ): Long? {
        val expectedSize = expectedSizeBytes.coerceAtLeast(0L)
        val tolerance = toleranceBytes.coerceAtLeast(0L)
        val reportedSize = reportedSizeBytes?.takeIf { it >= 0L }
        if (reportedSize != null) {
            if (isSizeWithinTolerance(reportedSize, expectedSize, tolerance)) {
                return reportedSize
            }
        }
        return countedSizeBytes
            ?.takeIf { it >= 0L }
            ?.takeIf { isSizeWithinTolerance(it, expectedSize, tolerance) }
    }
}
