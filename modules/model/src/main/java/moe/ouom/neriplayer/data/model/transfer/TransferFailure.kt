package moe.ouom.neriplayer.data.model.transfer

import java.io.IOException

enum class TransferFailureKind {
    OUTPUT_UNAVAILABLE,
    AUTH_STORAGE_UNAVAILABLE,
    AUTH_STORAGE_CONFIRMATION_REQUIRED,
    LOCAL_PLAYLIST_NOT_READY,
    PLAY_HISTORY_NOT_READY,
    PLAYBACK_STATS_NOT_READY,
    BACKUP_INPUT_UNAVAILABLE,
    NO_PLAYLIST_DATA
}

interface TransferFailure {
    val kind: TransferFailureKind
}

class TransferIOException(
    override val kind: TransferFailureKind,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause), TransferFailure

class TransferStateException(
    override val kind: TransferFailureKind,
    message: String
) : IllegalStateException(message), TransferFailure

class TransferArgumentException(
    override val kind: TransferFailureKind,
    message: String
) : IllegalArgumentException(message), TransferFailure
