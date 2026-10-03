@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.data.sync.store.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.common.logging.NPLogger

internal object EncryptedSyncPreferences {
    fun open(context: Context, name: String, tag: String, recoverOnFailure: Boolean = true): SharedPreferences = SyncPreferenceRecovery.open(
        create = { create(context, name) },
        delete = { context.deleteSharedPreferences(name) },
        onOpenFailure = {
            val message = if (recoverOnFailure) "Failed to open secure prefs, clearing storage and recreating"
                else "Failed to open secure sync metadata, preserving storage"
            NPLogger.w(tag, message, it)
        },
        onDeleteFailure = { NPLogger.w(tag, "Failed to delete corrupted secure prefs file", it) },
        recoverOnFailure = recoverOnFailure
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
