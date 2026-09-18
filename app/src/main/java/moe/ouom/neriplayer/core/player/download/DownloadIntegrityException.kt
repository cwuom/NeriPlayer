package moe.ouom.neriplayer.core.player.download

import java.io.IOException

internal class DownloadIntegrityException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
