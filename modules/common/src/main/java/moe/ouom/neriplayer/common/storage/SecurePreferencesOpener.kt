package moe.ouom.neriplayer.common.storage

import android.content.SharedPreferences
import java.security.KeyStoreException
import java.security.ProviderException

enum class SecurePreferencesEvent { OPEN_FAILED, DELETE_FAILED, VOLATILE_FALLBACK }

/**
 * 打开 EncryptedSharedPreferences 的统一失败策略
 *
 * 所有加密存储共用同一个 Keystore 主密钥，主密钥暂不可用时删除文件不能修复问题，
 * 只会丢失凭据，因此保留原文件并在本进程改用内存存储；只有密钥集或数据损坏时才删除重建
 */
object SecurePreferencesOpener {
    private const val MAX_CAUSE_DEPTH = 16

    fun open(
        name: String,
        create: () -> SharedPreferences,
        delete: () -> Unit,
        rebuildCorrupted: Boolean = true,
        report: (SecurePreferencesEvent, Throwable) -> Unit = { _, _ -> }
    ): SharedPreferences {
        VolatileSharedPreferences.existing(name)?.let { return it }
        val failure = try {
            return create()
        } catch (error: Exception) {
            error
        }
        report(SecurePreferencesEvent.OPEN_FAILED, failure)
        if (isKeystoreUnavailable(failure)) return volatileFallback(name, failure, report)
        if (!rebuildCorrupted) throw failure
        try {
            delete()
        } catch (error: Exception) {
            report(SecurePreferencesEvent.DELETE_FAILED, error)
        }
        return try {
            create()
        } catch (error: Exception) {
            volatileFallback(name, error, report)
        }
    }

    fun isKeystoreUnavailable(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .any { cause ->
            cause is KeyStoreException || cause is ProviderException ||
                cause.javaClass.name == "android.security.KeyStoreException"
        }

    fun isPersistent(preferences: SharedPreferences): Boolean = preferences !is VolatileSharedPreferences

    private fun volatileFallback(
        name: String,
        error: Exception,
        report: (SecurePreferencesEvent, Throwable) -> Unit
    ): SharedPreferences {
        report(SecurePreferencesEvent.VOLATILE_FALLBACK, error)
        return VolatileSharedPreferences.shared(name)
    }
}
