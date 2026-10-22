package moe.ouom.neriplayer.data.config

import moe.ouom.neriplayer.data.model.config.CONFIG_FORMAT_VERSION
import moe.ouom.neriplayer.data.model.config.CONFIG_KIND

import moe.ouom.neriplayer.data.model.config.AppConfigBackup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private const val CONFIG_FILE_PREFIX = "neriplayer_config"
private const val CONFIG_FILE_EXTENSION = ".json"

private val configJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal data class AppConfigBackupSections(
    val settings: Boolean,
    val listenTogether: Boolean,
    val language: Boolean,
    val neteaseAuth: Boolean,
    val biliAuth: Boolean,
    val youTubeAuth: Boolean,
    val gitHubSync: Boolean,
    val webDavSync: Boolean,
    val syncPreferences: Boolean
) {
    val hasSyncSection: Boolean
        get() = gitHubSync || webDavSync || syncPreferences

    fun hasAnySection(): Boolean = listOf(
        settings,
        listenTogether,
        language,
        neteaseAuth,
        biliAuth,
        youTubeAuth,
        gitHubSync,
        webDavSync,
        syncPreferences
    ).any { it }
}

internal data class DecodedAppConfigBackup(
    val payload: AppConfigBackup,
    val sections: AppConfigBackupSections
)

object AppConfigBackupCodec {
    fun encode(payload: AppConfigBackup): String = configJson.encodeToString(AppConfigBackup.serializer(), payload)

    // password 数组交给本次操作使用，返回或失败时都会清零
    fun encodeForExport(
        payload: AppConfigBackup,
        options: ConfigExportOptions = ConfigExportOptions(),
        password: CharArray? = null
    ): String = try {
        val root = configJson.encodeToJsonElement(AppConfigBackup.serializer(), payload).jsonObject
        val exported = if (options.includeLoginState) root else JsonObject(root.filterKeys { it !in LOGIN_STATE_SECTIONS })
        val raw = configJson.encodeToString(JsonObject.serializer(), exported)
        ConfigBackupLimits.requireBytes(raw, ConfigBackupLimits.MAX_PLAINTEXT_BYTES)
        if (password == null) raw else ConfigBackupEncryption.encrypt(raw, password)
    } finally {
        password?.fill('\u0000')
    }

    fun decode(raw: String): AppConfigBackup {
        return decodeForImport(raw).payload
    }

    internal fun decodeForImport(raw: String, password: CharArray? = null): DecodedAppConfigBackup = try {
        ConfigBackupLimits.requireBytes(raw, ConfigBackupLimits.MAX_ENVELOPE_BYTES)
        val root = parseRootObject(raw)
        val plaintext = if (ConfigBackupEncryption.isEncrypted(root)) ConfigBackupEncryption.decrypt(raw, password) else raw
        ConfigBackupLimits.requireBytes(plaintext, ConfigBackupLimits.MAX_PLAINTEXT_BYTES)
        decodePlaintext(plaintext)
    } finally {
        password?.fill('\u0000')
    }

    private fun decodePlaintext(raw: String): DecodedAppConfigBackup {
        val root = parseRootObject(raw)
        val formatVersion = root.configFormatVersionOrNull()
        require(formatVersion != null) { "Not a NeriPlayer config backup" }
        require(formatVersion in 1..CONFIG_FORMAT_VERSION) {
            "Unsupported config backup format: $formatVersion"
        }

        val sections = root.detectSections()
        require(sections.hasAnySection()) { "Config backup has no restorable content" }

        val payload = try {
            configJson.decodeFromString(AppConfigBackup.serializer(), raw)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("Invalid NeriPlayer config backup")
        }
        return DecodedAppConfigBackup(payload = payload, sections = sections)
    }

    fun generateFileName(now: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        return "${CONFIG_FILE_PREFIX}_${formatter.format(Date(now))}$CONFIG_FILE_EXTENSION"
    }

    private fun parseRootObject(raw: String): JsonObject {
        val element = try {
            configJson.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("Invalid NeriPlayer config backup")
        }
        return element as? JsonObject
            ?: throw IllegalArgumentException("Not a NeriPlayer config backup")
    }

    private fun JsonObject.configFormatVersionOrNull(): Int? =
        if (primitive("kind")?.contentOrNull == CONFIG_KIND) primitive("formatVersion")?.intOrNull else null

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name] as? JsonPrimitive

    private fun JsonObject.detectSections(): AppConfigBackupSections {
        return AppConfigBackupSections(
            settings = "settings" in this,
            listenTogether = "listenTogether" in this,
            language = "language" in this,
            neteaseAuth = "neteaseAuth" in this,
            biliAuth = "biliAuth" in this,
            youTubeAuth = "youTubeAuth" in this,
            gitHubSync = "gitHubSync" in this,
            webDavSync = "webDavSync" in this,
            syncPreferences = "syncPreferences" in this
        )
    }

    private val LOGIN_STATE_SECTIONS = setOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")
}
