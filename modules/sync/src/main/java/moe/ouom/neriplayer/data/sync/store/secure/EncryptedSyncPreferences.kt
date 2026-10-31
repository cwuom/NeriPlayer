@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.sync.store.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.common.logging.NPLogger

internal object EncryptedSyncPreferences {
    private val stores = HashMap<String, RecoverableSyncPreferences>()

    fun open(context: Context, name: String, tag: String): SharedPreferences = synchronized(stores) {
        val appContext = context.applicationContext
        val identity = "${appContext.noBackupFilesDir.absolutePath}/$name"
        stores.getOrPut(identity) {
            RecoverableSyncPreferences {
                SyncPreferenceRecovery.open(
                    name = identity,
                    create = { create(appContext, name) },
                    delete = { appContext.deleteSharedPreferences(name) },
                    onOpenFailure = { NPLogger.w(tag, "Failed to open secure sync metadata, preserving storage", it) },
                    onDeleteFailure = { NPLogger.w(tag, "Failed to delete corrupted secure prefs file", it) },
                    recoverOnFailure = false
                )
            }
        }
    }

    fun isPersistent(preferences: SharedPreferences): Boolean =
        if (preferences is RecoverableSyncPreferences) preferences.isPersistent
        else moe.ouom.neriplayer.common.storage.SecurePreferencesOpener.isPersistent(preferences)

    private fun create(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, name, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
