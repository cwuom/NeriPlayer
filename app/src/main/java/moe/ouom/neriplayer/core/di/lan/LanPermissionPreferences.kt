package moe.ouom.neriplayer.core.di.lan

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.model.lan.LanPermissions
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema

/** One atomic setting; room creation waits for any preceding permission write. */
internal class LanPermissionPreferences(private val repository: SettingsRepository) {
    private val mutex = Mutex()
    private val setting = AutoSettingsSchema.playback.lanHostPermissions
    suspend fun load(): LanPermissions = mutex.withLock {
        val bits = repository.settingFlow(setting).first()
        LanPermissions(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
    }
    suspend fun save(value: LanPermissions) = mutex.withLock {
        val bits = (if (value.next) 1 else 0) or
            (if (value.playPause) 2 else 0) or (if (value.seek) 4 else 0)
        repository.setSetting(setting, bits)
    }
}
