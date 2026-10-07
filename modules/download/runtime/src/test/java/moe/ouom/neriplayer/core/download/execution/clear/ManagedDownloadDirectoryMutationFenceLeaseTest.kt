package moe.ouom.neriplayer.core.download.execution.clear

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHostTestSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedDownloadDirectoryMutationFenceLeaseTest {
    private val context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt()))
            .thenReturn(DownloadExecutionHostTestSupport.StatefulSharedPreferences())
    }

    @Test
    fun `directory mutation waits for held leases and refuses new ones until it reopens`() = runTest {
        assertFalse(ManagedDownloadDirectoryMutationFence.isActive(context))
        val deleteLease = ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(context)
        assertNotNull(deleteLease)
        val mutation = async { ManagedDownloadDirectoryMutationFence.closeAndDrain() }
        try {
            runCurrent()
            assertFalse(mutation.isCompleted)
            assertTrue(ManagedDownloadDirectoryMutationFence.isActive(context))
            assertNull(ManagedDownloadDirectoryMutationFence.acquireRecoveryLeaseOrNull(context))
            assertNull(ManagedDownloadDirectoryMutationFence.acquireDeleteLeaseOrNull(context))

            deleteLease?.close()
            runCurrent()
            assertTrue(mutation.isCompleted)
        } finally {
            deleteLease?.close()
            mutation.await().close()
        }

        assertFalse(ManagedDownloadDirectoryMutationFence.isActive(context))
        val recoveryLease = ManagedDownloadDirectoryMutationFence.acquireRecoveryLeaseOrNull(context)
        assertNotNull(recoveryLease)
        val nextMutation = async { ManagedDownloadDirectoryMutationFence.closeAndDrain() }
        try {
            runCurrent()
            assertFalse(nextMutation.isCompleted)
        } finally {
            recoveryLease?.close()
            nextMutation.await().close()
        }
        assertFalse(ManagedDownloadDirectoryMutationFence.isActive(context))
    }
}
