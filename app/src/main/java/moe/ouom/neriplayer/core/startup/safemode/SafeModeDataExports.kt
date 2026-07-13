package moe.ouom.neriplayer.core.startup.safemode

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.data.backup.BackupManager
import moe.ouom.neriplayer.data.config.ConfigFileManager

internal class SafeModeDataExports(
    context: Context
) {
    private val appContext = context.applicationContext

    suspend fun exportConfigBackup(destination: Uri): Result<String> {
        return ConfigFileManager(appContext).exportConfig(destination)
    }

    suspend fun exportPlaylistBackup(destination: Uri): Result<String> {
        return BackupManager(appContext).exportPlaylists(destination)
    }

    fun generateConfigBackupFileName(): String {
        return ConfigFileManager(appContext).generateBackupFileName()
    }

    fun generatePlaylistBackupFileName(): String {
        return BackupManager(appContext).generateBackupFileName()
    }
}
