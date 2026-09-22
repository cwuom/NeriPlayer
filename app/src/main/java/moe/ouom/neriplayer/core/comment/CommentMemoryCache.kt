package moe.ouom.neriplayer.core.comment

import moe.ouom.neriplayer.core.comment.model.CommentPage

/**
 * 评论分页的内存缓存 (MVP 级别, 不引入数据库)。
 *
 * - 键为 `平台:资源id:页码`
 * - 默认 TTL 5 分钟
 * - LRU 上限约束内存占用
 */
internal object CommentMemoryCache {

    private const val DEFAULT_TTL_MS = 5 * 60 * 1000L
    private const val MAX_ENTRIES = 48

    private data class Entry(val page: CommentPage, val savedAtMs: Long)

    private val lock = Any()

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        /**
         * LRU 淘汰判定: 条目数超过 [MAX_ENTRIES] 时移除最久未访问的一条。
         */
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    /**
     * 拼装缓存键, 规则为 `平台:资源id:页码`。
     */
    private fun pageKey(platform: String, resourceId: Long, page: Int): String {
        return "$platform:$resourceId:$page"
    }

    /**
     * 读取指定页缓存; 条目已超过 [DEFAULT_TTL_MS] 时顺手删除并返回 null。
     */
    fun get(platform: String, resourceId: Long, page: Int): CommentPage? {
        val key = pageKey(platform, resourceId, page)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val entry = entries[key] ?: return null
            if (now - entry.savedAtMs > DEFAULT_TTL_MS) {
                entries.remove(key)
                return null
            }
            return entry.page
        }
    }

    /**
     * 写入指定页缓存, 记录当前时间作为 TTL 起点, 同键覆盖。
     */
    fun put(platform: String, resourceId: Long, page: Int, pageData: CommentPage) {
        val key = pageKey(platform, resourceId, page)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            entries[key] = Entry(pageData, now)
        }
    }

    /**
     * 清空全部评论分页缓存。
     */
    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
