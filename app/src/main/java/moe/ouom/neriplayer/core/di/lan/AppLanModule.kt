package moe.ouom.neriplayer.core.di.lan

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.lan.LanPlayerAdapter
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.lanltw.session.LanSessionManager

internal object AppLanModule {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun create(): LanSessionManager {
        lateinit var manager: LanSessionManager
        val cloud = AppContainer.listenTogetherSessionManager
        val permissions = LanPermissionPreferences(AppContainer.settingsRepo)
        manager = LanSessionManager(AppContainer.applicationContext, AppContainer.sharedOkHttpClient,
            LanPlayerAdapter { manager.state.value.hosting },
            cloudActive = { !cloud.sessionState.value.roomId.isNullOrBlank() },
            saveNudge = { value -> scope.launch { AppContainer.settingsRepo.setSetting(AutoSettingsSchema.playback.lanNudgeMs, value) } },
            loadPermissions = permissions::load,
            savePermissions = { value -> scope.launch { permissions.save(value) } })
        scope.launch { manager.restoreNudge(AppContainer.settingsRepo.settingFlow(AutoSettingsSchema.playback.lanNudgeMs).first()) }
        scope.launch { cloud.sessionState.collect { if (!it.roomId.isNullOrBlank() && manager.state.value.active) manager.leave("CLOUD_ACTIVE") } }
        scope.launch {
            manager.state.map { it.error }.distinctUntilChanged().filterNotNull().collect { code ->
                if (code != "LOADING") moe.ouom.neriplayer.ui.feedback.AppFeedback.show(
                    context = AppContainer.applicationContext,
                    message = AppContainer.applicationContext.getString(moe.ouom.neriplayer.ui.feedback.lanErrorText(code)))
            }
        }
        return manager
    }
}
