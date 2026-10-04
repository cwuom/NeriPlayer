package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.ouom.neriplayer.api.sync.github.GitHubApiException
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAccessDeniedException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiException
import moe.ouom.neriplayer.api.sync.webdav.WebDavArchiveLeaseLostException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavDirectoryNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException
import moe.ouom.neriplayer.api.sync.webdav.WebDavNotDirectoryException
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncProtocolUpgradeFailureTest {
    private val models = mutableListOf<SyncProtocolUpgradeViewModel>()
    private val target = "a".repeat(64)
    private val challenge = SyncProtocolUpgradeChallenge(target, "1".repeat(64))

    @After
    fun cleanUp() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `sync failures expose localized categories and HTTP status without retaining service text`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sensitive = "https://private.example/dav?authorization=private-service-body"
        val cases = listOf(
            WebDavAuthException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_auth_failed, 401),
            WebDavDirectoryNotFoundException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_directory_missing, 404),
            WebDavNotDirectoryException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_sync_not_directory),
            WebDavAccessDeniedException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_access_denied, 403),
            WebDavMissingConcurrencyTokenException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_sync_missing_condition),
            WebDavArchiveLeaseLostException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_sync_lock_lost),
            WebDavContentConflictException(423, sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.webdav_sync_locked, 423),
            WebDavContentConflictException(412, sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.sync_remote_changed, 412),
            WebDavApiException(503, sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.sync_request_failed, 503),
            GitHubApiException(500, sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.sync_request_failed, 500),
            GitHubContentConflictException(409, sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.sync_remote_changed, 409),
            TokenExpiredException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.github_token_expired_message, 401),
            UnknownHostException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.comment_error_network),
            ConnectException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.comment_error_network),
            SocketTimeoutException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.comment_error_network),
            SSLHandshakeException(sensitive) to SyncProtocolUpgradeError(CoreCommonR.string.comment_error_network)
        )
        for ((error, expected) in cases) {
            val logs = mutableListOf<String>()
            val model = SyncProtocolUpgradeViewModel(
                flowOf(emptyList()), { _, _ -> }, startupTargetsFlow = flowOf(setOf(target)),
                performImmediateSync = { _, _ -> Result.failure(error) }, logFailure = logs::add
            ).also(models::add)
            runCurrent()
            model.openConfirmation()
            model.setAllDevicesUpdated(true)
            model.confirm()
            runCurrent()

            assertTrue(model.uiState.value.hasError)
            assertEquals(expected, model.uiState.value.errorDetail)
            val expectedLog = expected.httpStatus?.let { "${error.javaClass.simpleName} (HTTP $it)" }
                ?: error.javaClass.simpleName
            assertEquals(listOf(expectedLog), logs)
            assertFalse(model.uiState.value.toString().contains(sensitive))
        }
    }

    @Test
    fun `unknown failure messages stay private and retain the existing fallback`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val logs = mutableListOf<String>()
        val model = SyncProtocolUpgradeViewModel(
            flowOf(listOf(challenge)), { _, _ -> throw IOException("private response text") }, logFailure = logs::add
        ).also(models::add)
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()

        assertTrue(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)
        assertEquals(listOf("IOException"), logs)
    }

    @Test
    fun `retry clears the previous reason while the next sync is running`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val complete = CompletableDeferred<Unit>()
        var attempts = 0
        val model = SyncProtocolUpgradeViewModel(
            flowOf(emptyList()), { _, _ -> }, startupTargetsFlow = flowOf(setOf(target)),
            performImmediateSync = { _, _ ->
                if (++attempts == 1) Result.failure(WebDavApiException(503, "unavailable"))
                else {
                    complete.await()
                    Result.success(SyncResult(true, "completed"))
                }
            }, logFailure = {}
        ).also(models::add)
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(503, model.uiState.value.errorDetail?.httpStatus)

        model.confirm()
        assertFalse(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)
        runCurrent()
        assertTrue(model.uiState.value.isSyncing)
        complete.complete(Unit)
        runCurrent()
        assertFalse(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)
    }

    @Test
    fun `a new pending target and reopening a dismissed confirmation discard stale errors`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = MutableStateFlow(listOf(challenge))
        val other = SyncProtocolUpgradeChallenge("b".repeat(64), "2".repeat(64))
        val model = SyncProtocolUpgradeViewModel(
            pending, { _, _ -> throw WebDavAuthException("private") }, logFailure = {}
        ).also(models::add)
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertEquals(CoreCommonR.string.webdav_auth_failed, model.uiState.value.errorDetail?.messageRes)

        pending.value = listOf(other)
        runCurrent()
        assertEquals(other, model.uiState.value.challenge)
        assertFalse(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertTrue(model.uiState.value.hasError)
        assertTrue(model.dismissConfirmation())
        model.openConfirmation()
        assertFalse(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)
    }

    @Test
    fun `a newly detected challenge clears an earlier failure and requires a new declaration`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val changed = challenge.copy(fingerprint = "2".repeat(64))
        var attempts = 0
        val model = SyncProtocolUpgradeViewModel(
            flowOf(listOf(challenge)), { _, _ ->
                if (++attempts == 1) throw WebDavAuthException("private")
                throw SyncProtocolUpgradeRequiredException("new challenge", changed)
            }, logFailure = {}
        ).also(models::add)
        runCurrent()
        model.openConfirmation()
        model.setAllDevicesUpdated(true)
        model.confirm()
        runCurrent()
        assertTrue(model.uiState.value.hasError)
        model.confirm()
        runCurrent()
        assertEquals(changed, model.uiState.value.challenge)
        assertFalse(model.uiState.value.allDevicesUpdated)
        assertFalse(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)

        model.requestUpgrade(challenge) {}
        assertEquals(challenge, model.uiState.value.challenge)
        assertFalse(model.uiState.value.hasError)
        assertNull(model.uiState.value.errorDetail)
    }
}
