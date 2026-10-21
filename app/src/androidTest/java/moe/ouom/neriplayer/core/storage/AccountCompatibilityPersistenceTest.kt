package moe.ouom.neriplayer.core.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
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
    private val phase = InstrumentationRegistry.getArguments().getString("accountPhase", "roundtrip")
    // 跨进程阶段复用专用路径，避免退出墓碑遮挡其它用例的旧凭据迁移
    private val fixtureName = if (phase == "roundtrip") "$FIXTURE_NAME-${UUID.randomUUID()}" else FIXTURE_NAME
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), fixtureName)
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("$fixtureName-$name", mode)
        override fun deleteSharedPreferences(name: String): Boolean =
            super.deleteSharedPreferences("$fixtureName-$name")
    }

    @Test
    fun accountsPersistAcrossSeparateInstrumentationProcesses() {
        when (phase) {
            "seed" -> seed()
            "restore" -> verifySaved()
            "clear" -> clear()
            "verifyCleared" -> {
                verifyCleared()
                removeFixture()
            }
            else -> try {
                seed()
                verifySaved()
                clear()
                verifyCleared()
            } finally {
                removeFixture()
            }
        }
    }

    private fun seed() {
        if (AccountPreferencesOpener.existing(context, "youtube_auth_secure_prefs") == null) {
            val original = YouTubeAuthRepository(context)
            assertTrue(original.isAuthStoragePersistent)
            original.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to "synthetic-old-keystore-session")))
        }
        AUTH_PREFERENCES.forEach {
            val preferences = AccountPreferencesOpener.open(
                context = context,
                name = it,
                create = { throw java.security.ProviderException("synthetic unavailable keystore") },
                delete = { error("keystore failure must preserve the original encrypted file") }
            )
            assertTrue(AccountPreferencesOpener.isCompatibilityStorage(preferences))
            // 只有实际提交过的兼容存储才应遮挡健康主存储
            assertTrue(preferences.edit().putBoolean("synthetic_compatibility_seed", true).commit())
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

    private fun removeFixture() {
        AUTH_PREFERENCES.forEach(context::deleteSharedPreferences)
        context.deleteSharedPreferences("youtube_auth_secure_prefs_recovery")
        val directory = context.noBackupFilesDir
        check(!directory.exists() || directory.deleteRecursively())
    }

    private companion object {
        const val FIXTURE_NAME = "account-compatibility-persistence-test"
        val AUTH_PREFERENCES = listOf("netease_auth_secure_prefs", "bili_auth_secure_prefs", "youtube_auth_secure_prefs")
        const val NETEASE_SESSION = "synthetic-persistence-netease"
        const val BILI_SESSION = "synthetic-persistence-bili"
        const val YOUTUBE_SESSION = "synthetic-persistence-youtube"
    }
}
