package moe.ouom.neriplayer.core.download

import java.text.Normalizer
import java.io.File
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal const val DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE = "%source% - %artist% - %title%"
internal const val LEGACY_DOWNLOAD_FILE_NAME_TEMPLATE = "%artist% - %title%"

internal data class ParsedManagedDownloadFileName(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: String? = null,
    val songId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null
)

private enum class ManagedDownloadTemplateField {
    TITLE,
    ARTIST,
    ALBUM,
    SOURCE,
    SONG_ID,
    AUDIO_ID,
    SUB_AUDIO_ID
}

private data class ManagedDownloadTemplatePattern(
    val regex: Regex,
    val fields: List<ManagedDownloadTemplateField>
)

private val managedDownloadTemplatePlaceholderMap = linkedMapOf(
    "%title%" to ManagedDownloadTemplateField.TITLE,
    "%artist%" to ManagedDownloadTemplateField.ARTIST,
    "%album%" to ManagedDownloadTemplateField.ALBUM,
    "%source%" to ManagedDownloadTemplateField.SOURCE,
    "%id%" to ManagedDownloadTemplateField.SONG_ID,
    "%audioId%" to ManagedDownloadTemplateField.AUDIO_ID,
    "%subAudioId%" to ManagedDownloadTemplateField.SUB_AUDIO_ID
)

private val managedDownloadTemplatePlaceholderRegex = Regex(
    managedDownloadTemplatePlaceholderMap.keys.joinToString(
        separator = "|",
        prefix = "(",
        postfix = ")"
    ) { Regex.escape(it) }
)

internal fun sanitizeManagedDownloadFileName(name: String): String {
    val normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
    return normalized.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio" }
}

internal fun normalizeDownloadFileNameTemplate(template: String?): String? {
    return template?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun candidateManagedDownloadFileNameTemplates(activeTemplate: String? = null): List<String> {
    return linkedSetOf<String>().apply {
        normalizeDownloadFileNameTemplate(activeTemplate)?.let(::add)
        add(DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE)
        add(LEGACY_DOWNLOAD_FILE_NAME_TEMPLATE)
    }.toList()
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

internal fun parseManagedDownloadBaseName(
    baseName: String,
    template: String? = DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
): ParsedManagedDownloadFileName? {
    val normalizedBaseName = baseName.trim().takeIf { it.isNotEmpty() } ?: return null
    val effectiveTemplate = normalizeDownloadFileNameTemplate(template) ?: DEFAULT_DOWNLOAD_FILE_NAME_TEMPLATE
    val pattern = buildManagedDownloadTemplatePattern(effectiveTemplate) ?: return null
    val match = pattern.regex.matchEntire(normalizedBaseName) ?: return null

    fun fieldValue(field: ManagedDownloadTemplateField): String? {
        val groupIndex = pattern.fields.indexOf(field)
        if (groupIndex < 0) {
            return null
        }
        return match.groupValues[groupIndex + 1]
            .trim()
            .takeIf { it.isNotBlank() }
    }

    return ParsedManagedDownloadFileName(
        title = fieldValue(ManagedDownloadTemplateField.TITLE),
        artist = fieldValue(ManagedDownloadTemplateField.ARTIST),
        album = fieldValue(ManagedDownloadTemplateField.ALBUM),
        source = fieldValue(ManagedDownloadTemplateField.SOURCE),
        songId = fieldValue(ManagedDownloadTemplateField.SONG_ID),
        audioId = fieldValue(ManagedDownloadTemplateField.AUDIO_ID),
        subAudioId = fieldValue(ManagedDownloadTemplateField.SUB_AUDIO_ID)
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

private fun buildManagedDownloadTemplatePattern(template: String): ManagedDownloadTemplatePattern? {
    val matches = managedDownloadTemplatePlaceholderRegex.findAll(template).toList()
    if (matches.isEmpty()) {
        return null
    }

    val fields = mutableListOf<ManagedDownloadTemplateField>()
    val pattern = StringBuilder("^")
    var cursor = 0
    var previousWasPlaceholder = false

    matches.forEach { match ->
        val literal = template.substring(cursor, match.range.first)
        if (literal.isEmpty() && previousWasPlaceholder) {
            return null
        }
        pattern.append(Regex.escape(literal))

        val field = managedDownloadTemplatePlaceholderMap[match.value] ?: return null
        if (field in fields) {
            return null
        }
        fields += field
        pattern.append("(.*?)")
        cursor = match.range.last + 1
        previousWasPlaceholder = true
    }

    pattern.append(Regex.escape(template.substring(cursor)))
    pattern.append("$")
    return ManagedDownloadTemplatePattern(
        regex = Regex(pattern.toString()),
        fields = fields.toList()
    )
}
