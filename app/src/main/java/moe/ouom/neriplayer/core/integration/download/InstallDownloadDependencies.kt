package moe.ouom.neriplayer.core.integration.download

import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHosts

internal fun installDownloadDependencies() {
    DownloadHosts.install(
        DownloadHostBindings(
            environment = AndroidDownloadEnvironment,
            sources = AndroidDownloadServices,
            lyrics = AndroidDownloadServices,
            credentials = AndroidDownloadServices,
            playback = AndroidDownloadedPlaybackHost
        )
    )
}
