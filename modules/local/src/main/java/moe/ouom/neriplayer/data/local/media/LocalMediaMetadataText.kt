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
private val legacyHighByteRun = Regex("[\\u0080-\\u00FF]{2,}")
private val ambiguousUtf8SymbolCategories = setOf(
    CharCategory.CURRENCY_SYMBOL, CharCategory.MATH_SYMBOL,
    CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL
)

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

internal fun decodeLegacyChineseContainer(bytes: ByteArray, utf8Text: String? = null): String? {
    if (bytes.any { it == 0.toByte() }) return null
    // 字母、空白和格式字符不能作为 UTF8 解释错误的证据
    if (utf8Text?.any { it.code >= 0x80 && (it.code > 0xFF || it.category !in ambiguousUtf8SymbolCategories) } == true) return null
    return recoverLegacyChineseMetadata(bytes, minimumCjkCharacters = 1)
}

private fun recoverLegacyChineseMetadata(bytes: ByteArray, minimumCjkCharacters: Int = 2): String? {
    // 字节可逆不能证明编码，纯小写重音和自然的欧洲字母串继续保留 Latin1 解释
    val latin1 = bytes.toString(ISO_8859_1)
    if (legacyHighByteRun.findAll(latin1).none { it.value.hasLegacyChineseSignals() }) return null
    val charset = metadataChineseCharset ?: return null
    return decodeMetadataBytes(bytes, charset)
        ?.takeIf { text -> text.count { it.isCjkUnifiedIdeograph() } >= minimumCjkCharacters }
        ?.takeIf { text -> text.toByteArray(charset).contentEquals(bytes) }
}

private fun String.hasLegacyChineseSignals(): Boolean {
    if (any { !it.isLetter() }) return true
    if (length == 2) return first().isUpperCase() && last().isLowerCase()
    var seenLowercase = false
    val mixedCase = any { char ->
        val suspicious = seenLowercase && char.isUpperCase()
        if (char.isLowerCase()) seenLowercase = true
        suspicious
    }
    return mixedCase || (length >= 4 && all(Char::isUpperCase))
}

private fun decodeMetadataBytes(bytes: ByteArray, charset: Charset): String? {
    return try {
        charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            .takeUnless { text -> text.any { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t") } }
    } catch (_: CharacterCodingException) {
        null
    }
}
