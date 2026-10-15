package moe.ouom.neriplayer.data.local.media.curation

import android.app.PendingIntent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalMediaWriteAccessPlanTest {

    @Test
    fun `nothing pending needs no system consent`() {
        val plan = systemConsentPlan(emptyList()) { error("must not create a request") }

        assertEquals(LocalMediaWriteAccessPlan.Ready, plan)
    }

    @Test
    fun `large batches are split into requests the system accepts`() {
        val uris = (1..2_500).map { Uri.parse("content://media/external/audio/media/$it") }
        val batchSizes = mutableListOf<Int>()

        val plan = systemConsentPlan(uris) { batch ->
            batchSizes += batch.size
            mock(PendingIntent::class.java)
        }

        assertEquals(listOf(1_000, 1_000, 500), batchSizes)
        assertEquals(3, (plan as LocalMediaWriteAccessPlan.SystemConsent).requests.size)
    }

    @Test
    fun `a rejected request is reported instead of treated as already granted`() {
        val uris = listOf(Uri.parse("content://media/external/audio/media/1"))

        val plan = systemConsentPlan(uris) { throw IllegalArgumentException("rejected") }

        assertTrue(plan is LocalMediaWriteAccessPlan.Unavailable)
    }

    @Test
    fun `android 10 asks for the common folder of songs that are not writable`() {
        val plan = folderGrantPlan(
            listOf(
                StorageDocumentPath("primary", "Music/A"),
                null,
                StorageDocumentPath("primary", "Music/B/Disc 1")
            )
        )

        assertEquals(LocalMediaWriteAccessPlan.FolderGrant(StorageDocumentPath("primary", "Music")), plan)
        assertEquals(LocalMediaWriteAccessPlan.Ready, folderGrantPlan(emptyList()))
    }
}
