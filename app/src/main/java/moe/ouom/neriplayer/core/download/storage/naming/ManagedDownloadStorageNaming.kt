package moe.ouom.neriplayer.core.download.storage.naming

import moe.ouom.neriplayer.core.download.storage.imageExtensions
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale

internal object ManagedDownloadStorageNaming {
    internal enum class LyricKind {
        ORIGINAL,
        TRANSLATED,
        ROMANIZED
    }

    fun buildSidecarCandidateNames(candidateBaseNames: List<String>): List<String> {
        return buildList {
            candidateBaseNames.forEach { baseName ->
                imageExtensions.forEach { extension ->
                    add("$baseName.$extension")
                }
            }
        }
    }

    fun buildStableCoverCandidateNames(baseName: String, stableKey: String): List<String> {
        val suffixes = listOf(
            coverStableKeySuffix(stableKey),
            stableKeySuffix(stableKey)
        ).distinct()
        return buildList {
            suffixes.forEach { suffix ->
                imageExtensions.forEach { extension ->
                    add("$baseName-$suffix.$extension")
                }
            }
        }
    }

    /** legacy names are read only while importing an older managed root */
    fun buildLegacyStableCoverCandidateNames(baseName: String, stableKey: String): List<String> {
        val suffix = legacyStableKeySuffix(stableKey)
        return imageExtensions.map { extension ->
            "$baseName-$suffix.$extension"
        }
    }

    fun stableKeySuffix(stableKey: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(stableKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(32)
    }

    fun coverStableKeySuffix(stableKey: String): String {
        return stableKeySuffix(stableKey).take(8)
    }

    fun legacyStableKeySuffix(stableKey: String): String {
        return java.lang.Long.toHexString(stableKey.hashCode().toLong() and 0xffffffffL)
    }

    fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): List<String> {
        return buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            kind = if (translated) LyricKind.TRANSLATED else LyricKind.ORIGINAL
        )
    }

    fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        kind: LyricKind
    ): List<String> {
        val names = linkedSetOf<String>()
        fun addLyricNames(baseName: String) {
            val prefixes = when (kind) {
                LyricKind.ORIGINAL -> listOf(baseName)
                LyricKind.TRANSLATED -> listOf("${baseName}_trans")
                LyricKind.ROMANIZED -> listOf(
                    "${baseName}_roma",
                    "${baseName}_romalrc",
                    "${baseName}_romanized"
                )
            }
            prefixes.forEach { prefix ->
                names += "$prefix.lrc"
                names += "$prefix.lrc.txt"
            }
        }

        songId?.takeIf { it > 0L }?.let { resolvedSongId ->
            addLyricNames(resolvedSongId.toString())
        }
        candidateBaseNames.forEach(::addLyricNames)
        return names.toList()
    }

    fun createUniqueName(existingNames: Set<String>, desiredName: String): String {
        val canonicalExistingNames = existingNames.mapTo(HashSet()) { canonicalName(it) }
        if (canonicalName(desiredName) !in canonicalExistingNames) return desiredName
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var index = 1
        while (index < 10_000) {
            val candidate = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            if (canonicalName(candidate) !in canonicalExistingNames) {
                return candidate
            }
            index++
        }
        return desiredName
    }

    private fun canonicalName(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    fun mimeTypeFromName(name: String, fallback: String?): String {
        val normalizedFallback = fallback?.takeIf { it.isNotBlank() }
        if (normalizedFallback != null) return normalizedFallback
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "eac3" -> "audio/eac3"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "lrc" -> "application/octet-stream"
            "txt", "json" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
