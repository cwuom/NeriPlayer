package moe.ouom.neriplayer.lanltw.media

import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.model.lan.LanSharedPresentation

object LanPresentationCodec {
    const val MAX_BYTES = 8L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(value: LanSharedPresentation): ByteArray = json.encodeToString(LanSharedPresentation.serializer(), value)
        .toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_BYTES) { "METADATA_LIMIT" } }
    fun decode(bytes: ByteArray): LanSharedPresentation {
        check(bytes.size <= MAX_BYTES) { "METADATA_LIMIT" }
        return json.decodeFromString(LanSharedPresentation.serializer(), bytes.toString(Charsets.UTF_8)).also { value ->
            check(value.name.length <= 512 && value.artist.length <= 512 && value.album.length <= 1024) { "SOURCE" }
            check(value.cover?.valid(MAX_BYTES) != false) { "SOURCE" }
            check(listOf(value.lyrics, value.translations, value.romanized).all { lines ->
                lines.size <= 30000 && lines.all { line ->
                    line.text.length <= 16000 && (line.words?.size ?: 0) <= 16000 &&
                        line.startTimeMs >= 0 && line.endTimeMs >= line.startTimeMs &&
                        line.words.orEmpty().all { it.startTimeMs >= 0 && it.endTimeMs >= it.startTimeMs && it.charCount >= 0 }
                }
            }) { "METADATA_LIMIT" }
        }
    }
}
