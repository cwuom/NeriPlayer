package moe.ouom.neriplayer.data.sync.store.state

internal fun hasSyncCredential(value: String?): Boolean = !value.isNullOrEmpty()

internal fun hasNonBlankSyncCredential(value: String?): Boolean = !value.isNullOrBlank()
