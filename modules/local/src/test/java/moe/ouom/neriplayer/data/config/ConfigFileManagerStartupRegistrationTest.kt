package moe.ouom.neriplayer.data.config

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.config.ListenTogetherConfigSnapshot
import moe.ouom.neriplayer.data.model.config.WebDavSyncConfigSnapshot
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.preferences.SyncPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigFileManagerStartupRegistrationTest {
    @Test
    fun `slow registration blocks actual config restoration until durable baseline exists`() = runTest {
        Fixture(this).use { fixture ->
            fixture.store.registrationGate = CompletableDeferred()
            val import = async { fixture.manager.importConfig(fixture.uri) }
            runCurrent()

            assertFalse(import.isCompleted)
            assertTrue(fixture.store.registrationEntered)
            fixture.assertNoRestore()
            assertNull(fixture.store.data.first()[StartupVersion])

            fixture.store.registrationGate?.complete(Unit)
            assertTrue(import.await().isSuccess)
            assertEquals(4, fixture.versionAtFirstRestore)
            assertEquals(fixture.importedGithub, fixture.github)
            assertEquals(fixture.importedWebDav, fixture.webDav)
        }
    }

    @Test
    fun `failed registration changes no imported section and retry retains empty original baseline`() = runTest {
        Fixture(this).use { fixture ->
            val failure = IOException("registration disk full")
            fixture.store.registrationFailure = failure
            fixture.includeSettingsAndLanguage()

            assertSame(failure, fixture.manager.importConfig(fixture.uri).exceptionOrNull())
            fixture.assertNoRestore()
            assertNull(fixture.store.data.first()[StartupVersion])

            fixture.store.registrationFailure = null
            fixture.input = fixture.syncInput
            assertTrue(fixture.manager.importConfig(fixture.uri).isSuccess)
            fixture.repository.initializeStartupTargets(fixture.importedTargets())
            assertTrue(fixture.repository.startupPendingFlow.first().isEmpty())
            assertEquals(4, fixture.repository.versionFlow(fixture.importedTargets().first()).first())
        }
    }

    @Test
    fun `cancelled registration propagates cancellation before any restore and can retry`() = runTest {
        Fixture(this).use { fixture ->
            val cancellation = CancellationException("registration cancelled")
            fixture.store.registrationFailure = cancellation
            fixture.includeSettingsAndLanguage()

            assertSame(cancellation, runCatching { fixture.manager.importConfig(fixture.uri) }.exceptionOrNull())
            fixture.assertNoRestore()
            assertNull(fixture.store.data.first()[StartupVersion])

            fixture.store.registrationFailure = null
            fixture.input = fixture.syncInput
            assertTrue(fixture.manager.importConfig(fixture.uri).isSuccess)
            assertTrue(fixture.repository.startupPendingFlow.first().isEmpty())
        }
    }

    @Test
    fun `cancelling a suspended import preserves every section and permits a later retry`() = runTest {
        Fixture(this).use { fixture ->
            fixture.store.registrationGate = CompletableDeferred()
            fixture.includeSettingsAndLanguage()
            val import = async { fixture.manager.importConfig(fixture.uri) }
            runCurrent()
            assertTrue(fixture.store.registrationEntered)

            import.cancelAndJoin()

            assertTrue(import.isCancelled)
            fixture.assertNoRestore()
            assertNull(fixture.store.data.first()[StartupVersion])
            fixture.store.registrationGate = null
            fixture.input = fixture.syncInput
            assertTrue(fixture.manager.importConfig(fixture.uri).isSuccess)
            assertTrue(fixture.repository.startupPendingFlow.first().isEmpty())
        }
    }

    @Test
    fun `future startup registration rejects a combined config before restoration`() = runTest {
        Fixture(this).use { fixture ->
            fixture.store.state.value = preferencesOf(StartupVersion to 5)
            fixture.includeSettingsAndLanguage()

            assertTrue(fixture.manager.importConfig(fixture.uri).exceptionOrNull() is SyncProtocolUpgradeRequiredException)

            fixture.assertNoRestore()
            assertEquals(5, fixture.store.data.first()[StartupVersion])
        }
    }

    @Test
    fun `first import registers existing targets rather than addresses in imported payload`() = runTest {
        Fixture(this).use { fixture ->
            fixture.github = GitHubSyncConfigSnapshot(token = "old", repoOwner = "old-owner", repoName = "old-repo")
            fixture.webDav = WebDavSyncConfigSnapshot(serverUrl = "https://old.example", username = "old-user", password = "old")
            val originalTargets = fixture.currentTargets()

            assertTrue(fixture.manager.importConfig(fixture.uri).isSuccess)
            fixture.repository.initializeStartupTargets(fixture.importedTargets())

            assertEquals(originalTargets, fixture.repository.startupPendingFlow.first())
            assertEquals(4, fixture.versionAtFirstRestore)
            for (target in fixture.importedTargets()) assertTrue(fixture.repository.canSyncTarget(target))
            for (target in originalTargets) assertFalse(fixture.repository.canSyncTarget(target))
        }
    }

    @Test
    fun `import after successful empty startup cannot turn later configured target into old installation`() = runTest {
        Fixture(this).use { fixture ->
            fixture.repository.initializeStartupTargets(emptySet())
            fixture.github = fixture.importedGithub.copy(repoName = "later-configured")

            assertTrue(fixture.manager.importConfig(fixture.uri).isSuccess)

            assertTrue(fixture.repository.startupPendingFlow.first().isEmpty())
            assertEquals(4, fixture.versionAtFirstRestore)
        }
    }

    @Test
    fun `import without sync target sections does not require startup registration`() = runTest {
        Fixture(this).use { fixture ->
            fixture.input = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"listenTogether":{},"neteaseAuth":{},"biliAuth":{},"youTubeAuth":{}}"""
            fixture.store.registrationFailure = IOException("unavailable baseline")

            assertTrue(fixture.manager.importConfig(fixture.uri).isSuccess)

            assertFalse(fixture.store.registrationEntered)
            assertNull(fixture.store.data.first()[StartupVersion])
            assertEquals(1, fixture.restoreCount)
        }
    }

    private class Fixture(scope: TestScope) : Closeable {
        val store = RegistrationStore()
        val repository = SyncProtocolUpgradeRepository(store)
        val uri = mock(Uri::class.java)
        private val context = mock(Context::class.java)
        private val resolver = mock(ContentResolver::class.java)
        private val languagePreferences = mock(SharedPreferences::class.java)
        private val listenTogether = mock(ListenTogetherPreferences::class.java)
        private val netease = mock(NeteaseCookieRepository::class.java)
        private val bili = mock(BiliCookieRepository::class.java)
        private val youtube = mock(YouTubeAuthRepository::class.java)
        val importedGithub = GitHubSyncConfigSnapshot(token = "imported", repoOwner = "new-owner", repoName = "new-repo", autoSyncEnabled = false)
        val importedWebDav = WebDavSyncConfigSnapshot(serverUrl = "https://new.example", basePath = "new-backup", username = "new-user", password = "imported", autoSyncEnabled = false)
        var github = GitHubSyncConfigSnapshot()
        var webDav = WebDavSyncConfigSnapshot()
        var restoreCount = 0
        var versionAtFirstRestore: Int? = null
        val syncInput = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"listenTogether":{},"neteaseAuth":{},"biliAuth":{},"youTubeAuth":{},"gitHubSync":{"token":"imported","repoOwner":"new-owner","repoName":"new-repo","autoSyncEnabled":false},"webDavSync":{"serverUrl":"https://new.example","basePath":"new-backup","username":"new-user","password":"imported","autoSyncEnabled":false},"syncPreferences":{}}"""
        var input = syncInput
        private val githubConstruction = mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            `when`(storage.isConfigured()).thenAnswer { github.token.isNotBlank() && github.repoOwner.isNotBlank() && github.repoName.isNotBlank() }
            `when`(storage.snapshot()).thenAnswer { github }
            `when`(storage.getRepoOwner()).thenAnswer { github.repoOwner }
            `when`(storage.getRepoName()).thenAnswer { github.repoName }
            doAnswer { invocation -> github = invocation.getArgument(0); Unit }.`when`(storage)
                .restore(any(GitHubSyncConfigSnapshot::class.java) ?: importedGithub)
        }
        private val webDavConstruction = mockConstruction(WebDavStorage::class.java) { storage, _ ->
            `when`(storage.isConfigured()).thenAnswer { webDav.serverUrl.isNotBlank() && webDav.username.isNotBlank() && webDav.password.isNotBlank() }
            `when`(storage.snapshot()).thenAnswer { webDav }
            `when`(storage.getServerUrl()).thenAnswer { webDav.serverUrl }
            `when`(storage.getBasePath()).thenAnswer { webDav.basePath }
            `when`(storage.getUsername()).thenAnswer { webDav.username }
            doAnswer { invocation -> webDav = invocation.getArgument(0); Unit }.`when`(storage)
                .restore(any(WebDavSyncConfigSnapshot::class.java) ?: importedWebDav)
        }
        private val preferencesConstruction = mockConstruction(SyncPreferences::class.java)
        private val schedulerConstruction = mockConstruction(SyncWorkScheduler::class.java)
        val manager = ConfigFileManager(context, listenTogether, netease, bili, youtube, repository, UnconfinedTestDispatcher(scope.testScheduler))

        init {
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.contentResolver).thenReturn(resolver)
            `when`(context.getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(languagePreferences)
            `when`(languagePreferences.getString("selected_language", "")).thenReturn("")
            `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(input.toByteArray()) }
            runBlocking {
                doAnswer {
                    if (restoreCount == 0) versionAtFirstRestore = store.state.value[StartupVersion]
                    restoreCount++
                    Unit
                }.`when`(listenTogether).restore(any(ListenTogetherConfigSnapshot::class.java) ?: ListenTogetherConfigSnapshot())
            }
        }

        fun currentTargets(): Set<String> = buildSet {
            if (github.token.isNotBlank()) add(SyncProtocolUpgradeRepository.githubTargetHash(github.repoOwner, github.repoName))
            if (webDav.password.isNotBlank()) add(SyncProtocolUpgradeRepository.webDavTargetHash(webDav.serverUrl, webDav.basePath, webDav.username))
        }

        fun importedTargets(): Set<String> = setOf(
            SyncProtocolUpgradeRepository.githubTargetHash(importedGithub.repoOwner, importedGithub.repoName),
            SyncProtocolUpgradeRepository.webDavTargetHash(importedWebDav.serverUrl, importedWebDav.basePath, importedWebDav.username)
        )

        fun includeSettingsAndLanguage() {
            input = syncInput.replace("\"listenTogether\":{}", "\"settings\":{\"booleans\":{\"dynamic_color\":false}},\"language\":{\"code\":\"en\"},\"listenTogether\":{}")
        }

        fun assertNoRestore() {
            assertEquals(0, restoreCount)
            verifyNoInteractions(listenTogether, netease, bili, youtube, languagePreferences)
            verify(context, never()).filesDir
            githubConstruction.constructed().forEach { storage ->
                verify(storage, never()).restore(any(GitHubSyncConfigSnapshot::class.java) ?: importedGithub)
            }
            webDavConstruction.constructed().forEach { storage ->
                verify(storage, never()).restore(any(WebDavSyncConfigSnapshot::class.java) ?: importedWebDav)
            }
            assertTrue(preferencesConstruction.constructed().isEmpty())
            assertTrue(schedulerConstruction.constructed().isEmpty())
        }

        override fun close() {
            store.registrationGate?.cancel()
            schedulerConstruction.close()
            preferencesConstruction.close()
            webDavConstruction.close()
            githubConstruction.close()
        }
    }

    private class RegistrationStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        var registrationGate: CompletableDeferred<Unit>? = null
        var registrationFailure: Exception? = null
        var registrationEntered = false
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            if (updated[StartupVersion] != state.value[StartupVersion]) {
                registrationEntered = true
                registrationGate?.await()
                registrationFailure?.let { throw it }
            }
            return updated.also { state.value = it }
        }
    }

    private companion object {
        val StartupVersion = intPreferencesKey("startup_registration_version")
    }
}
