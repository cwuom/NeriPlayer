package moe.ouom.neriplayer.data.local.media

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8

private val metadataSingleByteCharsets: List<Charset> by lazy {
    listOfNotNull(ISO_8859_1, supportedCharsetOrNull("windows-1252"))
}

private val metadataChineseCharset: Charset? by lazy { supportedCharsetOrNull("GB18030") }
private val legacyHighByteRun = Regex("[\\u0080-\\u00FF]{4,}")
private val containerHighByteRun = Regex("[\\u0080-\\u00FF]+")

internal fun repairLocalMetadataText(value: String): String {
    if (value.all { it.code < 0x80 }) return value
    return metadataSingleByteCharsets.firstNotNullOfOrNull { charset ->
        val encoder = charset.newEncoder()
        if (!encoder.canEncode(value)) return@firstNotNullOfOrNull null
        val buffer = encoder.encode(CharBuffer.wrap(value))
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        decodeLocalMetadataUtf8(bytes)
            ?: recoverLegacyChineseMetadata(bytes)
    } ?: value
}

internal fun decodeLocalMetadataUtf8(bytes: ByteArray): String? = decodeMetadataBytes(bytes, UTF_8)

internal fun decodeShortLegacyChineseContainer(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    val runs = containerHighByteRun.findAll(bytes.toString(ISO_8859_1)).toList()
    // 短汉字可能被 ASCII 分隔，孤立重音字母和连续欧洲文字仍保留原有解释
    if (runs.isEmpty() || runs.any { it.value.length != 2 }) return null
    val charset = metadataChineseCharset ?: return null
    return decodeMetadataBytes(bytes, charset)
        ?.takeIf { text -> text.any { it.isCjkUnifiedIdeograph() } }
        ?.takeIf { text -> text.toByteArray(charset).contentEquals(bytes) }
}

private fun recoverLegacyChineseMetadata(bytes: ByteArray): String? {
    // 连续高字节仍可能是正常欧洲文字，只对混杂符号或异常大小写的片段尝试旧中文编码
    val latin1 = bytes.toString(ISO_8859_1)
    if (legacyHighByteRun.findAll(latin1).none { it.value.hasLegacyChineseSignals() }) return null
    val charset = metadataChineseCharset ?: return null
    return decodeMetadataBytes(bytes, charset)
        ?.takeIf { text -> text.count { it.isCjkUnifiedIdeograph() } >= 2 }
        ?.takeIf { text -> text.toByteArray(charset).contentEquals(bytes) }
}

private fun String.hasLegacyChineseSignals(): Boolean {
    var seenLowercase = false
    return any { char ->
        val suspicious = !char.isLetter() || (seenLowercase && char.isUpperCase())
        if (char.isLowerCase()) seenLowercase = true
        suspicious
    }
}

private fun decodeMetadataBytes(bytes: ByteArray, charset: Charset): String? {
    return try {
        charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            .takeUnless { text -> text.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") } }
    } catch (_: CharacterCodingException) {
        null
    }
}
