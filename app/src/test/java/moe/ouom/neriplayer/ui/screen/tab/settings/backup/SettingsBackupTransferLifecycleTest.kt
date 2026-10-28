package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.Closeable
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import moe.ouom.neriplayer.ui.viewmodel.BackupRestoreViewModel
import moe.ouom.neriplayer.ui.viewmodel.ConfigTransferViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.robolectric.Robolectric

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsBackupTransferLifecycleTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `host disposal keeps a healthy import applying and publishes its final result`() = runTest {
        assertApplyingImportSurvivesHostDisposal(requiresConfirmation = false)
    }

    @Test
    fun `host disposal keeps a confirmed compatibility import applying and publishes its final result`() = runTest {
        assertApplyingImportSurvivesHostDisposal(requiresConfirmation = true)
    }

    private suspend fun TestScope.assertApplyingImportSurvivesHostDisposal(requiresConfirmation: Boolean) {
        Fixture(this, requiresConfirmation).use { fixture ->
            fixture.viewModel.importConfig(fixture.uri)
            if (requiresConfirmation) {
                assertTrue(fixture.viewModel.uiState.value.requiresAuthStorageConfirmation)
                assertFalse(fixture.applyEntered.isCompleted)
                fixture.viewModel.confirmAuthStorageImport()
            }
            assertTrue(fixture.applyEntered.isCompleted)
            assertEquals("restored", fixture.restoredNickname)
            assertTrue(fixture.savedAuth.isEmpty())
            fixture.mountHost()

            fixture.disposeHost()

            assertTrue("组合销毁不能取消已开始恢复的导入", fixture.viewModel.uiState.value.isImporting)
            fixture.mountHost()
            fixture.releaseApply.complete(Unit)
            runCurrent()

            assertEquals("session", fixture.savedAuth.single().cookies["SID"])
            verify(fixture.youtube).saveAuth(fixture.savedAuth.single(), requiresConfirmation)
            assertEquals(true, fixture.viewModel.uiState.value.lastImportSuccess)
            assertFalse(fixture.viewModel.uiState.value.isImporting)
        }
    }

    private class Fixture(scope: TestScope, requiresConfirmation: Boolean) : Closeable {
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        private val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        private val activity = activityController.get()
        private val store = ViewModelStore()
        private val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var composition: Composition? = null
        private val github = mockConstruction(SecureTokenStorage::class.java)
        private val webDav = mockConstruction(WebDavStorage::class.java)
        private val syncPreferences = mockConstruction(SyncPreferences::class.java)
        val uri = mock(Uri::class.java)
        val youtube = mock(YouTubeAuthRepository::class.java)
        val viewModel = ConfigTransferViewModel()
        val applyEntered = CompletableDeferred<Unit>()
        val releaseApply = CompletableDeferred<Unit>()
        val savedAuth = mutableListOf<YouTubeAuthBundle>()
        var restoredNickname: String? = null
            private set

        init {
            Dispatchers.setMain(dispatcher)
            val resolver = mock(ContentResolver::class.java)
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
            doReturn(requiresConfirmation).`when`(youtube).requiresCompatibilityStorageWarning
            doAnswer { savedAuth += it.getArgument<YouTubeAuthBundle>(0); Unit }
                .`when`(youtube).saveAuth(any(YouTubeAuthBundle::class.java) ?: YouTubeAuthBundle(), anyBoolean())
            doAnswer {
                ByteArrayInputStream(
                    """{"kind":"moe.ouom.neriplayer.config","formatVersion":1,"listenTogether":{"nickname":"restored"},"youTubeAuth":{"cookieHeader":"SID=session"}}""".toByteArray()
                )
            }.`when`(resolver).openInputStream(uri)
            val listenTogether = mock(ListenTogetherPreferences::class.java)
            kotlinx.coroutines.runBlocking {
                doAnswer { invocation ->
                    restoredNickname = invocation.getArgument<ListenTogetherConfigSnapshot>(0).nickname
                    @Suppress("UNCHECKED_CAST")
                    val continuation = invocation.rawArguments.last() as Continuation<Unit>
                    val restore: suspend () -> Unit = {
                        applyEntered.complete(Unit)
                        releaseApply.await()
                    }
                    restore.startCoroutineUninterceptedOrReturn(continuation)
                }.`when`(listenTogether).restore(
                    any(ListenTogetherConfigSnapshot::class.java) ?: ListenTogetherConfigSnapshot()
                )
            }
            val manager = ConfigFileManager(
                context = context,
                listenTogetherPreferences = listenTogether,
                youTubeAuthRepo = youtube,
                ioDispatcher = dispatcher
            )
            setField("configFileManager", manager)
            setField("strings", ConfigTransferViewModel.ConfigTransferStrings.create())
            store.put(defaultKey(ConfigTransferViewModel::class.java), viewModel)
            store.put(defaultKey(BackupRestoreViewModel::class.java), BackupRestoreViewModel())
        }

        fun mountHost() {
            composition = Composition(EmptyApplier(), recomposer).also { host ->
                host.setContent {
                    CompositionLocalProvider(
                        LocalContext provides activity,
                        LocalLifecycleOwner provides activity,
                        LocalViewModelStoreOwner provides owner,
                        LocalActivityResultRegistryOwner provides activity
                    ) {
                        rememberSettingsBackupTransferController(onBeforeLanguageRestart = {})
                    }
                }
            }
        }

        fun disposeHost() {
            composition?.dispose()
            composition = null
        }

        private fun setField(name: String, value: Any) {
            ConfigTransferViewModel::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(viewModel, value)
            }
        }

        override fun close() {
            disposeHost()
            store.clear()
            releaseApply.complete(Unit)
            recomposer.cancel()
            runner.cancel()
            syncPreferences.close()
            webDav.close()
            github.close()
            activityController.pause().stop().destroy()
        }

        private fun defaultKey(type: Class<*>) = "androidx.lifecycle.ViewModelProvider.DefaultKey:${type.canonicalName}"
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
        override fun remove(index: Int, count: Int) = Unit
    }
}
