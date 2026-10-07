package moe.ouom.neriplayer.core.download.execution.clear

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHostTestSupport
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.data.model.download.execution.DownloadClearPurpose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class PersistentDownloadClearFenceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val store = PersistentDownloadClearFenceStore

    @Before
    fun resetBefore() {
        resetFence()
    }

    @After
    fun resetAfter() {
        resetFence()
    }

    @Test
    fun `pending clear is reused and only upgrades toward full library delete`() {
        val context = fenceContext()
        val first = store.beginClear(
            purpose = DownloadClearPurpose.TASK_PROGRESS,
            ownership = DownloadClearOwnership(setOf(" op-1 "), setOf("key-1"))
        )

        assertEquals(DownloadClearOwnership(setOf("op-1"), setOf("key-1")), store.ownership(context))
        assertTrue(store.isTaskClearActive(context))
        assertTrue(store.isTaskProgressActive(context))
        assertEquals(
            first,
            store.beginClear(ownership = DownloadClearOwnership(operationIds = setOf("op-2")))
        )
        assertEquals(DownloadClearOwnership(operationIds = setOf("op-2")), store.ownership(context))

        assertEquals(first, store.beginClear(DownloadClearPurpose.FULL_LIBRARY_DELETE))
        assertEquals(
            first,
            store.beginClear(ownership = DownloadClearOwnership(operationIds = setOf("op-3")))
        )
        assertTrue(store.isTaskClearActive(context))
        assertFalse(store.isTaskProgressActive(context))
        assertEquals(DownloadClearOwnership(operationIds = setOf("op-2")), store.ownership(context))

        assertTrue(store.abandonUnpersistedRequestIfCurrent(context, first))
        assertFalse(store.isTaskClearActive(context))
        assertNull(store.ownership(context))
    }

    @Test
    fun `unpersisted request is abandoned only for the current epoch without durable evidence`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        val context = fenceContext(preferences)
        val epoch = store.beginClear()

        assertFalse(store.abandonUnpersistedRequestIfCurrent(context, epoch + 1))
        preferences.values[ACTIVE_KEY] = true
        assertFalse(store.abandonUnpersistedRequestIfCurrent(context, epoch))
        preferences.values.remove(ACTIVE_KEY)
        assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, "root", listOf(song())))
        assertFalse(store.abandonUnpersistedRequestIfCurrent(context, epoch))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.clear(context))

        assertTrue(store.abandonUnpersistedRequestIfCurrent(context, epoch))
        assertFalse(store.abandonUnpersistedRequestIfCurrent(context, epoch))
        assertFalse(store.isActive(context))
    }

    @Test
    fun `active purpose prefers a readable persisted purpose then a pending delete intent`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        val context = fenceContext(preferences)

        assertEquals(DownloadClearPurpose.TASK_PROGRESS, store.activePurpose(context))
        preferences.values[PURPOSE_KEY] = DownloadClearPurpose.FULL_LIBRARY_DELETE.name
        assertEquals(DownloadClearPurpose.FULL_LIBRARY_DELETE, store.activePurpose(context))
        preferences.values[PURPOSE_KEY] = "UNKNOWN_PURPOSE"
        assertEquals(DownloadClearPurpose.TASK_PROGRESS, store.activePurpose(context))

        assertTrue(PersistentDownloadedSongDeleteIntentStore.begin(context, "root", listOf(song())))
        assertEquals(DownloadClearPurpose.FULL_LIBRARY_DELETE, store.activePurpose(context))
        assertTrue(PersistentDownloadedSongDeleteIntentStore.clear(context))

        assertEquals(DownloadClearPurpose.TASK_PROGRESS, store.activePurpose(fenceContext(preferences = null)))
        val unreadable = mock(SharedPreferences::class.java)
        `when`(unreadable.getString(anyString(), any())).thenThrow(IllegalStateException("disk"))
        assertEquals(DownloadClearPurpose.TASK_PROGRESS, store.activePurpose(fenceContext(unreadable)))
    }

    @Test
    fun `requested timestamp is reported only when positive and readable`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()

        assertNull(store.requestedAtMs(fenceContext(preferences)))
        preferences.values[REQUESTED_AT_MS_KEY] = 1_234L
        assertEquals(1_234L, store.requestedAtMs(fenceContext(preferences)))
        assertNull(store.requestedAtMs(fenceContext(preferences = null)))
        val unreadable = mock(SharedPreferences::class.java)
        `when`(unreadable.getLong(anyString(), anyLong())).thenThrow(IllegalStateException("disk"))
        assertNull(store.requestedAtMs(fenceContext(unreadable)))
    }

    @Test
    fun `task progress fence keeps blocking release until its owners are captured`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        val context = fenceContext(preferences)
        val ownership = DownloadClearOwnership(setOf("op-1"), setOf("key-1"))
        val startedAt = System.currentTimeMillis()
        val epoch = store.beginClear(DownloadClearPurpose.TASK_PROGRESS, ownership)

        assertTrue(store.activate(context, ownership))
        val requestedAt = requireNotNull(store.requestedAtMs(context))
        assertTrue(requestedAt in startedAt..System.currentTimeMillis())
        assertTrue(store.hasPersistedFence(context))
        assertFalse(store.isOwnershipCaptureComplete(context))
        assertEquals(DownloadClearFenceReleaseResult.FAILED, store.clearIfCurrent(context, epoch))
        assertEquals(DownloadClearFenceReleaseResult.SUPERSEDED, store.clearIfCurrent(context, epoch + 1))

        assertTrue(store.setOwnership(context, epoch, DownloadClearOwnership(operationIds = setOf("op-2"))))
        assertTrue(store.isOwnershipCaptureComplete(context))
        assertEquals(
            DownloadClearOwnership(setOf("op-1", "op-2"), setOf("key-1")),
            store.ownership(context)
        )
        assertEquals(DownloadClearFenceReleaseResult.RELEASED, store.clearIfCurrent(context, epoch))

        assertFalse(store.isTaskClearActive(context))
        assertFalse(store.hasPersistedFence(context))
        assertNull(store.requestedAtMs(context))
        assertFalse(store.isOwnershipCaptureComplete(context))
    }

    @Test
    fun `persisted fences from an earlier process release according to their purpose`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        preferences.values[ACTIVE_KEY] = true
        val context = fenceContext(preferences)
        val epoch = store.currentEpoch(context)

        assertEquals(DownloadClearFenceReleaseResult.FAILED, store.clearIfCurrent(context, epoch))
        assertTrue(preferences.values.getValue(ACTIVE_KEY) as Boolean)

        preferences.values[PURPOSE_KEY] = DownloadClearPurpose.FULL_LIBRARY_DELETE.name
        assertEquals(DownloadClearFenceReleaseResult.RELEASED, store.clearIfCurrent(context, epoch))
        assertTrue(preferences.values.isEmpty())
    }

    @Test
    fun `release fails when the fence cannot be durably removed`() {
        val epoch = store.currentEpoch()

        assertEquals(
            DownloadClearFenceReleaseResult.RELEASED,
            store.clearIfCurrent(fenceContext(), epoch)
        )
        assertEquals(
            DownloadClearFenceReleaseResult.FAILED,
            store.clearIfCurrent(fenceContext(preferences = null), epoch)
        )
        val rejecting = mock(SharedPreferences::class.java)
        `when`(rejecting.edit()).thenReturn(mock(SharedPreferences.Editor::class.java))
        assertEquals(
            DownloadClearFenceReleaseResult.FAILED,
            store.clearIfCurrent(fenceContext(rejecting), epoch)
        )
    }

    @Test
    fun `owner capture is complete only when both owner sets are readable`() {
        val preferences = DownloadExecutionHostTestSupport.StatefulSharedPreferences()
        val context = fenceContext(preferences)
        store.beginClear()

        assertFalse(store.isOwnershipCaptureComplete(fenceContext(preferences = null)))
        assertFalse(store.isOwnershipCaptureComplete(context))
        preferences.values[OWNER_CAPTURE_COMPLETE_KEY] = true
        assertFalse(store.isOwnershipCaptureComplete(context))
        preferences.values[OWNER_OPERATION_IDS_KEY] = setOf("op-1")
        assertFalse(store.isOwnershipCaptureComplete(context))
        preferences.values[OWNER_STABLE_KEYS_KEY] = setOf("key-1")
        assertTrue(store.isOwnershipCaptureComplete(context))

        val unreadableOwners = mock(SharedPreferences::class.java)
        `when`(unreadableOwners.getBoolean(anyString(), anyBoolean())).thenReturn(true)
        `when`(unreadableOwners.contains(anyString())).thenReturn(true)
        `when`(unreadableOwners.getStringSet(anyString(), any())).thenThrow(IllegalStateException("disk"))
        assertFalse(store.isOwnershipCaptureComplete(fenceContext(unreadableOwners)))
        val unreadableFlag = mock(SharedPreferences::class.java)
        `when`(unreadableFlag.getBoolean(anyString(), anyBoolean())).thenThrow(IllegalStateException("disk"))
        assertFalse(store.isOwnershipCaptureComplete(fenceContext(unreadableFlag)))
    }

    @Test
    fun `task clear blocks anonymous callers and captured owners only`() {
        val context = fenceContext()
        store.beginClear(
            purpose = DownloadClearPurpose.TASK_PROGRESS,
            ownership = DownloadClearOwnership(setOf("op-1"), setOf("key-1"))
        )

        assertTrue(store.isBlocked(context, stableKey = null, operationId = "   "))
        assertTrue(store.isBlocked(context, stableKey = " key-1 "))
        assertTrue(store.isBlocked(context, operationId = "op-1"))
        assertFalse(store.isBlocked(context, stableKey = "key-2", operationId = "op-2"))
    }

    private fun resetFence() {
        val context = fenceContext(filesDir = temporaryFolder.newFolder())
        store.abandonUnpersistedRequestIfCurrent(context, store.currentEpoch(context))
        val epoch = store.beginClear(DownloadClearPurpose.TASK_PROGRESS)
        assertTrue(store.abandonUnpersistedRequestIfCurrent(context, epoch))
    }

    private fun fenceContext(
        preferences: SharedPreferences? = DownloadExecutionHostTestSupport.StatefulSharedPreferences(),
        filesDir: File = temporaryFolder.root
    ): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        return context
    }

    private fun song(): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = "song",
            artist = "artist",
            album = "album",
            filePath = "/library/song.mp3",
            fileSize = 10L,
            downloadTime = 1L,
            stableKey = "1|netease|"
        )
    }

    private companion object {
        const val ACTIVE_KEY = "active"
        const val REQUESTED_AT_MS_KEY = "requested_at_ms"
        const val PURPOSE_KEY = "purpose"
        const val OWNER_OPERATION_IDS_KEY = "owner_operation_ids"
        const val OWNER_STABLE_KEYS_KEY = "owner_stable_keys"
        const val OWNER_CAPTURE_COMPLETE_KEY = "owner_capture_complete"
    }
}
