package moe.ouom.neriplayer.core.download.host

// 安装完整接口集合后再启动下载，避免初始化期间读取到不同宿主的依赖
class DownloadHostBindings(
    val environment: DownloadEnvironment,
    val sources: DownloadSourceServices,
    val lyrics: DownloadLyricServices,
    val credentials: DownloadCredentials,
    val playback: DownloadedPlaybackHost
)

internal class DownloadHostRegistry {
    @Volatile
    private var installed: DownloadHostBindings? = null

    fun install(bindings: DownloadHostBindings) {
        installed = bindings
    }

    fun bindings(): DownloadHostBindings = checkNotNull(installed) {
        "Download hosts must be installed before starting downloads"
    }
}

object DownloadHosts {
    private val registry = DownloadHostRegistry()

    fun install(bindings: DownloadHostBindings) {
        registry.install(bindings)
    }

    val environment: DownloadEnvironment get() = registry.bindings().environment
    val sources: DownloadSourceServices get() = registry.bindings().sources
    val lyrics: DownloadLyricServices get() = registry.bindings().lyrics
    val credentials: DownloadCredentials get() = registry.bindings().credentials
    val playback: DownloadedPlaybackHost get() = registry.bindings().playback
}
