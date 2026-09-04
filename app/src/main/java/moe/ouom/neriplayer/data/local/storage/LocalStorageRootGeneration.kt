package moe.ouom.neriplayer.data.local.storage

import java.util.concurrent.atomic.AtomicLong

/**
 * 记录当前本地管理根的身份变化，让 SAF 缓存不会跨授权树复用
 */
object LocalStorageRootGeneration {
    private val generation = AtomicLong(0L)

    @Volatile
    private var identity: String? = null

    fun current(): Long = generation.get()

    fun currentIdentity(): String? = identity

    @Synchronized
    fun update(newIdentity: String?): Long {
        val normalized = newIdentity
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (identity == normalized) {
            return generation.get()
        }
        identity = normalized
        return generation.incrementAndGet()
    }

    @Synchronized
    internal fun resetForTest() {
        identity = null
        generation.set(0L)
    }
}
