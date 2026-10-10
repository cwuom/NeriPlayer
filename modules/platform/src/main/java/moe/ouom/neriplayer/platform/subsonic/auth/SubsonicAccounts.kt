@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.subsonic.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import java.util.UUID

/**
 * Public configuration excludes the password. Editing an existing instance keeps its ID and
 * increments revision so requests and caches cannot publish results from the previous settings.
 */
data class SubsonicProfile(
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String,
    val enabled: Boolean = true,
    val revision: Long = 0L
)

class SubsonicCredentials(val profile: SubsonicProfile, val password: String) {
    override fun toString(): String = "SubsonicCredentials(profileId=${profile.id})"
}

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
    @Volatile private var snapshot: List<SubsonicProfile>? = null
    val isLoaded: Boolean get() = snapshot != null

    suspend fun load() = withContext(Dispatchers.IO) {
        synchronized(this@SubsonicAccounts) {
            if (snapshot == null) publish(readProfiles())
        }
    }

    /** Memory only: safe for composition and lyric first-frame reads. */
    fun profile(id: String): SubsonicProfile? = snapshot?.firstOrNull { it.id == id && it.enabled }

    /** Blocking encrypted storage access; only call from an IO worker/interceptor. */
    fun credentials(id: String): SubsonicCredentials = synchronized(this) {
        if (snapshot == null) publish(readProfiles())
        val profile = profile(id) ?: throw IllegalStateException("音乐服务器账号不可用")
        SubsonicCredentials(profile, preferences.getString("password:$id", null)
            ?: throw IllegalStateException("服务器账号需要重新登录"))
    }

    suspend fun save(profile: SubsonicProfile, password: String, expectedRevision: Long? = null) = withContext(Dispatchers.IO) {
        synchronized(this@SubsonicAccounts) {
            val current = readProfiles()
            if (expectedRevision != null) {
                if (current.none { it.id == profile.id && it.revision == expectedRevision && it.username == profile.username }) {
                    throw SubsonicException(-1, "Configuration changed", SubsonicFailureKind.CONFIG_CHANGED)
                }
            }
            val all = current.filterNot { it.id == profile.id } + profile
            check(preferences.commitEdit {
                putString("profiles", serialize(all))
                putString("password:${profile.id}", password)
            }) { "无法保存服务器账号" }
            publish(all)
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        synchronized(this@SubsonicAccounts) {
            val all = readProfiles().filterNot { it.id == id }
            check(preferences.commitEdit {
                putString("profiles", serialize(all))
                remove("password:$id")
            }) { "无法移除服务器账号" }
            publish(all)
        }
    }

    private fun publish(profiles: List<SubsonicProfile>) {
        snapshot = profiles
        state.value = profiles
    }

    private fun readProfiles(): List<SubsonicProfile> {
        val data = JSONArray(preferences.getString("profiles", "[]"))
        return List(data.length()) { index ->
            val item = data.getJSONObject(index)
            SubsonicProfile(item.getString("id"), item.getString("label"),
                item.getString("baseUrl"), item.getString("username"), item.optBoolean("enabled", true), item.optLong("revision", 0L))
        }
    }

    private fun serialize(profiles: List<SubsonicProfile>): String = JSONArray().apply {
        profiles.forEach { profile -> put(JSONObject().put("id", profile.id)
            .put("label", profile.label).put("baseUrl", profile.baseUrl)
            .put("username", profile.username).put("enabled", profile.enabled)
            .put("revision", profile.revision)) }
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

@SuppressLint("UseKtx")
private inline fun SharedPreferences.commitEdit(action: SharedPreferences.Editor.() -> Unit): Boolean {
    // KTX edit(commit = true) hides commit failure; account state must only publish after success.
    val editor = edit()
    editor.action()
    return editor.commit()
}
