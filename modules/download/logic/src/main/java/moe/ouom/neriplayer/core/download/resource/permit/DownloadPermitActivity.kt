package moe.ouom.neriplayer.core.download.resource.permit

internal class DownloadPermitActivity(
    val permit: DownloadTransferPermitRegistry.Permit,
    val usesOverflow: Boolean
) {
    var networkIoActive = false
        private set
    var lastReportedBytes = 0L
        private set
    private var hasProgress = false
    private var lastProgressNs = 0L
    private var lastActivityNs = 0L

    fun startNetworkIo(nowNs: () -> Long): Long? {
        if (networkIoActive) return null
        networkIoActive = true
        val now = nowNs()
        lastProgressNs = now
        lastActivityNs = now
        return now
    }

    fun finishNetworkIo(): Boolean {
        if (!networkIoActive) return false
        networkIoActive = false
        return true
    }

    fun markNetworkActivity(nowNs: () -> Long): Boolean {
        if (!networkIoActive) return false
        lastActivityNs = nowNs()
        return true
    }

    fun recordProgress(absoluteBytes: Long, nowNs: () -> Long): Boolean {
        if (absoluteBytes < lastReportedBytes) return false
        val changed = absoluteBytes > lastReportedBytes
        lastReportedBytes = absoluteBytes
        if (changed) {
            hasProgress = true
            val now = nowNs()
            lastProgressNs = now
            lastActivityNs = now
        }
        return changed
    }

    fun isStale(staleAfterNs: Long, atNs: Long): Boolean =
        networkIoActive && (atNs - lastActivityNs).coerceAtLeast(0L) >= staleAfterNs

    fun isProgressing(atNs: Long, graceNs: Long): Boolean =
        networkIoActive && hasProgress && (atNs - lastProgressNs).coerceAtLeast(0L) <= graceNs
}
