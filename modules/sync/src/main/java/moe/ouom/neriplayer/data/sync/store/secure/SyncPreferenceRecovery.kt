package moe.ouom.neriplayer.data.sync.store.secure

import android.content.SharedPreferences

internal object SyncPreferenceRecovery {
    fun open(
        create: () -> SharedPreferences,
        delete: () -> Unit,
        onOpenFailure: (Throwable) -> Unit,
        onDeleteFailure: (Throwable) -> Unit,
        recoverOnFailure: Boolean = true
    ): SharedPreferences = runCatching(create).getOrElse { error ->
        onOpenFailure(error)
        if (!recoverOnFailure) throw error
        runCatching(delete).onFailure(onDeleteFailure)
        create()
    }
}
