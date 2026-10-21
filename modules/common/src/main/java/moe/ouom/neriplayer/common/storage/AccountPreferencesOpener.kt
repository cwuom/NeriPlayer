package moe.ouom.neriplayer.common.storage

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** 账户在系统密钥不可用时使用本机软件加密，兼容文件一旦存在便优先读取，防止旧凭据复活 */
object AccountPreferencesOpener {
    private val lock = Any()
    private val sharedByPath = HashMap<String, SharedPreferences>()

    fun open(
        context: Context,
        name: String,
        create: () -> SharedPreferences,
        delete: () -> Unit,
        report: (SecurePreferencesEvent, Throwable) -> Unit = { _, _ -> }
    ): SharedPreferences = synchronized(lock) {
        existing(context, name, report)?.let { return@synchronized it }
        val preferences = try {
            SecurePreferencesOpener.open(
                name = "account-primary:${storage(context, name).dataFile.absolutePath}",
                create = create,
                delete = delete,
                rebuildCorrupted = false,
                report = report
            )
        } catch (_: Exception) {
            return@synchronized fallback(context, name, report)
        }
        if (SecurePreferencesOpener.isPersistent(preferences)) preferences else fallback(context, name, report)
    }

    fun fallback(
        context: Context,
        name: String,
        report: (SecurePreferencesEvent, Throwable) -> Unit = { _, _ -> }
    ): SharedPreferences = synchronized(lock) {
        val storage = storage(context, name)
        sharedByPath.getOrPut(storage.dataFile.absolutePath) {
            openCompatibility(storage, report)
        }
    }

    fun existing(
        context: Context,
        name: String,
        report: (SecurePreferencesEvent, Throwable) -> Unit = { _, _ -> }
    ): SharedPreferences? = synchronized(lock) {
        val storage = storage(context, name)
        sharedByPath[storage.dataFile.absolutePath]?.let { return@synchronized it }
        if (!storage.hasArtifacts()) return@synchronized null
        openCompatibility(storage, report).also { sharedByPath[storage.dataFile.absolutePath] = it }
    }

    fun isCompatibilityStorage(preferences: SharedPreferences): Boolean =
        preferences is SoftwareEncryptedSharedPreferences

    private fun storage(context: Context, name: String) =
        CompatibilityPreferencesFile(File(context.noBackupFilesDir, "account_preferences"), name)

    private fun openCompatibility(
        storage: CompatibilityPreferencesFile,
        report: (SecurePreferencesEvent, Throwable) -> Unit
    ): SharedPreferences = try {
        storage.open()
    } catch (error: Exception) {
        report(SecurePreferencesEvent.OPEN_FAILED, error)
        report(SecurePreferencesEvent.VOLATILE_FALLBACK, error)
        VolatileSharedPreferences.shared("account:${storage.dataFile.absolutePath}")
    }
}
