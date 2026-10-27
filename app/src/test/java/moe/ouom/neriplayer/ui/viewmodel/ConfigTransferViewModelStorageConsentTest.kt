package moe.ouom.neriplayer.ui.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.data.config.ConfigFileManager
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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigTransferViewModelStorageConsentTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pending import rejects duplicate requests and confirmation restores once`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.importConfig(fixture.uri)

            assertTrue(fixture.viewModel.uiState.value.isImporting)
            assertTrue(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertTrue(fixture.savedAuth.isEmpty())

            fixture.viewModel.confirmAuthStorageImport()
            fixture.viewModel.confirmAuthStorageImport()

            assertFalse(fixture.viewModel.uiState.value.isImporting)
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertEquals(true, fixture.viewModel.uiState.value.lastImportSuccess)
            assertEquals("SID=synthetic", fixture.savedAuth.single().cookieHeader)
            verify(fixture.youtube).saveAuth(fixture.savedAuth.single(), true)
            verify(fixture.resolver, times(1)).openInputStream(fixture.uri)
        }
    }

    @Test
    fun `cancelled import changes no auth and a later import needs fresh confirmation`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.cancelAuthStorageImport()
            fixture.viewModel.confirmAuthStorageImport()

            assertFalse(fixture.viewModel.uiState.value.isImporting)
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertNull(fixture.viewModel.uiState.value.lastImportSuccess)
            assertTrue(fixture.savedAuth.isEmpty())

            fixture.viewModel.importConfig(fixture.uri)

            assertTrue(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertTrue(fixture.savedAuth.isEmpty())
            fixture.viewModel.confirmAuthStorageImport()
            assertEquals(1, fixture.savedAuth.size)
            verify(fixture.youtube).saveAuth(fixture.savedAuth.single(), true)
        }
    }

    @Test
    fun `clearing the viewmodel cancels the suspended import without restoring auth`() = runTest {
        fixture().use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            assertTrue(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)

            fixture.store.clear()
            fixture.viewModel.confirmAuthStorageImport()

            assertTrue(fixture.savedAuth.isEmpty())
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
        }
    }

    @Test
    fun `healthy storage imports without a confirmation prompt`() = runTest {
        fixture().use { fixture ->
            doReturn(false).`when`(fixture.youtube).requiresCompatibilityStorageWarning

            fixture.viewModel.importConfig(fixture.uri)

            assertEquals(true, fixture.viewModel.uiState.value.lastImportSuccess)
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertEquals(1, fixture.savedAuth.size)
            verify(fixture.youtube).saveAuth(fixture.savedAuth.single(), false)
        }
    }

    @Test
    fun `volatile storage reports failure and cannot be confirmed`() = runTest {
        fixture().use { fixture ->
            doReturn(false).`when`(fixture.youtube).isAuthStoragePersistent

            fixture.viewModel.importConfig(fixture.uri)
            fixture.viewModel.confirmAuthStorageImport()

            assertEquals(false, fixture.viewModel.uiState.value.lastImportSuccess)
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertTrue(fixture.savedAuth.isEmpty())
        }
    }

    @Test
    fun `explicit cancellation during a slow read prevents a later confirmation`() = runTest {
        fixture(Dispatchers.IO).use { fixture ->
            val releaseRead = CountDownLatch(1)
            fixture.readGate = releaseRead
            fixture.viewModel.importConfig(fixture.uri)
            fixture.readStarted.await()
            val importJob = fixture.viewModel.viewModelScope.coroutineContext[Job]!!.children.single()

            fixture.viewModel.cancelAuthStorageImport()
            val stateAfterCancellation = fixture.viewModel.uiState.value
            val cancelledExplicitly = importJob.isCancelled
            importJob.cancel()
            releaseRead.countDown()
            importJob.join()

            assertTrue(cancelledExplicitly)
            assertFalse(stateAfterCancellation.isImporting)
            assertFalse(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
            assertTrue(fixture.savedAuth.isEmpty())
        }
    }

    private fun TestScope.fixture(ioDispatcher: CoroutineDispatcher? = null): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return Fixture(ioDispatcher ?: dispatcher)
    }

    private class Fixture(ioDispatcher: CoroutineDispatcher) : Closeable {
        val uri = mock(Uri::class.java)
        val resolver = mock(ContentResolver::class.java)
        val youtube = mock(YouTubeAuthRepository::class.java)
        val savedAuth = mutableListOf<YouTubeAuthBundle>()
        val store = ViewModelStore()
        val viewModel = ConfigTransferViewModel()
        var readGate: CountDownLatch? = null
        val readStarted = CompletableDeferred<Unit>()
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
                    "getString" -> "storage status"
                    "getResources" -> resources
                    "getSharedPreferences" -> language
                    "getContentResolver" -> resolver
                    else -> null
                }
            }
            doReturn("").`when`(language).getString("selected_language", "")
            doReturn(true).`when`(youtube).isAuthStoragePersistent
            doReturn(true).`when`(youtube).requiresCompatibilityStorageWarning
            doAnswer { savedAuth += it.getArgument<YouTubeAuthBundle>(0); Unit }
                .`when`(youtube).saveAuth(any(YouTubeAuthBundle::class.java) ?: YouTubeAuthBundle(), anyBoolean())
            doAnswer {
                readStarted.complete(Unit)
                check(readGate?.await(10, TimeUnit.SECONDS) != false) { "synthetic input read timed out" }
                ByteArrayInputStream(
                    """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"youTubeAuth":{"cookieHeader":"SID=synthetic"}}""".toByteArray()
                )
            }.`when`(resolver).openInputStream(uri)
            val manager = ConfigFileManager(
                context = context,
                youTubeAuthRepo = youtube,
                ioDispatcher = ioDispatcher
            )
            setField("configFileManager", manager)
            setField("strings", ConfigTransferViewModel.ConfigTransferStrings.create())
            store.put("config", viewModel)
        }

        private fun setField(name: String, value: Any) {
            ConfigTransferViewModel::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(viewModel, value)
            }
        }

        override fun close() {
            readGate?.countDown()
            store.clear()
            syncPreferences.close()
            webDav.close()
            github.close()
        }
    }
}
