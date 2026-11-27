package moe.ouom.neriplayer.core.startup.navigation

import android.content.Context
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.navigation.readStartDestinationSnapshotOrNull

/**
 * 首帧启动页偏好的读取入口.
 *
 * 与 [StartupThemeSnapshotProvider][moe.ouom.neriplayer.core.startup.theme.StartupThemeSnapshotProvider]
 * 的形态保持一致, 便于启动期各快照的调用方式统一.
 */
internal object StartDestinationSnapshotProvider {

    /**
     * 读取启动页快照.
     *
     * @param context 任意上下文, 内部会取 applicationContext 做后台补热.
     * @param safeModeActive 安全模式下不读任何偏好, 直接返回默认值, 避免坏配置反复触发崩溃.
     * @return 已确定的快照; `null` 表示缓存尚未就绪, 调用方需保留"未知"三态,
     *   等 DataStore 真值到达后再纠正, 不能把默认值当作已确定的选择.
     */
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
