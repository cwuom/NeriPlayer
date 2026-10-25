package moe.ouom.neriplayer.data.config

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.model.config.AppConfigImportResult
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.config.ListenTogetherConfigSnapshot
import moe.ouom.neriplayer.data.model.config.SyncPreferencesConfigSnapshot
import moe.ouom.neriplayer.data.model.config.WebDavSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.preferences.SyncPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedConstruction
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigFileManagerSectionImportTest {
    private val context = mock(Context::class.java)
    private val resolver = mock(ContentResolver::class.java)
    private val uri = mock(Uri::class.java)
    private val languagePreferences = mock(SharedPreferences::class.java)
    private val languageEditor = mock(SharedPreferences.Editor::class.java)
    private val listenTogether = mock(ListenTogetherPreferences::class.java)
    private val netease = mock(NeteaseCookieRepository::class.java)
    private val bili = mock(BiliCookieRepository::class.java)
    private val youtube = mock(YouTubeAuthRepository::class.java)
    private val originalLocale = Locale.getDefault()
    private val savedAuth = mutableListOf<YouTubeAuthBundle>()
    private val scheduled = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private var github = GitHubSyncConfigSnapshot()
    private var webDav = WebDavSyncConfigSnapshot()
    private var syncMutations = 0
    private var input = ""
    private lateinit var constructions: List<MockedConstruction<*>>
    private lateinit var syncPreferences: MockedConstruction<SyncPreferences>

    @Before
    fun setUp() {
        doReturn(context).`when`(context).applicationContext
        doReturn(resolver).`when`(context).contentResolver
        doReturn(languagePreferences).`when`(context).getSharedPreferences("language_settings", Context.MODE_PRIVATE)
        doReturn("").`when`(languagePreferences).getString("selected_language", "")
        doReturn(languageEditor).`when`(languagePreferences).edit()
        doReturn(languageEditor).`when`(languageEditor).putString(anyString(), anyString())
        doReturn(true).`when`(netease).isAuthStoragePersistent
        doReturn(true).`when`(bili).isAuthStoragePersistent
        doReturn(true).`when`(youtube).isAuthStoragePersistent
        doAnswer { ByteArrayInputStream(input.toByteArray()) }.`when`(resolver).openInputStream(uri)
        doAnswer {
            val bundle = it.getArgument<YouTubeAuthBundle>(0)
            if (youtube.requiresCompatibilityStorageWarning &&
                !it.getArgument<Boolean>(1) && bundle.hasSavedAuthMaterial()
            ) {
                throw IOException("compatibility storage was not confirmed")
            }
            savedAuth += bundle
            Unit
        }.`when`(youtube).saveAuth(any(YouTubeAuthBundle::class.java) ?: YouTubeAuthBundle(), anyBoolean())
        syncPreferences = mockConstruction(SyncPreferences::class.java)
        constructions = listOf(
            syncPreferences,
            mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
                doReturn(true).`when`(storage).isPersistent
                doAnswer { github.token.isNotBlank() && github.repoOwner.isNotBlank() && github.repoName.isNotBlank() }
                    .`when`(storage).isConfigured()
                doAnswer { github.autoSyncEnabled }.`when`(storage).isAutoSyncEnabled()
                doAnswer { github }.`when`(storage).snapshot()
                doAnswer { github = it.getArgument(0); Unit }
                    .`when`(storage).restore(any(GitHubSyncConfigSnapshot::class.java) ?: GitHubSyncConfigSnapshot())
                doAnswer { ++syncMutations; 1L }.`when`(storage).markSyncMutation()
            },
            mockConstruction(WebDavStorage::class.java) { storage, _ ->
                doReturn(true).`when`(storage).isPersistent
                doAnswer { webDav.serverUrl.isNotBlank() && webDav.username.isNotBlank() && webDav.password.isNotBlank() }
                    .`when`(storage).isConfigured()
                doAnswer { webDav.autoSyncEnabled }.`when`(storage).isAutoSyncEnabled()
                doAnswer { webDav }.`when`(storage).snapshot()
                doAnswer { webDav = it.getArgument(0); Unit }
                    .`when`(storage).restore(any(WebDavSyncConfigSnapshot::class.java) ?: WebDavSyncConfigSnapshot())
            },
            mockConstruction(SyncWorkScheduler::class.java) { scheduler, construction ->
                val workName = construction.arguments()[1] as String
                doAnswer { scheduled += workName; Unit }.`when`(scheduler).schedulePeriodic()
                doAnswer { cancelled += workName; Unit }.`when`(scheduler).cancel()
            }
        )
    }

    @After
    fun tearDown() {
        constructions.forEach { it.close() }
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `english language and a youtube login without origin or timestamp restore with defaults`() = runTest {
        val before = System.currentTimeMillis()
        val result = importSections(
            """"language":{"code":" en "},"youTubeAuth":{"cookies":{"SAPISID":"sapisid","NID":"nid"},""" +
                """"authorization":"SAPISIDHASH 1_abc","xGoogAuthUser":"1"}"""
        )
        val after = System.currentTimeMillis()

        assertEquals(AppConfigImportResult(0, 0, 1, 0, emptyList(), requiresActivityRecreate = true), result)
        val bundle = savedAuth.single()
        assertEquals(
            YouTubeAuthBundle(
                cookieHeader = "SAPISID=sapisid",
                cookies = mapOf("SAPISID" to "sapisid"),
                authorization = "SAPISIDHASH 1_abc",
                xGoogAuthUser = "1",
                origin = "https://music.youtube.com",
                savedAt = bundle.savedAt
            ),
            bundle
        )
        assertTrue(bundle.savedAt in before..after)
        verify(youtube).saveAuth(bundle, false)
        verify(languageEditor).putString("selected_language", "en")
        assertEquals(Locale.forLanguageTag("en"), Locale.getDefault())
    }

    @Test
    fun `reimporting the selected chinese language keeps the activity and the youtube origin`() = runTest {
        doReturn("zh").`when`(languagePreferences).getString("selected_language", "")

        val result = importSections(
            """"language":{"code":"zh"},"youTubeAuth":{"cookieHeader":"SID=sid; NID=nid",""" +
                """"origin":"https://www.youtube.com","userAgent":"UA","savedAt":1234}"""
        )

        assertEquals(AppConfigImportResult(0, 0, 1, 0, emptyList(), requiresActivityRecreate = false), result)
        assertEquals(
            listOf(
                YouTubeAuthBundle(
                    cookieHeader = "SID=sid",
                    cookies = mapOf("SID" to "sid"),
                    origin = "https://www.youtube.com",
                    userAgent = "UA",
                    savedAt = 1234L
                )
            ),
            savedAuth
        )
        verify(languageEditor).putString("selected_language", "zh")
        assertEquals(Locale.forLanguageTag("zh"), Locale.getDefault())
    }

    @Test
    fun `unknown languages are ignored and empty auth sections clear saved logins`() = runTest {
        val result = importSections(""""language":{"code":"fr"},"neteaseAuth":{},"biliAuth":{},"youTubeAuth":{}""")

        assertEquals(AppConfigImportResult(0, 0, 0, 0, emptyList(), requiresActivityRecreate = false), result)
        verify(netease).clear()
        verify(bili).clear()
        verify(youtube).clear()
        assertEquals(emptyList<YouTubeAuthBundle>(), savedAuth)
        verify(languagePreferences, never()).edit()
        assertEquals(originalLocale, Locale.getDefault())
    }

    @Test
    fun `auto synced github target is scheduled while an empty webdav section is cancelled`() = runTest {
        val result = importSections(
            """"gitHubSync":{"token":"token","repoOwner":"owner","repoName":"repo","autoSyncEnabled":true},""" +
                """"webDavSync":{},"syncPreferences":{"playHistoryUpdateMode":"BATCHED"}"""
        )

        assertEquals(AppConfigImportResult(0, 0, 0, 2, emptyList(), requiresActivityRecreate = false), result)
        assertEquals(GitHubSyncConfigSnapshot(token = "token", repoOwner = "owner", repoName = "repo"), github)
        assertEquals(WebDavSyncConfigSnapshot(), webDav)
        assertEquals(listOf("github_sync_work"), scheduled)
        assertEquals(listOf("webdav_sync_work"), cancelled)
        assertEquals(1, syncMutations)
        verify(syncPreferences.constructed().single()).restore(SyncPreferencesConfigSnapshot("BATCHED"), "")
    }

    @Test
    fun `manual github sync is cancelled while a configured webdav target is scheduled`() = runTest {
        val result = importSections(
            """"gitHubSync":{"token":"token","repoOwner":"owner","repoName":"repo","autoSyncEnabled":false},""" +
                """"webDavSync":{"serverUrl":"https://dav.example","username":"user","password":"secret"}"""
        )

        assertEquals(AppConfigImportResult(0, 0, 0, 2, emptyList(), requiresActivityRecreate = false), result)
        assertEquals(
            WebDavSyncConfigSnapshot(serverUrl = "https://dav.example", username = "user", password = "secret"),
            webDav
        )
        assertEquals(listOf("webdav_sync_work"), scheduled)
        assertEquals(listOf("github_sync_work"), cancelled)
        assertEquals(1, syncMutations)
        verifyNoInteractions(syncPreferences.constructed().single())
    }

    @Test
    fun `a legacy github play history mode restores sync preferences without their own section`() = runTest {
        val result = importSections(
            """"gitHubSync":{"token":"token","repoOwner":"owner","repoName":"repo","playHistoryUpdateMode":"IMMEDIATE"}"""
        )

        assertEquals(1, result.restoredSyncCount)
        verify(syncPreferences.constructed().single()).restore(SyncPreferencesConfigSnapshot(), "IMMEDIATE")
    }

    @Test
    fun `cookie logins keep their timestamps and a rejected netease login becomes a warning`() = runTest {
        doReturn("netease rejected").`when`(context).getString(CoreCommonR.string.config_import_warning_netease_cookie)
        doReturn(false).`when`(netease).saveCookies(mapOf("MUSIC_U" to "bad"), 77L)

        val result = importSections(
            """"neteaseAuth":{"cookies":{"MUSIC_U":"bad"},"savedAt":77},""" +
                """"biliAuth":{"cookies":{"SESSDATA":"session"},"savedAt":88}"""
        )

        assertEquals(
            AppConfigImportResult(0, 0, 1, 0, listOf("netease rejected"), requiresActivityRecreate = false),
            result
        )
        verify(bili).saveCookies(mapOf("SESSDATA" to "session"), 88L)
        verifyNoInteractions(youtube)
    }

    @Test
    fun `accepted cookie logins without a timestamp are saved at import time`() = runTest {
        val savedAt = mutableListOf<Long>()
        doAnswer { savedAt += it.getArgument<Long>(1); true }
            .`when`(netease).saveCookies(anyMap(), anyLong())
        doAnswer { savedAt += it.getArgument<Long>(1); Unit }
            .`when`(bili).saveCookies(anyMap(), anyLong())
        val before = System.currentTimeMillis()

        val result = importSections(
            """"neteaseAuth":{"cookies":{"MUSIC_U":"good"}},"biliAuth":{"cookies":{"SESSDATA":"session"}}"""
        )

        assertEquals(AppConfigImportResult(0, 0, 2, 0, emptyList(), requiresActivityRecreate = false), result)
        assertEquals(2, savedAt.size)
        assertTrue(savedAt.toString(), savedAt.all { it in before..System.currentTimeMillis() })
    }

    @Test
    fun `direct import cannot silently save netease auth in compatibility storage`() = runTest {
        doReturn(true).`when`(netease).requiresCompatibilityStorageWarning

        val result = importResult(""""listenTogether":{},"neteaseAuth":{"cookies":{"MUSIC_U":"session"}}""")

        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        verify(netease, never()).saveCookies(anyMap(), anyLong())
    }

    @Test
    fun `direct import cannot silently save bilibili auth in compatibility storage`() = runTest {
        doReturn(true).`when`(bili).requiresCompatibilityStorageWarning

        val result = importResult(""""listenTogether":{},"biliAuth":{"cookies":{"SESSDATA":"session"}}""")

        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        verify(bili, never()).saveCookies(anyMap(), anyLong())
    }

    @Test
    fun `direct import cannot silently save youtube auth in compatibility storage`() = runTest {
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning

        val result = importResult(""""listenTogether":{},"youTubeAuth":{"cookieHeader":"SID=session"}""")

        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `volatile auth storage blocks the entire import before any restoration`() = runTest {
        doReturn(false).`when`(youtube).isAuthStoragePersistent

        val result = importResult(""""listenTogether":{},"youTubeAuth":{"cookieHeader":"SID=session"}""")

        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `declining compatibility storage leaves every imported section untouched`() = runTest {
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning
        var confirmations = 0

        val result = importResult(""""listenTogether":{},"youTubeAuth":{"cookieHeader":"SID=session"}""") {
            confirmations++
            false
        }

        assertEquals(1, confirmations)
        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `confirmation resumes the original parsed backup without reopening its uri`() = runTest {
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning
        val confirmation = CompletableDeferred<Boolean>()
        val importing = async {
            importResult(""""youTubeAuth":{"cookieHeader":"SID=original"}""") { confirmation.await() }
        }
        runCurrent()
        assertFalse(importing.isCompleted)
        assertTrue(savedAuth.isEmpty())
        input = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"youTubeAuth":{"cookieHeader":"SID=replaced"}}"""

        confirmation.complete(true)

        assertEquals(1, importing.await().getOrThrow().restoredAuthCount)
        assertEquals("SID=original", savedAuth.single().cookieHeader)
        verify(youtube).saveAuth(savedAuth.single(), true)
        verify(resolver, times(1)).openInputStream(uri)
    }

    @Test
    fun `storage becoming unavailable during confirmation still blocks all restoration`() = runTest {
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning

        val result = importResult(""""listenTogether":{},"youTubeAuth":{"cookieHeader":"SID=session"}""") {
            doReturn(false).`when`(youtube).isAuthStoragePersistent
            true
        }

        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `empty compatibility auth sections clear logins without requesting confirmation`() = runTest {
        doReturn(true).`when`(netease).requiresCompatibilityStorageWarning
        doReturn(true).`when`(bili).requiresCompatibilityStorageWarning
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning

        val result = importResult(""""neteaseAuth":{},"biliAuth":{},"youTubeAuth":{}""") {
            error("empty auth must not request confirmation")
        }.getOrThrow()

        assertEquals(0, result.restoredAuthCount)
        verify(netease).clear()
        verify(bili).clear()
        verify(youtube).clear()
    }

    @Test
    fun `empty volatile auth sections block clearing before other sections change`() = runTest {
        doReturn(false).`when`(youtube).isAuthStoragePersistent

        val result = importResult(""""listenTogether":{},"youTubeAuth":{}""")

        assertFalse(result.isSuccess)
        verifyNoInteractions(listenTogether)
        verify(youtube, never()).clear()
    }

    @Test
    fun `backup without auth sections does not inspect unavailable account storage`() = runTest {
        doReturn(false).`when`(netease).isAuthStoragePersistent
        doReturn(false).`when`(bili).isAuthStoragePersistent
        doReturn(false).`when`(youtube).isAuthStoragePersistent

        val result = importResult(""""listenTogether":{}""") {
            error("backup without auth must not request confirmation")
        }.getOrThrow()

        assertEquals(0, result.restoredAuthCount)
        verifyNoInteractions(netease, bili, youtube)
    }

    @Test
    fun `logged out youtube metadata does not request compatibility confirmation`() = runTest {
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning

        val result = importResult(
            """"youTubeAuth":{"origin":"https://music.youtube.com","userAgent":"synthetic-agent","xGoogAuthUser":"0"}"""
        ) {
            error("youtube metadata without credentials must not request confirmation")
        }

        assertTrue(result.isSuccess)
    }

    @Test
    fun `storage switching to compatibility while other sections restore cannot save unconfirmed auth`() = runTest {
        doAnswer {
            doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning
            Unit
        }.`when`(listenTogether).restore(
            any(ListenTogetherConfigSnapshot::class.java) ?: ListenTogetherConfigSnapshot()
        )

        val result = importResult(""""listenTogether":{},"youTubeAuth":{"cookieHeader":"SID=session"}""")

        assertFalse(result.isSuccess)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `cancelling an encrypted backup password changes no configuration or account`() = runTest {
        input = encryptedInput()
        var prompts = 0

        val result = manager().importConfig(uri, requestPassword = { prompts++; null })

        assertTrue(result.exceptionOrNull() is ConfigImportCancelledException)
        assertEquals(1, prompts)
        verifyNoInteractions(listenTogether, netease, bili, youtube, languagePreferences)
        assertEquals(0, syncMutations)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `wrong encrypted backup passwords are consumed before any configuration is changed`() = runTest {
        input = encryptedInput()
        val password = "wrong password".toCharArray()

        val result = manager().importConfig(uri, requestPassword = { password })

        assertTrue(result.exceptionOrNull() is ConfigBackupPasswordException)
        assertTrue(password.all { it == '\u0000' })
        verifyNoInteractions(listenTogether, netease, bili, youtube, languagePreferences)
        assertEquals(0, syncMutations)
        assertTrue(savedAuth.isEmpty())
    }

    @Test
    fun `encrypted credential free backup preserves every existing account and uses the original input`() = runTest {
        github = GitHubSyncConfigSnapshot(token = "existing", repoOwner = "owner", repoName = "repo")
        webDav = WebDavSyncConfigSnapshot(serverUrl = "https://dav.example", username = "user", password = "existing")
        val originalGithub = github
        val originalWebDav = webDav
        val restored = mutableListOf<ListenTogetherConfigSnapshot>()
        doAnswer { restored += it.getArgument<ListenTogetherConfigSnapshot>(0); Unit }
            .`when`(listenTogether).restore(any(ListenTogetherConfigSnapshot::class.java) ?: ListenTogetherConfigSnapshot())
        input = encryptedInput()
        val password = "fixture password".toCharArray()

        val result = manager().importConfig(uri, requestPassword = {
            input = "replaced while the password prompt was open"
            password
        }).getOrThrow()

        assertEquals("original", restored.single().nickname)
        assertEquals(0, result.restoredAuthCount)
        assertEquals(0, result.restoredSyncCount)
        assertEquals(originalGithub, github)
        assertEquals(originalWebDav, webDav)
        verifyNoInteractions(netease, bili, youtube)
        verify(resolver, times(1)).openInputStream(uri)
        assertEquals(0, syncMutations)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun `unencrypted old backups never request passwords`() = runTest {
        input = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"listenTogether":{}}"""

        val result = manager().importConfig(uri, requestPassword = { error("legacy JSON does not need a password") })

        assertTrue(result.isSuccess)
    }

    @Test
    fun `decryption cannot bypass the existing compatibility storage confirmation`() = runTest {
        doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning
        input = encryptedInput(""""listenTogether":{},"youTubeAuth":{"cookieHeader":"SID=synthetic-session"}""")
        val password = "fixture password".toCharArray()
        var confirmations = 0

        val result = manager().importConfig(
            uri,
            requestPassword = { password },
            confirmCompatibilityStorage = { confirmations++; false }
        )

        assertTrue(result.exceptionOrNull() is ConfigImportCancelledException)
        assertEquals(1, confirmations)
        verifyNoInteractions(listenTogether)
        assertTrue(savedAuth.isEmpty())
        assertTrue(password.all { it == '\u0000' })
    }

    private fun encryptedInput(sections: String = """"listenTogether":{"nickname":"original"}"""): String =
        ConfigBackupEncryption.encrypt(
            """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,$sections}""",
            "fixture password".toCharArray()
        )

    private suspend fun TestScope.importSections(sections: String): AppConfigImportResult {
        return importResult(sections).getOrThrow()
    }

    private suspend fun TestScope.importResult(
        sections: String,
        confirmCompatibilityStorage: suspend () -> Boolean = { false }
    ): Result<AppConfigImportResult> {
        input = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,$sections}"""
        return manager().importConfig(uri, confirmCompatibilityStorage)
    }

    private fun TestScope.manager(): ConfigFileManager = ConfigFileManager(
        context,
        listenTogether,
        netease,
        bili,
        youtube,
        SyncProtocolUpgradeRepository(MemoryPreferencesStore()),
        UnconfinedTestDispatcher(testScheduler)
    )

    private class MemoryPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
}
