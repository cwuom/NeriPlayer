package moe.ouom.neriplayer.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountCompatibilityPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun accountsPersistAcrossSeparateInstrumentationProcesses() {
        when (InstrumentationRegistry.getArguments().getString("accountPhase", "roundtrip")) {
            "seed" -> seed()
            "restore" -> verifySaved()
            "clear" -> clear()
            "verifyCleared" -> verifyCleared()
            else -> {
                seed()
                verifySaved()
                clear()
                verifyCleared()
            }
        }
    }

    private fun seed() {
        if (AccountPreferencesOpener.existing(context, "youtube_auth_secure_prefs") == null) {
            val original = YouTubeAuthRepository(context)
            assertTrue(original.isAuthStoragePersistent)
            original.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "synthetic-old-keystore-session")))
        }
        listOf("netease_auth_secure_prefs", "bili_auth_secure_prefs", "youtube_auth_secure_prefs").forEach {
            val preferences = AccountPreferencesOpener.open(
                context = context,
                name = it,
                create = { throw java.security.ProviderException("synthetic unavailable keystore") },
                delete = { error("keystore failure must preserve the original encrypted file") }
            )
            assertTrue(AccountPreferencesOpener.isCompatibilityStorage(preferences))
        }
        assertTrue(YouTubeAuthRepository(context).getAuthOnce().cookies.isEmpty())
        assertTrue(NeteaseCookieRepository(context).saveCookies(mapOf("MUSIC_U" to NETEASE_SESSION)))
        BiliCookieRepository(context).saveCookies(mapOf("SESSDATA" to BILI_SESSION))
        YouTubeAuthRepository(context).saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to YOUTUBE_SESSION)))
    }

    private fun verifySaved() {
        val netease = NeteaseCookieRepository(context)
        val bili = BiliCookieRepository(context)
        val youtube = YouTubeAuthRepository(context)
        assertTrue(netease.requiresCompatibilityStorageWarning && netease.isAuthStoragePersistent)
        assertTrue(bili.requiresCompatibilityStorageWarning && bili.isAuthStoragePersistent)
        assertTrue(youtube.requiresCompatibilityStorageWarning && youtube.isAuthStoragePersistent)
        assertEquals(NETEASE_SESSION, netease.getCookiesOnce()["MUSIC_U"])
        assertEquals(BILI_SESSION, bili.getCookiesOnce()["SESSDATA"])
        assertEquals(YOUTUBE_SESSION, youtube.getAuthOnce().cookies["SAPISID"])
    }

    private fun clear() {
        NeteaseCookieRepository(context).clear()
        BiliCookieRepository(context).clear()
        YouTubeAuthRepository(context).clear()
    }

    private fun verifyCleared() {
        assertTrue(NeteaseCookieRepository(context).getCookiesOnce().isEmpty())
        assertTrue(BiliCookieRepository(context).getCookiesOnce().isEmpty())
        assertTrue(YouTubeAuthRepository(context).getAuthOnce().cookies.isEmpty())
    }

    private companion object {
        const val NETEASE_SESSION = "synthetic-persistence-netease"
        const val BILI_SESSION = "synthetic-persistence-bili"
        const val YOUTUBE_SESSION = "synthetic-persistence-youtube"
    }
}
