package moe.ouom.neriplayer.core.download

import java.text.Normalizer
import java.io.File
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal const val DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE = "%source% - %artist% - %title%"

internal fun sanitizeManagedDownloadFileName(name: String): String {
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
    return normalized.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }
}

internal fun normalizeDownloadFileNameTemplate(template: String?): String? {
    return template?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun renderManagedDownloadBaseName(
    title: String,
    artist: String,
    album: String,
    source: String = "",
    songId: String = "",
    audioId: String = "",
    subAudioId: String = "",
    template: String? = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
): String {
    val effectiveTemplate = normalizeDownloadFileNameTemplate(template) ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    val rendered = effectiveTemplate
        .replace("%title%", title)
        .replace("%artist%", artist)
        .replace("%album%", album)
        .replace("%source%", source)
        .replace("%id%", songId)
        .replace("%audioId%", audioId)
        .replace("%subAudioId%", subAudioId)
    return sanitizeManagedDownloadFileName(rendered)
}

internal fun renderManagedDownloadBaseName(
    song: SongItem,
    template: String? = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
): String {
    return renderManagedDownloadBaseName(
        title = song.customName ?: song.name,
        artist = song.customArtist ?: song.artist,
        album = song.album,
        source = managedDownloadSource(song),
        songId = song.id.toString(),
        audioId = song.audioId.orEmpty(),
        subAudioId = song.subAudioId.orEmpty(),
        template = template
    )
}

internal fun candidateManagedDownloadBaseNames(
    song: SongItem,
    activeTemplate: String? = null
): List<String> {
    val baseNames = linkedSetOf<String>()
    baseNames += renderManagedDownloadBaseName(song, activeTemplate)
    baseNames += renderManagedDownloadBaseName(
        title = song.name,
        artist = song.artist,
        album = song.album,
        source = managedDownloadSource(song),
        songId = song.id.toString(),
        audioId = song.audioId.orEmpty(),
        subAudioId = song.subAudioId.orEmpty(),
        template = activeTemplate
    )

    val originalName = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
    val originalArtist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
    baseNames += renderManagedDownloadBaseName(
        title = originalName,
        artist = originalArtist,
        album = song.album,
        source = managedDownloadSource(song),
        songId = song.id.toString(),
        audioId = song.audioId.orEmpty(),
        subAudioId = song.subAudioId.orEmpty(),
        template = activeTemplate
    )

    // Keep matching historical downloads created before custom templates were introduced.
    baseNames += sanitizeManagedDownloadFileName("${song.customArtist ?: song.artist} - ${song.customName ?: song.name}")
    baseNames += sanitizeManagedDownloadFileName("${song.artist} - ${song.name}")
    baseNames += sanitizeManagedDownloadFileName("$originalArtist - $originalName")
    appendLocalFileDerivedBaseNames(baseNames, song)

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

private fun managedDownloadSource(song: SongItem): String {
    return song.channelId
        ?.takeIf { it.isNotBlank() }
        ?: when {
            song.album.startsWith("bili", ignoreCase = true) -> "bilibili"
            song.mediaUri?.contains("youtube", ignoreCase = true) == true -> "youtube_music"
            else -> "netease"
        }
}

private fun appendLocalFileDerivedBaseNames(
    baseNames: MutableSet<String>,
    song: SongItem
) {
    buildSet {
        song.localFileName
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        song.localFilePath
            ?.takeIf(String::isNotBlank)
            ?.let(::extractManagedLocalFileName)
            ?.let(::add)
        song.mediaUri
            ?.takeIf(String::isNotBlank)
            ?.let(::extractManagedLocalFileName)
            ?.let(::add)
    }.forEach { rawFileName ->
        val normalizedName = rawFileName
            .substringAfterLast('/')
            .substringAfterLast(File.separatorChar)
            .takeIf(String::isNotBlank)
            ?: return@forEach
        val baseName = normalizedName.substringBeforeLast('.', normalizedName)
        candidateManagedDownloadBaseNames(baseName).forEach(baseNames::add)
    }
}

private fun extractManagedLocalFileName(location: String): String? {
    val normalized = location
        .substringBefore('?')
        .substringBefore('#')
    if (normalized.isBlank()) return null
    return normalized.substringAfterLast('/')
        .substringAfterLast(File.separatorChar)
        .takeIf(String::isNotBlank)
}
