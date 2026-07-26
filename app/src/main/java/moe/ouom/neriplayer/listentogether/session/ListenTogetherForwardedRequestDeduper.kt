package moe.ouom.neriplayer.listentogether.session

private const val DEFAULT_MAX_REQUESTERS = 64
private const val DEFAULT_MAX_EVENT_IDS = 256

/**
 * 控制端对转发来的成员控制请求做去重。
 *
 * 旧实现用单个全局序号，存在两个缺陷：
 * 1. requestSequence=0/null 直接绕过去重；
 * 2. 全局计数跨请求者，导致 B 的首个请求被 A 的更大序号误判为过期。
 *
 * 现按 requesterUuid 维护独立的最新序号；无有效序号时退化为按事件 id 去重。
 */
internal class ListenTogetherForwardedRequestDeduper(
    private val maxRequesters: Int = DEFAULT_MAX_REQUESTERS,
    private val maxEventIds: Int = DEFAULT_MAX_EVENT_IDS
) {
    private val lastSequenceByRequester = LinkedHashMap<String, Long>()
    private val seenEventIds = LinkedHashSet<String>()
    private val lock = Any()

    /** 返回 true 表示应处理该请求；false 表示重复/过期应忽略 */
    fun shouldProcess(
        requesterUuid: String?,
        sequence: Long?,
        eventId: String?
    ): Boolean = synchronized(lock) {
        val seq = sequence ?: 0L
        if (seq > 0L) {
            val requester = requesterUuid?.takeIf { it.isNotBlank() } ?: GLOBAL_REQUESTER
            val last = lastSequenceByRequester[requester] ?: 0L
            if (seq <= last) return false
            // remove + put 维持插入顺序为 LRU，便于按容量修剪
            lastSequenceByRequester.remove(requester)
            lastSequenceByRequester[requester] = seq
            trimOldest(lastSequenceByRequester.keys, maxRequesters) { lastSequenceByRequester.remove(it) }
            return true
        }
        // 无有效序号：按事件 id 去重
        val id = eventId?.takeIf { it.isNotBlank() } ?: return true
        if (seenEventIds.contains(id)) return false
        seenEventIds.add(id)
        trimOldest(seenEventIds, maxEventIds) { seenEventIds.remove(it) }
        return true
    }

    fun clear() = synchronized(lock) {
        lastSequenceByRequester.clear()
        seenEventIds.clear()
    }

    private inline fun trimOldest(
        keys: MutableSet<String>,
        maxSize: Int,
        remove: (String) -> Unit
    ) {
        while (keys.size > maxSize) {
            val oldest = keys.firstOrNull() ?: break
            remove(oldest)
        }
    }

    private companion object {
        const val GLOBAL_REQUESTER = "__global__"
    }
}
