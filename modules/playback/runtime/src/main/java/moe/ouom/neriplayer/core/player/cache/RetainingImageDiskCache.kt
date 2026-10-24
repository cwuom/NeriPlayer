@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package moe.ouom.neriplayer.core.player.cache

import coil.disk.DiskCache
import java.io.File
import java.io.IOException
import java.util.Base64

/** 记录公开 API 使用过的键，让清理时可以找到使用版本化键的封面 */
class RetainingImageDiskCache(
    private val delegate: DiskCache,
    registryFile: File
) : DiskCache by delegate {
    private val registry = ImageCacheKeyRegistry(registryFile)

    @Synchronized
    override fun openSnapshot(key: String): DiskCache.Snapshot? =
        delegate.openSnapshot(key)?.also { registry.record(key) }

    @Synchronized
    override fun openEditor(key: String): DiskCache.Editor? =
        delegate.openEditor(key)?.also { registry.record(key) }

    @Deprecated("Renamed to openSnapshot", ReplaceWith("openSnapshot(key)"))
    override fun get(key: String): DiskCache.Snapshot? = openSnapshot(key)

    @Deprecated("Renamed to openEditor", ReplaceWith("openEditor(key)"))
    override fun edit(key: String): DiskCache.Editor? = openEditor(key)

    @Synchronized
    override fun remove(key: String): Boolean {
        val removed = delegate.remove(key)
        if (removed) registry.remove(key)
        return removed
    }

    @Synchronized
    override fun clear() {
        delegate.clear()
        registry.clear()
    }

    internal fun knownKeys(): Set<String> = registry.keys()

    internal fun flushKeys() = registry.flush()
}

private val coverVersionSuffix = Regex("[0-9]+\\|[0-9]+\\|[0-9]+")
private val blurStrengthPrefix = Regex("(?:null|[0-9]+\\.[0-9]+(?:E-?[0-9]+)?):.+")

internal fun imageCacheKeyMatchesCover(key: String, url: String): Boolean {
    if (key == url) return true
    if (key.endsWith("|data=$url") && key.length > "|data=$url".length) return true
    if (key.startsWith("playlist-row-cover|") && key.endsWith("|$url")) return true
    val coverPrefix = "nowplaying-cover|$url|"
    if (key.startsWith(coverPrefix)) {
        val suffix = key.removePrefix(coverPrefix).removeSuffix("|data=$url")
        return coverVersionSuffix.matches(suffix)
    }
    val blurPrefix = "nowplaying-blur:$url:"
    return key.startsWith(blurPrefix) && blurStrengthPrefix.matches(key.removePrefix(blurPrefix))
}

private class ImageCacheKeyRegistry(private val file: File) {
    private val knownKeys = linkedSetOf<String>()
    private var knownCharacters = 0
    private var appendedLines = 0
    private var dirty = false

    init {
        try {
            if (file.isFile && file.length() <= MAX_REGISTRY_BYTES) {
                file.useLines { lines ->
                    lines.take(MAX_LOG_LINES).forEach { line ->
                        appendedLines++
                        try {
                            remember(String(Base64.getDecoder().decode(line), Charsets.UTF_8))
                        } catch (_: IllegalArgumentException) {
                            // 单行损坏不影响 Coil 本身读取已有缓存
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 注册表是兼容索引，读取失败时仍允许 Coil 正常工作
        } catch (_: SecurityException) {
            // 缓存目录不可读时，保持图片加载原有的错误处理
        }
    }

    @Synchronized
    fun keys(): Set<String> = knownKeys.toSet()

    @Synchronized
    fun record(key: String) {
        if (!remember(key)) return
        val encoded = encode(key) + "\n"
        val fileSize = try {
            file.length()
        } catch (_: SecurityException) {
            dirty = true
            return
        }
        if (appendedLines >= MAX_LOG_LINES || fileSize + encoded.length > MAX_REGISTRY_BYTES) {
            dirty = true
            flush()
        } else {
            if (!updateFile {
                    file.parentFile?.mkdirs()
                    file.appendText(encoded)
                }) dirty = true
            appendedLines++
        }
    }

    @Synchronized
    fun remove(key: String) {
        if (knownKeys.remove(key)) {
            knownCharacters -= key.length
            dirty = true
        }
    }

    @Synchronized
    fun clear() {
        knownKeys.clear()
        knownCharacters = 0
        dirty = true
        flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        if (updateFile {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.tmp")
                try {
                    temporary.bufferedWriter().use { writer ->
                        knownKeys.forEach { key -> writer.append(encode(key)).append('\n') }
                    }
                    if (!temporary.renameTo(file)) throw IOException("Cannot update image cache key registry")
                } finally {
                    temporary.delete()
                }
            }) {
            dirty = false
            appendedLines = knownKeys.size
        }
    }

    private fun remember(key: String): Boolean {
        if (key.length > MAX_KEY_CHARACTERS || !knownKeys.add(key)) return false
        knownCharacters += key.length
        while (knownKeys.size > MAX_KEYS || knownCharacters > MAX_KNOWN_CHARACTERS) {
            val iterator = knownKeys.iterator()
            knownCharacters -= iterator.next().length
            iterator.remove()
            dirty = true
        }
        return true
    }

    private fun encode(key: String): String = Base64.getEncoder().encodeToString(key.toByteArray(Charsets.UTF_8))

    private inline fun updateFile(update: () -> Unit): Boolean = try {
        update()
        true
    } catch (_: IOException) {
        // 索引写入失败后，未追踪的条目会在保留清理时保守留下
        false
    } catch (_: SecurityException) {
        // 注册表不改变 Coil 在只读缓存目录上的行为
        false
    }

    private companion object {
        const val MAX_KEYS = 8192
        const val MAX_KEY_CHARACTERS = 4096
        const val MAX_KNOWN_CHARACTERS = 512 * 1024
        const val MAX_LOG_LINES = MAX_KEYS * 2
        const val MAX_REGISTRY_BYTES = 4L * 1024 * 1024
    }
}
