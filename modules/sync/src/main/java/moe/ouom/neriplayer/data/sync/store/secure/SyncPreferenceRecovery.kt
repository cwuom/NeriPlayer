package moe.ouom.neriplayer.data.sync.store.secure

import android.content.SharedPreferences
import moe.ouom.neriplayer.common.storage.SecurePreferencesEvent
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener

internal object SyncPreferenceRecovery {
    fun open(
        name: String,
        create: () -> SharedPreferences,
        delete: () -> Unit,
        onOpenFailure: (Throwable) -> Unit,
        onDeleteFailure: (Throwable) -> Unit,
        recoverOnFailure: Boolean = true
    ): SharedPreferences = SecurePreferencesOpener.open(
        name = name,
        create = create,
        delete = delete,
        rebuildCorrupted = recoverOnFailure,
        report = { event, error ->
            if (event == SecurePreferencesEvent.DELETE_FAILED) onDeleteFailure(error) else onOpenFailure(error)
        }
    )
}
