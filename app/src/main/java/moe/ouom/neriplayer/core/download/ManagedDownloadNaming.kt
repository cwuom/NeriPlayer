package moe.ouom.neriplayer.core.download

import java.text.Normalizer
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal fun sanitizeManagedDownloadFileName(name: String): String {
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
    return normalized.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }
}

internal fun candidateManagedDownloadBaseNames(song: SongItem): List<String> {
    val baseNames = linkedSetOf<String>()
    baseNames += sanitizeManagedDownloadFileName("${song.customArtist ?: song.artist} - ${song.customName ?: song.name}")
    baseNames += sanitizeManagedDownloadFileName("${song.artist} - ${song.name}")

    val originalName = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
    val originalArtist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
    baseNames += sanitizeManagedDownloadFileName("$originalArtist - $originalName")

    return baseNames.toList()
}

internal fun candidateManagedDownloadBaseNames(fileNameWithoutExtension: String): List<String> {
    val names = linkedSetOf(fileNameWithoutExtension)
    val base = fileNameWithoutExtension.replace(Regex(" \\(\\d+\\)$"), "")
    if (base != fileNameWithoutExtension) {
        names += base
    }
    return names.toList()
}
