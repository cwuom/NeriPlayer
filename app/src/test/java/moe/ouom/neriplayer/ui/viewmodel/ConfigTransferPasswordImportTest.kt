package moe.ouom.neriplayer.ui.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.model.config.ListenTogetherConfigSnapshot
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.preferences.SyncPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigTransferPasswordImportTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `encrypted import waits without writing and duplicate confirmation restores only once`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.importConfig(fixture.uri)

            assertTrue(fixture.viewModel.uiState.value.isImporting)
            assertTrue(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertFalse(fixture.viewModel.uiState.value.isConfigPasswordInvalid)
            assertTrue(fixture.savedAuth.isEmpty())

            fixture.viewModel.confirmImportPassword(PASSWORD)
            fixture.viewModel.confirmImportPassword(PASSWORD)

            fixture.assertImportedOnce()
            verify(fixture.resolver, times(1)).openInputStream(fixture.uri)
        }
    }

    @Test
    fun `wrong password reports a retry without writing then correct password restores once`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.confirmImportPassword("Synthetic-wrong-password")

            assertTrue(fixture.viewModel.uiState.value.isImporting)
            assertTrue(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertTrue(fixture.viewModel.uiState.value.isConfigPasswordInvalid)
            assertNull(fixture.viewModel.uiState.value.lastImportSuccess)
            assertTrue(fixture.savedAuth.isEmpty())

            fixture.viewModel.confirmImportPassword(PASSWORD)

            fixture.assertImportedOnce()
        }
    }

    @Test
    fun `cancelling a password prompt ignores a late answer and later import asks again`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.cancelAuthStorageImport()
            fixture.viewModel.confirmImportPassword(PASSWORD)

            assertFalse(fixture.viewModel.uiState.value.isImporting)
            assertFalse(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertNull(fixture.viewModel.uiState.value.lastImportSuccess)
            assertTrue(fixture.savedAuth.isEmpty())

            fixture.viewModel.importConfig(fixture.uri)
            assertTrue(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertTrue(fixture.savedAuth.isEmpty())
            fixture.viewModel.confirmImportPassword(PASSWORD)
            fixture.assertImportedOnce()
        }
    }

    @Test
    fun `cancelling an invalid password clears the error before a fresh import`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.confirmImportPassword("Synthetic-wrong-password")
            assertTrue(fixture.viewModel.uiState.value.isConfigPasswordInvalid)

            fixture.viewModel.cancelAuthStorageImport()
            fixture.viewModel.importConfig(fixture.uri)

            assertTrue(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertFalse(fixture.viewModel.uiState.value.isConfigPasswordInvalid)
            assertTrue(fixture.savedAuth.isEmpty())
            fixture.viewModel.cancelAuthStorageImport()
        }
    }

    @Test
    fun `clearing the viewmodel cancels password waiting and rejects a late answer`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            assertTrue(fixture.viewModel.uiState.value.requiresConfigPassword)
            val importJob = fixture.viewModel.viewModelScope.coroutineContext[Job]!!.children.single()

            fixture.store.clear()
            fixture.viewModel.confirmImportPassword(PASSWORD)

            assertTrue(importJob.isCancelled)
            assertFalse(fixture.viewModel.uiState.value.isImporting)
            assertFalse(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertFalse(fixture.viewModel.uiState.value.isConfigPasswordInvalid)
            assertTrue(fixture.savedAuth.isEmpty())
        }
    }

    @Test
    fun `cancelling after submitting a password but before io resumes cannot restore auth`() = runTest {
        fixture(deferIo = true).use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            runCurrent()
            assertTrue(fixture.viewModel.uiState.value.requiresConfigPassword)
            val importJob = fixture.viewModel.viewModelScope.coroutineContext[Job]!!.children.single()

            fixture.viewModel.confirmImportPassword(PASSWORD)
            fixture.viewModel.cancelAuthStorageImport()
            runCurrent()

            assertTrue(importJob.isCancelled)
            assertFalse(fixture.viewModel.uiState.value.isImporting)
            assertFalse(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertNull(fixture.viewModel.uiState.value.lastImportSuccess)
            assertTrue(fixture.savedAuth.isEmpty())
        }
    }

    @Test
    fun `correct password still waits for compatibility consent before writing auth`() = runTest {
        fixture(compatibilityWarning = true).use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.confirmImportPassword(PASSWORD)

            assertFalse(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertTrue(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertTrue(fixture.savedAuth.isEmpty())

            fixture.viewModel.confirmAuthStorageImport()
            fixture.viewModel.confirmAuthStorageImport()

            fixture.assertImportedOnce(allowCompatibilityStorage = true)
        }
    }

    @Test
    fun `legacy plaintext without auth imports without requesting a password or changing auth`() = runTest {
        val raw = """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"listenTogether":{"nickname":"synthetic migration"}}"""
        fixture(raw).use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)

            assertEquals(true, fixture.viewModel.uiState.value.lastImportSuccess)
            assertFalse(fixture.viewModel.uiState.value.requiresConfigPassword)
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertTrue(fixture.savedAuth.isEmpty())
            verifyNoInteractions(fixture.youtube)
            verify(fixture.listenTogether).restore(ListenTogetherConfigSnapshot(nickname = "synthetic migration"))
            verify(fixture.resolver, times(1)).openInputStream(fixture.uri)
        }
    }

    @Test
    fun `cancelled export selection cannot export and reopening starts without a prepared selection`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.requestExportOptions()
            assertTrue(fixture.viewModel.uiState.value.showExportOptions)
            assertTrue(fixture.viewModel.prepareExport(includeLoginState = true, password = PASSWORD))
            assertFalse(fixture.viewModel.uiState.value.showExportOptions)
            assertFalse(fixture.viewModel.prepareExport(includeLoginState = false, password = null))

            fixture.viewModel.cancelExportSelection()
            fixture.viewModel.requestExportOptions()
            assertEquals(ConfigTransferUiState(showExportOptions = true), fixture.viewModel.uiState.value)
            fixture.viewModel.cancelExportSelection()
            fixture.viewModel.exportConfig(fixture.uri)

            assertFalse(fixture.viewModel.uiState.value.isExporting)
            assertFalse(fixture.viewModel.uiState.value.showExportOptions)
            assertEquals(false, fixture.viewModel.uiState.value.lastExportSuccess)
            verify(fixture.resolver, never()).openOutputStream(fixture.uri)
        }
    }

    private fun TestScope.fixture(
        raw: String = ENCRYPTED,
        compatibilityWarning: Boolean = false,
        deferIo: Boolean = false
    ): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val ioDispatcher = if (deferIo) StandardTestDispatcher(testScheduler) else dispatcher
        return Fixture(ioDispatcher, raw, compatibilityWarning)
    }

    private class Fixture(ioDispatcher: CoroutineDispatcher, raw: String, compatibilityWarning: Boolean) : Closeable {
        val uri = mock(Uri::class.java)
        val resolver = mock(ContentResolver::class.java)
        val youtube = mock(YouTubeAuthRepository::class.java)
        val listenTogether = mock(ListenTogetherPreferences::class.java)
        val savedAuth = mutableListOf<YouTubeAuthBundle>()
        val store = ViewModelStore()
        val viewModel = ConfigTransferViewModel()
        private val github = mockConstruction(SecureTokenStorage::class.java)
        private val webDav = mockConstruction(WebDavStorage::class.java)
        private val syncPreferences = mockConstruction(SyncPreferences::class.java)

        init {
            val language = mock(SharedPreferences::class.java)
            val resources = mock(Resources::class.java) { invocation ->
                if (invocation.method.name == "getQuantityString") "restored" else null
            }
            val context = mock(Context::class.java) { invocation ->
                when (invocation.method.name) {
                    "getString" -> "transfer status"
                    "getResources" -> resources
                    "getSharedPreferences" -> language
                    "getContentResolver" -> resolver
                    else -> null
                }
            }
            doReturn("").`when`(language).getString("selected_language", "")
            doReturn(true).`when`(youtube).isAuthStoragePersistent
            doReturn(compatibilityWarning).`when`(youtube).requiresCompatibilityStorageWarning
            doAnswer { savedAuth += it.getArgument<YouTubeAuthBundle>(0); Unit }
                .`when`(youtube).saveAuth(any(YouTubeAuthBundle::class.java) ?: YouTubeAuthBundle(), anyBoolean())
            doAnswer { ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)) }
                .`when`(resolver).openInputStream(uri)
            val manager = ConfigFileManager(
                context = context,
                listenTogetherPreferences = listenTogether,
                youTubeAuthRepo = youtube,
                ioDispatcher = ioDispatcher
            )
            setField("configFileManager", manager)
            setField("strings", ConfigTransferViewModel.ConfigTransferStrings.from(context))
            store.put("config", viewModel)
        }

        fun assertImportedOnce(allowCompatibilityStorage: Boolean = false) {
            assertEquals(true, viewModel.uiState.value.lastImportSuccess)
            assertFalse(viewModel.uiState.value.isImporting)
            assertFalse(viewModel.uiState.value.requiresConfigPassword)
            assertFalse(viewModel.uiState.value.isConfigPasswordInvalid)
            assertFalse(viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertEquals("SID=synthetic-password-import", savedAuth.single().cookieHeader)
            verify(youtube).saveAuth(savedAuth.single(), allowCompatibilityStorage)
        }

        private fun setField(name: String, value: Any) {
            ConfigTransferViewModel::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(viewModel, value)
            }
        }

        override fun close() {
            store.clear()
            syncPreferences.close()
            webDav.close()
            github.close()
        }
    }

    private companion object {
        const val PASSWORD = "Synthetic-import-password"
        // 固定向量由 Node crypto 独立生成，内容只包含合成的 YouTube 登录态
        const val ENCRYPTED = """{"kind":"moe.ouom.neriplayer.config.encrypted","formatVersion":1,"kdf":"PBKDF2-HMAC-SHA256","iterations":600000,"cipher":"AES-256-GCM","salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"EBESExQVFhcYGRob","ciphertext":"GtcuMjFexq/FzVVBLixREtgCTORTjjgII1i1l9FmuJsSSOWPDlXH9I3nJrwuufB5qzZRg5GQZohB9TVIzUVxsM80620570PNSXmsy5BYtQtsW76xsUVQmFhhMkTbnM7BKXB4C7Lw4ZDWGNHQeOtGEP5DJYxSXhfEO3tNvE9mhKyqrc6WyTc="}"""
    }
}
