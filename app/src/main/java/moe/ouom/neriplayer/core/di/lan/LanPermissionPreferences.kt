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
        LanPermissions(
            playPause = bits and PLAY_PAUSE != 0,
            seek = bits and PROGRESS == PROGRESS,
            volume = bits and VOLUME != 0
        )
    }
    suspend fun save(value: LanPermissions) = mutex.withLock {
        val bits = (if (value.playPause) PLAY_PAUSE else 0) or
            (if (value.seek) PROGRESS else 0) or (if (value.volume) VOLUME else 0)
        repository.setSetting(setting, bits)
    }

    private companion object {
        // 旧设置分别保存切歌和拖动进度，两项都允许时才授予合并后的权限
        const val PROGRESS = 1 or 4
        const val PLAY_PAUSE = 2
        const val VOLUME = 8
    }
}
