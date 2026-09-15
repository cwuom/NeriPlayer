package moe.ouom.neriplayer.core.download

internal object ManagedDownloadSizePolicy {
    const val MAX_TRANSFER_SIZE_TOLERANCE_BYTES = 64L * 1024L

    private const val RELATIVE_TOLERANCE_DENOMINATOR = 1_000L
    private const val MAX_TOLERANCE_FRACTION_DENOMINATOR = 4L

    fun isTransferSizeComplete(
        expectedSizeBytes: Long?,
        actualSizeBytes: Long
    ): Boolean {
        if (actualSizeBytes <= 0L) {
            return false
        }
        val expectedSize = expectedSizeBytes?.takeIf { it > 0L } ?: return true
        return isSizeWithinTolerance(
            actualSizeBytes = actualSizeBytes,
            expectedSizeBytes = expectedSize,
            toleranceBytes = toleranceBytesFor(expectedSize)
        )
    }

    fun toleranceBytesFor(expectedSizeBytes: Long): Long {
        if (expectedSizeBytes <= 0L) {
            return 0L
        }
        val relativeTolerance = expectedSizeBytes / RELATIVE_TOLERANCE_DENOMINATOR
        val minimumTolerance = if (expectedSizeBytes >= 4L) 1L else 0L
        val maximumTolerance = expectedSizeBytes / MAX_TOLERANCE_FRACTION_DENOMINATOR
        return minOf(
            MAX_TRANSFER_SIZE_TOLERANCE_BYTES,
            maxOf(relativeTolerance, minimumTolerance),
            maximumTolerance
        )
    }

    private fun isSizeWithinTolerance(
        actualSizeBytes: Long,
        expectedSizeBytes: Long,
        toleranceBytes: Long
    ): Boolean {
        if (actualSizeBytes < 0L || expectedSizeBytes < 0L) {
            return false
        }
        val tolerance = toleranceBytes.coerceAtLeast(0L)
        val lowerBound = (expectedSizeBytes - tolerance).coerceAtLeast(0L)
        val upperBound = if (Long.MAX_VALUE - expectedSizeBytes < tolerance) {
            Long.MAX_VALUE
        } else {
            expectedSizeBytes + tolerance
        }
        return actualSizeBytes in lowerBound..upperBound
    }
}
