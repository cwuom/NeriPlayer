package moe.ouom.neriplayer.core.player.dlna.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists per-device DLNA playback options (FLAC pass-through vs WAV fallback,
 * per-device WAV ID3v2.3 embedding, optional strict lossless mode) and manually added renderer URLs.
 */
class DlnaPreferencesStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "neri_dlna_preferences"
        private const val KEY_DEVICE_OPTIONS = "device_options_json"
        private const val KEY_MANUAL_LOCATIONS = "manual_locations_json"
        private const val KEY_SCHEMA_VERSION = "dlna_prefs_schema_version"
        internal const val CURRENT_SCHEMA_VERSION = 2
    }

    private val prefs: SharedPreferences? =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSchemaIfNeeded()
    }

    private fun migrateSchemaIfNeeded() {
        val store = prefs ?: return
        val version = store.getInt(KEY_SCHEMA_VERSION, 1)
        if (version >= CURRENT_SCHEMA_VERSION) return

        // Migrate v1 -> v2: adaptive direct streaming is the new default (strictLosslessMode = false)
        // so existing installations automatically adopt adaptive mode for Bilibili / YouTube / MP3.
        val raw = store.getString(KEY_DEVICE_OPTIONS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(raw)
                val migrated = JSONObject()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val udn = keys.next()
                    val obj = root.optJSONObject(udn) ?: continue
                    migrated.put(
                        udn,
                        JSONObject().apply {
                            put("preferOriginalFlac", obj.optBoolean("preferOriginalFlac", true))
                            put("wavFallbackEnabled", obj.optBoolean("wavFallbackEnabled", true))
                            put("wavEmbedId3Enabled", obj.optBoolean("wavEmbedId3Enabled", false))
                            put("strictLosslessMode", false)
                        }
                    )
                }
                store.edit {
                    putString(KEY_DEVICE_OPTIONS, migrated.toString())
                    putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
                }
                return
            }
        }
        store.edit { putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION) }
    }

    fun loadAllDeviceOptions(): Map<String, DlnaDeviceOptions> {
        val store = prefs ?: return emptyMap()
        migrateSchemaIfNeeded()
        val raw = store.getString(KEY_DEVICE_OPTIONS, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val result = LinkedHashMap<String, DlnaDeviceOptions>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val udn = keys.next()
                val obj = root.optJSONObject(udn) ?: continue
                result[udn] = DlnaDeviceOptions(
                    preferOriginalFlac = obj.optBoolean("preferOriginalFlac", true),
                    wavFallbackEnabled = obj.optBoolean("wavFallbackEnabled", true),
                    wavEmbedId3Enabled = obj.optBoolean("wavEmbedId3Enabled", false),
                    strictLosslessMode = obj.optBoolean("strictLosslessMode", false)
                )
            }
            result
        }.getOrDefault(emptyMap())
    }

    fun saveDeviceOptions(udn: String, options: DlnaDeviceOptions) {
        val current = loadAllDeviceOptions().toMutableMap()
        current[udn] = options
        val root = JSONObject()
        current.forEach { (key, value) ->
            root.put(
                key,
                JSONObject().apply {
                    put("preferOriginalFlac", value.preferOriginalFlac)
                    put("wavFallbackEnabled", value.wavFallbackEnabled)
                    put("wavEmbedId3Enabled", value.wavEmbedId3Enabled)
                    put("strictLosslessMode", value.strictLosslessMode)
                }
            )
        }
        prefs?.edit {
            putString(KEY_DEVICE_OPTIONS, root.toString())
            putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        }
    }

    fun defaultOptionsForDevice(device: DlnaDevice): DlnaDeviceOptions {
        return DlnaDeviceOptions(
            preferOriginalFlac = true,
            wavFallbackEnabled = true,
            wavEmbedId3Enabled = device.isLikelyShanlingEm7,
            strictLosslessMode = false
        )
    }

    fun loadManualDevices(): List<DlnaDevice> {
        val raw = prefs?.getString(KEY_MANUAL_LOCATIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val location = obj.optString("location").takeIf { it.isNotBlank() } ?: continue
                    val udn = obj.optString("udn").ifBlank { location }
                    add(
                        DlnaDevice(
                            udn = udn,
                            friendlyName = obj.optString("friendlyName").ifBlank { location },
                            location = location,
                            host = obj.optString("host"),
                            manufacturer = obj.optString("manufacturer"),
                            modelName = obj.optString("modelName"),
                            avTransportControlUrl = obj.optString("avTransportControlUrl"),
                            avTransportEventSubUrl = obj.optString("avTransportEventSubUrl"),
                            renderingControlUrl = obj.optString("renderingControlUrl"),
                            renderingEventSubUrl = obj.optString("renderingEventSubUrl"),
                            connectionManagerUrl = obj.optString("connectionManagerUrl"),
                            sinkProtocolInfo = obj.optString("sinkProtocolInfo"),
                            supportsFlac = obj.optBoolean("supportsFlac", true),
                            supportsWav = obj.optBoolean("supportsWav", true),
                            supportsSetNextUri = obj.optBoolean("supportsSetNextUri", false),
                            online = false,
                            isManual = true
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveManualDevice(device: DlnaDevice) {
        val list = loadManualDevices().filterNot { it.udn == device.udn || it.location == device.location }.toMutableList()
        list.add(device.copy(isManual = true))
        writeManualDevices(list)
    }

    fun removeManualDevice(udn: String) {
        val list = loadManualDevices().filterNot { it.udn == udn }
        writeManualDevices(list)
    }

    private fun writeManualDevices(devices: List<DlnaDevice>) {
        val array = JSONArray()
        devices.forEach { d ->
            array.put(
                JSONObject().apply {
                    put("udn", d.udn)
                    put("friendlyName", d.friendlyName)
                    put("location", d.location)
                    put("host", d.host)
                    put("manufacturer", d.manufacturer)
                    put("modelName", d.modelName)
                    put("avTransportControlUrl", d.avTransportControlUrl)
                    put("avTransportEventSubUrl", d.avTransportEventSubUrl)
                    put("renderingControlUrl", d.renderingControlUrl)
                    put("renderingEventSubUrl", d.renderingEventSubUrl)
                    put("connectionManagerUrl", d.connectionManagerUrl)
                    put("sinkProtocolInfo", d.sinkProtocolInfo)
                    put("supportsFlac", d.supportsFlac)
                    put("supportsWav", d.supportsWav)
                    put("supportsSetNextUri", d.supportsSetNextUri)
                }
            )
        }
        prefs?.edit { putString(KEY_MANUAL_LOCATIONS, array.toString()) }
    }
}
