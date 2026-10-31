package moe.ouom.neriplayer.platform.youtube.auth

import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle

internal class RefreshTestAuthStore(
    auth: YouTubeAuthBundle = YouTubeAuthBundle(),
    private val updateFailure: Exception? = null
) : YouTubeAuthStore {
    private var snapshot = YouTubeAuthSnapshot(auth)

    @Synchronized
    fun replaceAuth(auth: YouTubeAuthBundle) {
        snapshot = YouTubeAuthSnapshot(auth)
    }

    @Synchronized
    override fun captureAuthSnapshot(): YouTubeAuthSnapshot = snapshot

    @Synchronized
    override fun updateAuthIfCurrent(
        expected: YouTubeAuthSnapshot,
        update: (YouTubeAuthBundle) -> YouTubeAuthBundle?
    ): YouTubeAuthSnapshot? {
        if (expected !== snapshot) return null
        updateFailure?.let { throw it }
        update(snapshot.auth)?.let(::replaceAuth)
        return snapshot
    }

    @Synchronized
    override fun runIfCurrent(expected: YouTubeAuthSnapshot, action: () -> Unit): Boolean {
        if (expected !== snapshot) return false
        action()
        return true
    }
}
