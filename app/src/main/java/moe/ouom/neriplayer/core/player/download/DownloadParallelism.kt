package moe.ouom.neriplayer.core.player.download

internal const val DEFAULT_DOWNLOAD_PARALLELISM = 6
internal const val MAX_DOWNLOAD_PARALLELISM = 8

internal fun normalizeDownloadParallelism(value: Int): Int {
    return value.coerceIn(1, MAX_DOWNLOAD_PARALLELISM)
}
