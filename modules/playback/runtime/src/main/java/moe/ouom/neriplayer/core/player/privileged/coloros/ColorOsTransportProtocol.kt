package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.InputStream
import java.io.OutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

internal object ColorOsTransportProtocol {
    const val MAX_FRAME_BYTES = 384 * 1024
    const val MAX_XML_BYTES = 128 * 1024
    const val FILTER = "app_systemui_oplus_media_controller_config"
    const val AUTHORITY = "com.oplus.romupdate.provider.db"
    const val URI = "content://$AUTHORITY/update_list"

    fun encodeRequest(operation: String, expected: ColorOsRusRow? = null, replacement: ColorOsRusRow? = null): String {
        val value = JSONObject().put("op", operation)
        if (expected != null) value.put("expected", rowJson(expected))
        if (replacement != null) value.put("replacement", rowJson(replacement))
        return value.toString().also(::validateRequest)
    }

    fun validateRequest(request: String): String {
        checkSize(request)
        val json = parse(request)
        val operation = json.opt("op") as? String ?: malformed()
        val keys = json.keys().asSequence().toSet()
        when (operation) {
            "read", "reload", "close" -> if (keys != setOf("op")) malformed()
            "cas" -> {
                if (keys != setOf("op", "expected", "replacement")) malformed()
                row(json.optJSONObject("expected") ?: malformed())
                row(json.optJSONObject("replacement") ?: malformed())
            }
            else -> malformed()
        }
        return operation
    }

    fun writeFrame(output: OutputStream, value: String) {
        val bytes = checkSize(value)
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    fun readFrame(input: InputStream): String {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        if (size !in 1..MAX_FRAME_BYTES) malformed()
        val bytes = ByteArray(size)
        stream.readFully(bytes)
        return decode(bytes)
    }

    fun checkSize(value: String): ByteArray = value.toByteArray(Charsets.UTF_8).also {
        if (it.size !in 1..MAX_FRAME_BYTES) throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "protocol_size")
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size !in 1..MAX_FRAME_BYTES) malformed()
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { malformed() }
    }

    fun parse(value: String): JSONObject = try {
        val tokenizer = org.json.JSONTokener(value)
        val json = tokenizer.nextValue() as? JSONObject ?: malformed()
        if (tokenizer.nextClean() != '\u0000') malformed()
        json
    } catch (_: org.json.JSONException) { malformed() }

    fun rowJson(row: ColorOsRusRow): JSONObject = JSONObject().apply {
        if (row.xml.toByteArray(Charsets.UTF_8).size > MAX_XML_BYTES) {
            throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "xml_size")
        }
        put("version", row.version); put("xml", row.xml)
        put("hasMd5", row.hasMd5); put("md5", row.md5 ?: JSONObject.NULL)
        put("hasIsOpen", row.hasIsOpen); put("isOpen", row.isOpen ?: JSONObject.NULL)
    }

    fun row(json: JSONObject): ColorOsRusRow {
        if (json.keys().asSequence().toSet() != setOf("version", "xml", "hasMd5", "md5", "hasIsOpen", "isOpen")) malformed()
        val version = json.opt("version") as? String ?: malformed()
        val xml = json.opt("xml") as? String ?: malformed()
        val hasMd5 = json.opt("hasMd5") as? Boolean ?: malformed()
        val hasIsOpen = json.opt("hasIsOpen") as? Boolean ?: malformed()
        fun nullableString(key: String): String? = if (json.isNull(key)) null else json.opt(key) as? String ?: malformed()
        return ColorOsRusRow(version, xml, hasMd5, nullableString("md5"), hasIsOpen, nullableString("isOpen")).also {
            rowJson(it)
            if ((!it.hasMd5 && it.md5 != null) || (!it.hasIsOpen && it.isOpen != null)) malformed()
        }
    }

    fun rootCommand(apkPath: String, targetUser: Int): List<String> {
        require(targetUser >= 0 && '\u0000' !in apkPath)
        val quoted = "'" + apkPath.replace("'", "'\"'\"'") + "'"
        return listOf("su", "-c", "CLASSPATH=$quoted /system/bin/app_process /system/bin moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsRootMain $targetUser")
    }

    fun reloadCommand(targetUser: Int): List<String> {
        require(targetUser >= 0)
        return listOf("am", "broadcast", "--user", "$targetUser", "-a", "oplus.intent.action.ROM_UPDATE_CONFIG_SUCCESS", "--esal", "ROM_UPDATE_CONFIG_LIST", FILTER)
    }

    fun verifyReload(exitCode: Int, output: String) {
        if (output.contains("Permission Denial", true) || output.contains("SecurityException", true)) {
            throw ColorOsConfigException(ColorOsConfigStatus.PERMISSION_DENIED, "reload_permission_denied")
        }
        if (exitCode != 0 || !Regex("(?m)^Broadcast completed: result=-?\\d+\\s*$").containsMatchIn(output)) {
            throw ColorOsConfigException(ColorOsConfigStatus.RELOAD_FAILED, "reload_unconfirmed")
        }
    }

    fun authorize(callingUid: Int, appUid: Int) {
        if (callingUid != appUid || appUid < 10_000) throw SecurityException("caller_uid")
    }

    fun malformed(): Nothing = throw ColorOsConfigException(ColorOsConfigStatus.ERROR, "protocol_malformed")
}
