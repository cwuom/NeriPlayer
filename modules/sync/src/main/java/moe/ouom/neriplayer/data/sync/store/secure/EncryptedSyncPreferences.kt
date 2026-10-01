@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.sync.store.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.core.logging.NPLogger

internal object EncryptedSyncPreferences {
    fun open(context: Context, name: String, tag: String): SharedPreferences = SyncPreferenceRecovery.open(
        create = { create(context, name) },
        delete = { context.deleteSharedPreferences(name) },
        onOpenFailure = { NPLogger.w(tag, "Failed to open secure prefs, clearing storage and recreating", it) },
        onDeleteFailure = { NPLogger.w(tag, "Failed to delete corrupted secure prefs file", it) }
    )

    private fun create(context: Context, name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, name, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
