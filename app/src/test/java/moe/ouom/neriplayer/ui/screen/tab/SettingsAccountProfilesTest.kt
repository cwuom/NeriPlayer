package moe.ouom.neriplayer.ui.screen.tab

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAccountProfile
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthorizationSnapshot
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsYouTubeAccountAuthorizationSnapshot
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.loadBiliAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.loadSettingsAccountProfileSafely
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.loadYouTubeAccountProfileIfCurrent
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.parseNeteaseAccountProfile
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.settingsAccountProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SettingsAccountProfilesTest {
    @Test
    fun `youtube snapshot copies authorization and includes selected account and saved generation`() {
        val cookies = linkedMapOf("SAPISID" to "synthetic-first", "SID" to "synthetic-first")
        val first = YouTubeAuthBundle(cookies = cookies, xGoogAuthUser = "1", savedAt = 12L)
        val snapshot = SettingsYouTubeAccountAuthorizationSnapshot(first)
        cookies["SAPISID"] = "synthetic-second"
        assertEquals(false, snapshot.matches(first))
        val copy = first.copy(cookies = mapOf("SAPISID" to "synthetic-first", "SID" to "synthetic-first"))
        assertEquals(true, snapshot.matches(copy))
        assertEquals(false, snapshot.matches(copy.copy(xGoogAuthUser = "2")))
        assertEquals(false, snapshot.matches(copy.copy(savedAt = 13L)))
        assertEquals("SettingsYouTubeAccountAuthorizationSnapshot", snapshot.toString())
    }

    @Test
    fun `youtube stale authorization and disabled feature never start the profile request`() = runTest {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic-first; SID=synthetic-first", savedAt = 12L)
        val snapshot = SettingsYouTubeAccountAuthorizationSnapshot(auth)
        var calls = 0
        val load = suspend { calls++; YouTubeAccountProfile("Fixture", null) }
        assertNull(loadYouTubeAccountProfileIfCurrent(snapshot, { auth.copy(xGoogAuthUser = "2") }, { true }, load))
        assertNull(loadYouTubeAccountProfileIfCurrent(snapshot, { auth }, { false }, load))
        assertEquals(0, calls)
    }

    @Test
    fun `youtube account switch or feature disable while loading discards the old profile`() = runTest {
        val initial = YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic-first; SID=synthetic-first", savedAt = 12L)
        val snapshot = SettingsYouTubeAccountAuthorizationSnapshot(initial)
        var current = initial
        assertNull(loadYouTubeAccountProfileIfCurrent(snapshot, { current }, { true }) {
            current = initial.copy(savedAt = 13L)
            YouTubeAccountProfile("Old account", "https://example.invalid/old.png")
        })
        var enabled = true
        assertNull(loadYouTubeAccountProfileIfCurrent(snapshot, { initial }, { enabled }) {
            enabled = false
            YouTubeAccountProfile("Old account", null)
        })
    }

    @Test
    fun `youtube current account profile maps nickname and avatar without changing authorization`() = runTest {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic-first; SID=synthetic-first", savedAt = 12L)
        val profile = loadYouTubeAccountProfileIfCurrent(SettingsYouTubeAccountAuthorizationSnapshot(auth), { auth }, { true }) {
            YouTubeAccountProfile(" Fixture ", "//example.invalid/a.png")
        }
        assertEquals(SettingsAccountProfile("Fixture", "https://example.invalid/a.png"), profile)
        assertEquals(12L, auth.savedAt)
    }

    @Test
    fun `youtube cancellation propagates and does not turn into a profile`() = runTest {
        val auth = YouTubeAuthBundle(cookieHeader = "SAPISID=synthetic; SID=synthetic", savedAt = 12L)
        val cancelled = CancellationException("fixture cancelled")
        try {
            loadYouTubeAccountProfileIfCurrent(SettingsYouTubeAccountAuthorizationSnapshot(auth), { auth }, { true }) {
                throw cancelled
            }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test
    fun `netease profile maps only nickname and avatar from a successful account response`() {
        val profile = parseNeteaseAccountProfile(
            """{"code":200,"profile":{"userId":42,"nickname":"  Fixture account  ","avatarUrl":"//example.invalid/avatar.png"},"account":{"id":42}}"""
        )
        assertEquals(SettingsAccountProfile("Fixture account", "https://example.invalid/avatar.png"), profile)
    }

    @Test
    fun `missing failed and incomplete account responses do not fabricate a profile`() {
        for (raw in listOf(
            """{"code":301,"profile":{"userId":42,"nickname":"Old account"}}""",
            """{"code":200}""",
            """{"code":200,"profile":{"userId":0,"nickname":"Unknown account"}}""",
            """{"code":200,"profile":{"userId":42,"nickname":" ","avatarUrl":"https://example.invalid/a.png"}}"""
        )) {
            assertNull(parseNeteaseAccountProfile(raw))
        }
    }

    @Test
    fun `invalid avatars retain a real nickname and use the avatar fallback`() {
        assertEquals(SettingsAccountProfile("Fixture", null), settingsAccountProfile("Fixture", "null"))
        assertEquals(SettingsAccountProfile("Fixture", null), settingsAccountProfile("Fixture", "file:///private/avatar.png"))
        assertEquals(
            SettingsAccountProfile("Fixture", "https://example.invalid/a.png"),
            settingsAccountProfile("Fixture", "http://example.invalid/a.png")
        )
    }

    @Test
    fun `missing or invalid bilibili user ID never initializes a remote client`() = runTest {
        assertNull(loadBiliAccountProfile(SettingsAccountAuthorizationSnapshot(emptyMap())))
        assertNull(loadBiliAccountProfile(SettingsAccountAuthorizationSnapshot(mapOf("DedeUserID" to "0"))))
        assertNull(loadBiliAccountProfile(SettingsAccountAuthorizationSnapshot(mapOf("DedeUserID" to "-1"))))
    }

    @Test
    fun `account identities include authorization changes and never expose their material`() {
        val first = SettingsAccountAuthorizationSnapshot(mapOf("MUSIC_U" to "synthetic-a"))
        val repeated = SettingsAccountAuthorizationSnapshot(mapOf("MUSIC_U" to "synthetic-a"))
        val second = SettingsAccountAuthorizationSnapshot(mapOf("MUSIC_U" to "synthetic-b"))
        assertEquals(first, repeated)
        assertEquals(false, first == second)
        assertEquals(false, first.matches(mapOf("MUSIC_U" to "synthetic-b")))
        assertEquals("SettingsAccountAuthorizationSnapshot", first.toString())
    }

    @Test
    fun `profile clients keep separate authorization snapshots without a shared session observer`() {
        val first = SettingsAccountAuthorizationSnapshot(mapOf("MUSIC_U" to "synthetic-a"))
        val second = SettingsAccountAuthorizationSnapshot(mapOf("MUSIC_U" to "synthetic-b"))
        val firstClient = first.newNeteaseProfileClient()
        val secondClient = second.newNeteaseProfileClient()
        try {
            assertEquals("synthetic-a", firstClient.getNeteaseRequestCookies()["MUSIC_U"])
            assertEquals("synthetic-b", secondClient.getNeteaseRequestCookies()["MUSIC_U"])
            assertEquals("synthetic-a", firstClient.getNeteaseRequestCookies()["MUSIC_U"])
        } finally {
            firstClient.evictConnections()
            secondClient.evictConnections()
        }
    }

    @Test
    fun `network and malformed response failures return no profile`() = runTest {
        assertNull(loadSettingsAccountProfileSafely { throw IOException("fixture unavailable") })
        assertNull(loadSettingsAccountProfileSafely { parseNeteaseAccountProfile("invalid fixture JSON") })
        val profile = SettingsAccountProfile("Fixture", null)
        assertSame(profile, loadSettingsAccountProfileSafely { profile })
    }

    @Test
    fun `profile cancellation propagates instead of being treated as an account failure`() = runTest {
        val cancellation = CancellationException("fixture cancellation")
        try {
            loadSettingsAccountProfileSafely { throw cancellation }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
