package moe.ouom.neriplayer.platform.subsonic.auth

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicCoverReference
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SubsonicCoverAccountsTest {
    private val source = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Source device",
        "https://example.test:4533/music/", "fixture-user")
    private val local = source.copy(id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc792", label = "This device")
    private val reference = requireNotNull(SubsonicCoverReference.parse(SubsonicCoverReference.create(source, "cover").toHttpUrl()))

    private fun accounts(profiles: List<SubsonicProfile>, loaded: Boolean = true): SubsonicAccounts {
        val preferences = mock(SharedPreferences::class.java)
        val json = JSONArray().apply {
            profiles.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("baseUrl", it.baseUrl)
                .put("username", it.username).put("enabled", it.enabled).put("revision", it.revision)) }
        }.toString()
        `when`(preferences.getString("profiles", "[]")).thenReturn(json)
        profiles.forEach { `when`(preferences.getString("password:${it.id}", null)).thenReturn("fixture-${it.id}") }
        return SubsonicAccounts(mock(Context::class.java)).also { store ->
            SubsonicAccounts::class.java.getDeclaredField("preferences\$delegate").apply { isAccessible = true }
                .set(store, lazyOf(preferences))
            if (loaded) SubsonicAccounts::class.java.getDeclaredField("snapshot").apply { isAccessible = true }.set(store, profiles)
        }
    }

    @Test fun `foreign local UUID resolves only to the matching enabled logical account`() {
        val unrelated = local.copy(id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc793", username = "other-user")
        val store = accounts(listOf(local, unrelated))
        assertEquals(local, store.coverCredentials(reference).profile)
        assertEquals("fixture-${local.id}", store.coverCredentials(reference).password)
    }

    @Test fun `the latest enabled duplicate is used without crossing accounts ports or subpaths`() {
        val latest = source.copy(id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc794", revision = 5)
        val disabled = source.copy(id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc795", enabled = false)
        val store = accounts(listOf(local, latest, disabled))
        assertEquals(latest, store.coverCredentials(reference).profile)
        for (candidate in listOf(local.copy(username = "other-user"), local.copy(enabled = false),
            local.copy(baseUrl = "https://example.test/music/"), local.copy(baseUrl = "https://example.test:4533/other/"))) {
            assertThrows(IllegalStateException::class.java) { accounts(listOf(candidate)).coverCredentials(reference) }
        }
        assertThrows(IllegalStateException::class.java) { accounts(emptyList()).coverCredentials(reference) }
    }

    @Test fun `request time account resolution can load the encrypted store lazily`() {
        val store = accounts(listOf(local), loaded = false)
        assertFalse(store.isLoaded)
        assertEquals(local, store.coverCredentials(reference).profile)
        assertEquals(listOf(local), store.profiles.value)
    }

    @Test fun `portable projection upgrades legacy covers using memory without changing unrelated references`() {
        val store = accounts(listOf(source))
        val legacy = ServerSongRef(source.id, "song").resourceUrl("cover", "cover / 中文")
        assertEquals(SubsonicCoverReference.create(source, "cover / 中文"), store.portableCoverUrl(legacy))
        for (raw in listOf(ServerSongRef(local.id, "song").resourceUrl("cover"),
            ServerSongRef(source.id, "song").resourceUrl(), "https://images.example.test/cover.jpg",
            "file:///local.jpg", legacy + "?unexpected=value", legacy + "#unexpected")) {
            assertEquals(raw, store.portableCoverUrl(raw))
        }
        assertNull(store.portableCoverUrl(null))
    }

    @Test fun `an unloaded projection leaves the original reference and performs no credential lookup`() {
        val store = accounts(listOf(source), loaded = false)
        val legacy = ServerSongRef(source.id, "song").resourceUrl("cover")
        assertEquals(legacy, store.portableCoverUrl(legacy))
        assertFalse(store.isLoaded)
    }
}
