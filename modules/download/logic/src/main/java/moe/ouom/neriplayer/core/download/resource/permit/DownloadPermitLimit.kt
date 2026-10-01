package moe.ouom.neriplayer.core.download.resource.permit

internal class DownloadPermitLimit(private val maximum: Int) {
    var requested = maximum
        private set
    var effective = maximum
        private set
    var reason = "initial"
        private set
    private var revision = Long.MIN_VALUE

    fun update(requested: Int, reason: String, revision: Long?): Boolean {
        if (isOutdated(revision)) return false
        val normalizedReason = reason.trim().ifBlank { "unspecified" }
        val normalizedLimit = requested.coerceIn(1, maximum)
        val changed = hasChanged(requested, normalizedLimit, normalizedReason)
        this.requested = requested
        effective = normalizedLimit
        this.reason = normalizedReason
        if (revision != null) this.revision = revision
        return changed
    }

    private fun isOutdated(revision: Long?): Boolean = revision != null && revision < this.revision

    private fun hasChanged(requested: Int, effective: Int, reason: String): Boolean =
        this.requested != requested || this.effective != effective || this.reason != reason
}
