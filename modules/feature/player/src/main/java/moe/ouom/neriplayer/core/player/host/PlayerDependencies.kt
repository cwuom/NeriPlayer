package moe.ouom.neriplayer.core.player.host

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class PlayerEnvironment(
    val application: Application,
    val repositories: PlayerRepositoryDependencies,
    val downloads: PlayerDownloadAccess,
    val listenTogether: PlayerListenTogetherAccess,
    val presentation: PlayerPresentationHost,
    val isReady: () -> Boolean,
    val launchBackgroundIo: (suspend CoroutineScope.() -> Unit) -> Job,
)

object PlayerDependencies {
    private val registry = PlayerDependencyRegistry()

    fun install(environment: PlayerEnvironment) = registry.install(environment)

    private fun requireEnvironment(): PlayerEnvironment = registry.requireEnvironment()

    val applicationContext get() = requireEnvironment().application
    val repositories get() = requireEnvironment().repositories
    val downloads get() = requireEnvironment().downloads
    val listenTogether get() = requireEnvironment().listenTogether
    val presentation get() = requireEnvironment().presentation
    fun isInitialized(): Boolean = registry.isReady()
    fun launchBackgroundIo(block: suspend CoroutineScope.() -> Unit): Job =
        requireEnvironment().launchBackgroundIo(block)
}
