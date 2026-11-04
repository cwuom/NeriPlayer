package moe.ouom.neriplayer.core.startup.navigation

import android.content.Context
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.navigation.readStartDestinationSnapshotOrNull

/**
 * 首帧启动页偏好. null 表示缓存尚未就绪, 调用方需保留"未知"三态,
 * 等 DataStore 真值到达后再纠正, 不能把默认值当作已确定的选择.
 */
internal object StartDestinationSnapshotProvider {
    fun read(
        context: Context,
        safeModeActive: Boolean
    ): StartDestinationSnapshot? {
        return if (safeModeActive) {
            StartDestinationSnapshot()
        } else {
            readStartDestinationSnapshotOrNull(context)
        }
    }
}
