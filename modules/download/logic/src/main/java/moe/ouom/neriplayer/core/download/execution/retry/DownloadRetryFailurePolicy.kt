package moe.ouom.neriplayer.core.download.execution.retry

private const val SOURCE_FAILURE_LIMIT = 3
private const val GENERAL_FAILURE_LIMIT = 6
private const val TRANSIENT_FAILURE_LIMIT = 8

private val boundedFailureLimits = mapOf(
    "DOWNLOAD_SOURCE_MISSING" to SOURCE_FAILURE_LIMIT,
    "DOWNLOAD_FAILED" to GENERAL_FAILURE_LIMIT,
    "DOWNLOAD_NO_PROGRESS" to GENERAL_FAILURE_LIMIT,
    "DOWNLOAD_STORAGE_UNAVAILABLE" to GENERAL_FAILURE_LIMIT,
    "DOWNLOAD_TRANSIENT_FAILURE" to TRANSIENT_FAILURE_LIMIT
)

fun isAutomaticDownloadRetryExhausted(errorCode: String?, failureCount: Int): Boolean {
    val limit = retryFailureLimit(errorCode) ?: return false
    return failureCount >= limit
}

private fun retryFailureLimit(errorCode: String?): Int? {
    if (errorCode == null) return null
    return when {
        isIntegrityFailure(errorCode) -> DOWNLOAD_INTEGRITY_MAX_FAILURES
        errorCode.startsWith("DOWNLOAD_HOST_FAILURE:") -> GENERAL_FAILURE_LIMIT
        else -> boundedFailureLimits[errorCode]
    }
}

private fun isIntegrityFailure(errorCode: String): Boolean =
    errorCode.startsWith("DOWNLOAD_INTEGRITY_") || errorCode.startsWith("CORE_AUDIO_")
