package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearFenceReleaseResult
import moe.ouom.neriplayer.core.download.execution.clear.DownloadClearOwnership
import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionSchedule
import moe.ouom.neriplayer.core.download.execution.recovery.loadExplicitDownloadResumeCandidates
import moe.ouom.neriplayer.core.download.execution.recovery.resumeExplicitDownload
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.model.ExplicitDownloadResumeCandidate
import moe.ouom.neriplayer.data.model.SongItem
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadClearFenceStoreInstrumentedTest {
    @Test
    fun clearFencePersistsAcrossStoreReads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PersistentDownloadClearFenceStore.clear(context)
        try {
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
            val clearEpoch = PersistentDownloadClearFenceStore.beginClear()
            assertTrue(PersistentDownloadClearFenceStore.activate(context))
            assertTrue(
                PersistentDownloadClearFenceStore.setOwnership(
                    context = context,
                    expectedEpoch = clearEpoch,
                    ownership = DownloadClearOwnership()
                )
            )
            assertTrue(PersistentDownloadClearFenceStore.isActive(context))
        } finally {
            assertTrue(PersistentDownloadClearFenceStore.clear(context))
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        }
    }

    @Test
    fun clearRequestHidesAndRejectsExplicitResumeBeforeRoomAccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PersistentDownloadClearFenceStore.clear(context)
        try {
            val clearEpoch = PersistentDownloadClearFenceStore.beginClear()
            assertTrue(PersistentDownloadClearFenceStore.activate(context))
            assertTrue(
                PersistentDownloadClearFenceStore.setOwnership(
                    context = context,
                    expectedEpoch = clearEpoch,
                    ownership = DownloadClearOwnership()
                )
            )

            assertTrue(PersistentDownloadClearFenceStore.isActive(context))
            assertTrue(loadExplicitDownloadResumeCandidates(context).isEmpty())
            assertTrue(
                resumeExplicitDownload(
                    context = context,
                    candidate = ExplicitDownloadResumeCandidate(
                        operationId = "clear-fence-explicit-resume",
                        song = SongItem(
                            id = 1L,
                            name = "Song",
                            artist = "Artist",
                            album = "Album",
                            albumId = 1L,
                            durationMs = 1_000L,
                            coverUrl = null
                        ),
                        queueOrder = 0
                    )
                ) is DownloadExecutionSchedule.Rejected
            )
        } finally {
            assertTrue(PersistentDownloadClearFenceStore.clear(context))
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        }
    }

    @Test
    fun repeatedClearRequestReusesCurrentFenceUntilOwnerCaptureCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PersistentDownloadClearFenceStore.clear(context)
        val firstEpoch = PersistentDownloadClearFenceStore.beginClear()
        try {
            assertTrue(PersistentDownloadClearFenceStore.activate(context))
            val secondEpoch = PersistentDownloadClearFenceStore.beginClear()

            assertEquals(firstEpoch, secondEpoch)
            assertEquals(
                DownloadClearFenceReleaseResult.FAILED,
                PersistentDownloadClearFenceStore.clearIfCurrent(context, firstEpoch)
            )
            assertTrue(PersistentDownloadClearFenceStore.isActive(context))
            assertEquals(
                "blocked",
                PersistentDownloadClearFenceStore.withSchedulingPermit(
                    context = context,
                    onFenceActive = { "blocked" },
                    schedule = { "scheduled" }
                )
            )

            assertTrue(
                PersistentDownloadClearFenceStore.setOwnership(
                    context = context,
                    expectedEpoch = secondEpoch,
                    ownership = DownloadClearOwnership()
                )
            )
            assertEquals(
                DownloadClearFenceReleaseResult.RELEASED,
                PersistentDownloadClearFenceStore.clearIfCurrent(context, secondEpoch)
            )
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        } finally {
            assertTrue(PersistentDownloadClearFenceStore.clear(context))
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        }
    }

    @Test
    fun clearActivationDoesNotBlockCurrentScheduleAndRejectsLateScheduling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val enteredSchedulingPermit = CountDownLatch(1)
        val releaseSchedulingPermit = CountDownLatch(1)
        val activationFinished = CountDownLatch(1)
        val activationSucceeded = AtomicBoolean(false)
        var firstScheduleResult: String? = null
        PersistentDownloadClearFenceStore.clear(context)
        val schedulingThread = Thread {
            firstScheduleResult = PersistentDownloadClearFenceStore.withSchedulingPermit(
                context = context,
                onFenceActive = { "blocked" },
                schedule = {
                    enteredSchedulingPermit.countDown()
                    releaseSchedulingPermit.await()
                    "scheduled"
                }
            )
        }
        try {
            schedulingThread.start()
            assertTrue(enteredSchedulingPermit.await(1, TimeUnit.SECONDS))
            val clearEpoch = PersistentDownloadClearFenceStore.beginClear()
            val activationThread = Thread {
                activationSucceeded.set(PersistentDownloadClearFenceStore.activate(context))
                activationFinished.countDown()
            }
            activationThread.start()

            assertTrue(activationFinished.await(200, TimeUnit.MILLISECONDS))
            assertTrue(activationSucceeded.get())
            releaseSchedulingPermit.countDown()
            schedulingThread.join(1_000L)
            activationThread.join(1_000L)

            assertFalse(schedulingThread.isAlive)
            assertFalse(activationThread.isAlive)
            assertEquals("scheduled", firstScheduleResult)
            assertTrue(
                PersistentDownloadClearFenceStore.setOwnership(
                    context = context,
                    expectedEpoch = clearEpoch,
                    ownership = DownloadClearOwnership()
                )
            )
            assertEquals(
                "blocked",
                PersistentDownloadClearFenceStore.withSchedulingPermit(
                    context = context,
                    onFenceActive = { "blocked" },
                    schedule = { "scheduled" }
                )
            )
        } finally {
            releaseSchedulingPermit.countDown()
            assertTrue(PersistentDownloadClearFenceStore.clear(context))
            assertFalse(PersistentDownloadClearFenceStore.isActive(context))
        }
    }
}
