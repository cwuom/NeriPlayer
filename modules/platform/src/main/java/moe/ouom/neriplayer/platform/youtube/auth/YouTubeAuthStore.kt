package moe.ouom.neriplayer.platform.youtube.auth

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle

class YouTubeAuthSnapshot internal constructor(
    val auth: YouTubeAuthBundle,
    internal val generation: Any = Any()
)

interface YouTubeAuthStore {
    fun captureAuthSnapshot(): YouTubeAuthSnapshot

    fun isCurrent(expected: YouTubeAuthSnapshot): Boolean = runIfCurrent(expected) {}

    /** 只提交仍属于当前快照的结果，返回提交后的快照供同一次刷新继续使用 */
    fun updateAuthIfCurrent(
        expected: YouTubeAuthSnapshot,
        update: (YouTubeAuthBundle) -> YouTubeAuthBundle?
    ): YouTubeAuthSnapshot?

    /** 浏览器 Cookie 写入也要与退出共用账号锁，避免检查后再写入旧凭据 */
    fun runIfCurrent(expected: YouTubeAuthSnapshot, action: () -> Unit): Boolean
}
