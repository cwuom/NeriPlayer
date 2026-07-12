package moe.ouom.neriplayer.core.download.storage.naming

import moe.ouom.neriplayer.core.download.storage.imageExtensions

internal object ManagedDownloadStorageNaming {
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
        val suffix = java.lang.Long.toHexString(stableKey.hashCode().toLong() and 0xffffffffL)
        return imageExtensions.map { extension -> "$baseName-$suffix.$extension" }
    }

    fun buildLyricCandidateNames(
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): List<String> {
        val names = linkedSetOf<String>()
        fun addLyricNames(baseName: String) {
            if (translated) {
                names += "${baseName}_trans.lrc"
                names += "${baseName}_trans.lrc.txt"
            } else {
                names += "$baseName.lrc"
                names += "$baseName.lrc.txt"
            }
        }

        songId?.takeIf { it > 0L }?.let { resolvedSongId ->
            addLyricNames(resolvedSongId.toString())
        }
        candidateBaseNames.forEach(::addLyricNames)
        return names.toList()
    }

    fun createUniqueName(existingNames: Set<String>, desiredName: String): String {
        if (desiredName !in existingNames) return desiredName
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var index = 1
        while (index < 10_000) {
            val candidate = if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext"
            if (candidate !in existingNames) {
                return candidate
            }
            index++
        }
        return desiredName
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
