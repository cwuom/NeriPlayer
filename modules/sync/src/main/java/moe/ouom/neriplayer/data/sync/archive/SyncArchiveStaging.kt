package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.util.UUID

internal object SyncArchiveStaging {
    fun createDirectory(parent: File?, prefix: String): File {
        if (parent == null) {
            val seed = File.createTempFile(prefix, ".tmp")
            try {
                return createDirectory(seed.parentFile, prefix)
            } finally {
                seed.delete()
            }
        }
        require(parent.isDirectory) { "Sync staging parent is unavailable" }
        val directory = File(parent, prefix + UUID.randomUUID())
        check(directory.mkdir()) { "Unable to create sync staging directory" }
        return directory
    }
}
