package moe.ouom.neriplayer.core.di.ltw

import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.ltw.platform.ListenTogetherPlatformHost
import moe.ouom.neriplayer.data.ltw.validation.format
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherValidationError

internal object AppListenTogetherPlatform : ListenTogetherPlatformHost {
    override val applicationContext get() = AppContainer.applicationContext
    override fun isInitialized(): Boolean = AppContainer.isInitialized()
    override fun isPlaybackServiceReady(): Boolean = AudioPlayerService.isReadyForPassiveLocalPlaybackSync()
    override fun startForegroundSync(reason: String) {
        AudioPlayerService.startSyncService(applicationContext, "listen_together_$reason", forceForeground = true)
    }
    override fun message(resourceId: Int): String = applicationContext.getString(resourceId)
    override fun validationMessage(error: ListenTogetherValidationError): String = error.format(applicationContext)
}
