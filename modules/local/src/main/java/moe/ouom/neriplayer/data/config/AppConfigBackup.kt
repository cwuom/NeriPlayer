package moe.ouom.neriplayer.data.config

import moe.ouom.neriplayer.data.model.config.CONFIG_FORMAT_VERSION
import moe.ouom.neriplayer.data.model.config.CONFIG_KIND

import moe.ouom.neriplayer.data.model.config.AppConfigBackup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    fun decode(raw: String): AppConfigBackup {
        return decodeForImport(raw).payload
    }

    internal fun decodeForImport(raw: String): DecodedAppConfigBackup {
        val root = parseRootObject(raw)
        val formatVersion = root.configFormatVersionOrNull()
        require(formatVersion != null) { "Not a NeriPlayer config backup" }
        require(formatVersion in 1..CONFIG_FORMAT_VERSION) {
            "Unsupported config backup format: $formatVersion"
        }

        val sections = root.detectSections()
        require(sections.hasAnySection()) { "Config backup has no restorable content" }

        val payload = configJson.decodeFromString(AppConfigBackup.serializer(), raw)
        return DecodedAppConfigBackup(payload = payload, sections = sections)
    }

    fun generateFileName(now: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        return "${CONFIG_FILE_PREFIX}_${formatter.format(Date(now))}$CONFIG_FILE_EXTENSION"
    }

    private fun parseRootObject(raw: String): JsonObject {
        return configJson.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("Not a NeriPlayer config backup")
    }

    private fun JsonObject.configFormatVersionOrNull(): Int? =
        if (primitive("kind")?.contentOrNull == CONFIG_KIND) primitive("formatVersion")?.intOrNull else null

    private fun JsonObject.primitive(name: String): JsonPrimitive? = this[name]?.jsonPrimitive

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
}
