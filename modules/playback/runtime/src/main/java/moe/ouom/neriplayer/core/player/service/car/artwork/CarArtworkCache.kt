package moe.ouom.neriplayer.core.player.service.car.artwork

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal const val CAR_ARTWORK_SIZE_PX = 512
internal const val CAR_ARTWORK_MAX_ENTRY_BYTES = 512 * 1024
private const val CACHE_ENTRY_LIMIT = 256
private const val CACHE_BYTE_LIMIT = 32L * 1024 * 1024
private val KEY_PATTERN = Regex("[0-9a-f]{64}")
private val PATH_PATTERN = Regex("/v1/([0-9a-f]{64})")

internal fun carArtworkKey(songKey: String, source: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in listOf("v1", songKey, source.orEmpty())) {
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun carArtworkKeyFromPath(encodedPath: String?): String? =
    encodedPath?.let { PATH_PATTERN.matchEntire(it)?.groupValues?.get(1) }

internal fun carArtworkSize(width: Int, height: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val largest = maxOf(width, height)
    if (largest <= CAR_ARTWORK_SIZE_PX) return width to height
    val scale = CAR_ARTWORK_SIZE_PX.toDouble() / largest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}

internal class CarArtworkDiskCache(
    private val directory: File,
    private val maxEntries: Int = CACHE_ENTRY_LIMIT,
    private val maxBytes: Long = CACHE_BYTE_LIMIT,
    private val maxEntryBytes: Int = CAR_ARTWORK_MAX_ENTRY_BYTES,
) {
    init {
        require(maxEntries > 0 && maxBytes > 0 && maxEntryBytes > 0)
    }

    @Synchronized
    fun find(key: String): File? {
        val file = fileFor(key) ?: return null
        if (!isCacheFile(file) || file.length() !in 1..maxEntryBytes.toLong()) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    @Synchronized
    fun save(key: String, data: ByteArray): File? {
        val target = fileFor(key) ?: return null
        if (data.isEmpty() || data.size > maxEntryBytes || data.size > maxBytes) return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        if (Files.isSymbolicLink(directory.toPath())) return null
        removeInterruptedWrites()
        val temporary = File.createTempFile("pending-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(data) }
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            trim(target)
            return target.takeIf(::isCacheFile)
        } finally {
            temporary.delete()
        }
    }

    private fun fileFor(key: String): File? =
        key.takeIf(KEY_PATTERN::matches)?.let { File(directory, "$it.jpg") }

    private fun isCacheFile(file: File): Boolean =
        !Files.isSymbolicLink(directory.toPath()) && file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == directory.canonicalFile

    private fun removeInterruptedWrites() {
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith("pending-") && it.extension == "tmp" }
            .filter(::isCacheFile)
            .forEach { it.delete() }
    }

    private fun trim(protectedFile: File) {
        val entries = directory.listFiles().orEmpty()
            .filter { KEY_PATTERN.matches(it.name.removeSuffix(".jpg")) && it.extension == "jpg" }
            .filter(::isCacheFile)
            .sortedBy(File::lastModified)
        var count = entries.size
        var bytes = entries.sumOf(File::length)
        for (file in entries) {
            if (count <= maxEntries && bytes <= maxBytes) break
            if (file == protectedFile) continue
            val size = file.length()
            if (file.delete()) {
                count--
                bytes -= size
            }
        }
    }
}

internal class CarArtworkRequests<T>(private val maxEntries: Int = 512) {
    private val requests = LinkedHashMap<String, T>(16, 0.75f, true)

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    fun register(key: String, request: T) {
        requests[key] = request
        while (requests.size > maxEntries) requests.remove(requests.keys.first())
    }

    @Synchronized
    fun get(key: String): T? = requests[key]
}
