package moe.ouom.neriplayer.ui.screen.tab

import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDirectoryAvailabilityTest {
    @Test
    fun `readable SAF root remains available`() = runBlocking {
        val availability = resolveDownloadDirectoryAvailability(
            directoryUri = "content://provider/tree/root",
            isRootResolvable = { true }
        )

        assertTrue(availability is DownloadDirectoryAvailability.Available)
    }

    @Test
    fun `provider failure is not mislabeled as permission loss`() = runBlocking {
        val permissionLost = resolveDownloadDirectoryPermissionLost(
            directoryUri = "content://provider/tree/old-root",
            isRootResolvable = {
                throw ManagedDownloadRootProviderException(
                    reference = "content://provider/tree/old-root",
                    cause = IllegalStateException("provider returned null document cursor")
                )
            }
        )

        assertFalse(permissionLost)
    }

    @Test
    fun `availability keeps provider failure distinct from an unavailable root`() = runBlocking {
        val providerFailure = resolveDownloadDirectoryAvailability(
            directoryUri = "content://provider/tree/root",
            isRootResolvable = {
                throw ManagedDownloadRootProviderException(
                    reference = "content://provider/tree/root",
                    cause = IllegalStateException("provider busy")
                )
            }
        )
        val unavailable = resolveDownloadDirectoryAvailability(
            directoryUri = "content://provider/tree/root",
            isRootResolvable = { false }
        )

        assertTrue(providerFailure is DownloadDirectoryAvailability.ProviderFailure)
        assertTrue(unavailable is DownloadDirectoryAvailability.Unavailable)
    }

    @Test
    fun `null cursor provider failure stays retryable and does not look like permission loss`() =
        runBlocking {
            val availability = resolveDownloadDirectoryAvailability(
                directoryUri = "content://provider/tree/root",
                isRootResolvable = {
                    throw ManagedDownloadRootProviderException(
                        reference = "content://provider/tree/root",
                        cause = IllegalStateException("provider returned null document cursor")
                    )
                },
                timeoutMs = 100L
            )

            assertTrue(availability is DownloadDirectoryAvailability.ProviderFailure)
            assertFalse(
                resolveDownloadDirectoryPermissionLost(
                    directoryUri = "content://provider/tree/root",
                    isRootResolvable = {
                        throw ManagedDownloadRootProviderException(
                            reference = "content://provider/tree/root",
                            cause = IllegalStateException("provider returned null document cursor")
                        )
                    },
                    timeoutMs = 100L
                )
            )
        }

    @Test
    fun `unexpected provider exception is normalized to retryable failure`() = runBlocking {
        val availability = resolveDownloadDirectoryAvailability(
            directoryUri = "content://provider/tree/root",
            isRootResolvable = {
                throw IllegalStateException("provider cursor failure")
            },
            timeoutMs = 100L
        )

        assertTrue(availability is DownloadDirectoryAvailability.ProviderFailure)
        val failure = availability as DownloadDirectoryAvailability.ProviderFailure
        assertTrue(failure.error.cause is IllegalStateException)
    }

    @Test
    fun `slow provider probe returns retryable failure at the soft deadline`() = runBlocking {
        val startedAt = System.nanoTime()
        val availability = resolveDownloadDirectoryAvailability(
            directoryUri = "content://provider/tree/root",
            isRootResolvable = {
                delay(250L)
                true
            },
            timeoutMs = 25L
        )
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue(availability is DownloadDirectoryAvailability.ProviderFailure)
        assertTrue("probe exceeded soft deadline: ${elapsedMs}ms", elapsedMs < 200L)
        val failure = availability as DownloadDirectoryAvailability.ProviderFailure
        assertTrue(failure.error.cause?.message?.contains("timed out") == true)
    }

    @Test
    fun `default root is never reported as permission lost`() = runBlocking {
        var probed = false

        val permissionLost = resolveDownloadDirectoryPermissionLost(
            directoryUri = null,
            isRootResolvable = {
                probed = true
                false
            }
        )

        assertFalse(permissionLost)
        assertFalse(probed)
    }

    @Test(expected = CancellationException::class)
    fun `availability probe preserves coroutine cancellation`() {
        runBlocking {
            resolveDownloadDirectoryPermissionLost(
                directoryUri = "content://provider/tree/root",
                isRootResolvable = { throw CancellationException("cancelled") }
            )
        }
    }
}
