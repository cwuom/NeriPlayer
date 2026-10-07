@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.subsonic.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Public configuration deliberately excludes authentication material. */
data class SubsonicProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String,
    val enabled: Boolean = true
)

/** Separate encrypted store, following the existing platform account repositories. */
class SubsonicAccounts(context: Context) {
    private val preferences by lazy {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context.applicationContext, "subsonic_accounts_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val state = MutableStateFlow<List<SubsonicProfile>>(emptyList())
    val profiles = state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(this@SubsonicAccounts) { publish() }
    }

    fun profile(id: String): SubsonicProfile? = synchronized(this) {
        readProfiles().firstOrNull { it.id == id && it.enabled }
    }

    fun password(id: String): String = preferences.getString("password:$id", null)
        ?: throw IllegalStateException("服务器账号需要重新登录")

    suspend fun save(profile: SubsonicProfile, password: String) = withContext(Dispatchers.IO) {
        synchronized(this@SubsonicAccounts) {
            val all = readProfiles().filterNot { it.id == profile.id } + profile
            check(preferences.edit().putString("profiles", serialize(all))
                .putString("password:${profile.id}", password).commit()) { "无法保存服务器账号" }
            state.value = all
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        synchronized(this@SubsonicAccounts) {
            val all = readProfiles().filterNot { it.id == id }
            check(preferences.edit().putString("profiles", serialize(all))
                .remove("password:$id").commit()) { "无法移除服务器账号" }
            state.value = all
        }
    }

    private fun publish() { state.value = readProfiles() }

    private fun readProfiles(): List<SubsonicProfile> {
        val data = JSONArray(preferences.getString("profiles", "[]"))
        return List(data.length()) { index ->
            val item = data.getJSONObject(index)
            SubsonicProfile(item.getString("id"), item.getString("label"),
                item.getString("baseUrl"), item.getString("username"), item.optBoolean("enabled", true))
        }
    }

    private fun serialize(profiles: List<SubsonicProfile>): String = JSONArray().apply {
        profiles.forEach { profile -> put(JSONObject().put("id", profile.id)
            .put("label", profile.label).put("baseUrl", profile.baseUrl)
            .put("username", profile.username).put("enabled", profile.enabled)) }
    }.toString()

    companion object {
        fun draft(label: String, address: String, username: String): SubsonicProfile {
            val url = address.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "请填写不含账号、查询参数或片段的服务器基础地址"
            }
            require(username.isNotBlank()) { "请填写用户名" }
            val normalized = url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
            return SubsonicProfile(UUID.randomUUID().toString(), label.trim().ifBlank { url.host },
                normalized.toString(), username.trim())
        }
    }
}
