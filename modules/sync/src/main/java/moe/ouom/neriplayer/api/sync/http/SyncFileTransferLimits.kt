package moe.ouom.neriplayer.api.sync.http

internal object SyncFileTransferLimits {
    const val ARCHIVE_FILE_BYTES = 2 * 1024 * 1024
    private val archiveObject = Regex("neriplayer-sync-v[34]-[0-9a-f]{64}\\.zst")

    fun responseBudget(path: String): Int {
        val name = path.substringAfterLast('/')
        val archive = name == "neriplayer-sync-v3.manifest" || archiveObject.matches(name)
        return if (archive) ARCHIVE_FILE_BYTES else SyncResponseBodyReader.MAX_SYNC_FILE_BYTES
    }
}
