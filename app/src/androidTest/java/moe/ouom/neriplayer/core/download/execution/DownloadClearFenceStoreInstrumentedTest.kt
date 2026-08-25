package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ExplicitDownloadResumeCandidate
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
            assertTrue(PersistentDownloadClearFenceStore.activate(context))
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
            PersistentDownloadClearFenceStore.beginClear()

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
    fun supersededClearCannotRemoveTheNewerFence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PersistentDownloadClearFenceStore.clear(context)
        val firstEpoch = PersistentDownloadClearFenceStore.beginClear()
        try {
            assertTrue(PersistentDownloadClearFenceStore.activate(context))
            val secondEpoch = PersistentDownloadClearFenceStore.beginClear()

            assertEquals(
                DownloadClearFenceReleaseResult.SUPERSEDED,
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

            assertTrue(PersistentDownloadClearFenceStore.activate(context))
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
    fun clearActivationWaitsForCurrentPermitAndRejectsLateScheduling() {
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
            PersistentDownloadClearFenceStore.beginClear()
            val activationThread = Thread {
                activationSucceeded.set(PersistentDownloadClearFenceStore.activate(context))
                activationFinished.countDown()
            }
            activationThread.start()

            assertFalse(activationFinished.await(200, TimeUnit.MILLISECONDS))
            releaseSchedulingPermit.countDown()
            schedulingThread.join(1_000L)
            activationThread.join(1_000L)

            assertFalse(schedulingThread.isAlive)
            assertFalse(activationThread.isAlive)
            assertTrue(activationSucceeded.get())
            assertEquals("scheduled", firstScheduleResult)
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
