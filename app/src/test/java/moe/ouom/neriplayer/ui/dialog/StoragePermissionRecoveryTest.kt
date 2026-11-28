package moe.ouom.neriplayer.ui.dialog

import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.StoragePermissionFallback
import moe.ouom.neriplayer.data.model.playback.StoragePermissionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePermissionRecoveryTest {

    private val authority = "content://com.android.externalstorage.documents"
    private val ringtoneTree = "$authority/tree/primary%3A%E9%9F%B3%E9%A2%91%2F%E9%93%83%E5%A3%B0"
    private val ringtoneSong = "$ringtoneTree/document/primary%3A%E9%9F%B3%E9%A2%91%2F%E9%93%83%E5%A3%B0%2Fnetease%20-%20ReoNa%20-%20Amore.mp3"

    private fun localEvent(reference: String?, fallback: StoragePermissionFallback = StoragePermissionFallback.SKIPPED) =
        PlayerEvent.StoragePermissionLost(StoragePermissionScope.LOCAL_MEDIA, fallback, reference)

    private val downloadEvent = PlayerEvent.StoragePermissionLost(
        StoragePermissionScope.DOWNLOAD_DIRECTORY, StoragePermissionFallback.STREAMING
    )

    @Test
    fun `local prompt targets the tree that owns the lost document`() {
        val prompt = storagePermissionPrompt(localEvent(ringtoneSong), downloadDirectoryUri = null)
        assertEquals(ringtoneTree, prompt.targetTreeUri)
        assertEquals("音频/铃声", storagePermissionDirectoryLabel(prompt.targetTreeUri))
    }

    @Test
    fun `download prompt targets the configured download directory`() {
        val prompt = storagePermissionPrompt(downloadEvent, downloadDirectoryUri = ringtoneTree)
        assertEquals(ringtoneTree, prompt.targetTreeUri)
    }

    @Test
    fun `references outside SAF trees cannot be reauthorized from the prompt`() {
        assertNull(storagePermissionPrompt(localEvent("content://media/external/audio/media/42"), null).targetTreeUri)
        assertNull(storagePermissionPrompt(localEvent("/storage/emulated/0/Music/a.flac"), null).targetTreeUri)
        assertNull(storagePermissionPrompt(downloadEvent, downloadDirectoryUri = null).targetTreeUri)
    }

    @Test
    fun `local reauthorization accepts the same tree or a parent tree only`() {
        val prompt = storagePermissionPrompt(localEvent(ringtoneSong), null)
        assertTrue(pickedTreeRestoresAccess(prompt, ringtoneTree))
        assertTrue(pickedTreeRestoresAccess(prompt, "$authority/tree/primary%3A%E9%9F%B3%E9%A2%91"))
        assertTrue(pickedTreeRestoresAccess(prompt, "$authority/tree/primary%3A"))
        assertFalse(pickedTreeRestoresAccess(prompt, "$authority/tree/primary%3AMusic"))
        assertFalse(pickedTreeRestoresAccess(prompt, "$authority/tree/primary%3A%E9%9F%B3%E9%A2%91%2F%E9%93%83"))
        assertFalse(pickedTreeRestoresAccess(prompt, "$authority/tree/1234-5678%3A%E9%9F%B3%E9%A2%91"))
        assertFalse(pickedTreeRestoresAccess(prompt, "content://other.provider/tree/primary%3A"))
    }

    @Test
    fun `download reauthorization requires the same directory identity`() {
        val prompt = storagePermissionPrompt(downloadEvent, ringtoneTree)
        assertTrue(pickedTreeRestoresAccess(prompt, "$ringtoneTree/"))
        assertFalse(pickedTreeRestoresAccess(prompt, "$authority/tree/primary%3A%E9%9F%B3%E9%A2%91"))
    }

    @Test
    fun `opaque provider ids only match exactly`() {
        assertTrue(treeCoversDocument("content://p/tree/abc", "content://p/tree/abc/document/abc"))
        assertFalse(treeCoversDocument("content://p/tree/a%3A", "content://p/tree/a%3A/document/a%3Ab"))
    }

    @Test
    fun `messages explain which fallback was applied`() {
        assertEquals(AppR.string.storage_permission_lost_download_streaming, storagePermissionMessageRes(downloadEvent))
        assertEquals(
            AppR.string.storage_permission_lost_download_check,
            storagePermissionMessageRes(downloadEvent.copy(fallback = StoragePermissionFallback.NONE))
        )
        assertEquals(
            AppR.string.storage_permission_lost_local_streaming,
            storagePermissionMessageRes(localEvent(ringtoneSong, StoragePermissionFallback.STREAMING))
        )
        assertEquals(AppR.string.storage_permission_lost_local_skipped, storagePermissionMessageRes(localEvent(ringtoneSong)))
    }

    @Test
    fun `prompt state shows each directory once and keeps skip notices after dismissal`() {
        val state = StoragePermissionPromptState()
        val skipped = storagePermissionPrompt(localEvent(ringtoneSong), null)
        val streaming = storagePermissionPrompt(localEvent(ringtoneSong, StoragePermissionFallback.STREAMING), null)

        assertEquals(StoragePermissionPromptAction.SHOW, state.offer(skipped))
        assertEquals(StoragePermissionPromptAction.IGNORE, state.offer(skipped))
        state.dismiss()
        assertNull(state.current)
        assertEquals(StoragePermissionPromptAction.NOTICE, state.offer(skipped))
        assertEquals(StoragePermissionPromptAction.IGNORE, state.offer(streaming))
    }

    @Test
    fun `resolved prompts can warn again if the grant is lost later`() {
        val state = StoragePermissionPromptState()
        val prompt = storagePermissionPrompt(downloadEvent, ringtoneTree)
        state.offer(prompt)
        state.resolve()
        assertEquals(StoragePermissionPromptAction.SHOW, state.offer(prompt))
    }

    @Test
    fun `another visible prompt is not replaced`() {
        val state = StoragePermissionPromptState()
        state.offer(storagePermissionPrompt(downloadEvent, ringtoneTree))
        val other = storagePermissionPrompt(localEvent("$authority/tree/primary%3AMusic/document/primary%3AMusic%2Fa.flac"), null)
        assertEquals(StoragePermissionPromptAction.NOTICE, state.offer(other))
        assertEquals(ringtoneTree, state.current?.targetTreeUri)
    }

    @Test
    fun `pending reauthorization survives saved state round trips`() {
        listOf(
            storagePermissionPrompt(localEvent(ringtoneSong), null),
            storagePermissionPrompt(downloadEvent, ringtoneTree),
            storagePermissionPrompt(localEvent("content://media/external/audio/media/42"), null)
        ).forEach { prompt ->
            val restored = restoreStoragePermissionPrompt(prompt.toSavedState())
            assertEquals(prompt, restored)
            assertEquals(prompt.key, restored?.key)
        }
    }

    @Test
    fun `corrupted saved state is ignored instead of crashing`() {
        assertNull(restoreStoragePermissionPrompt(emptyList()))
        assertNull(restoreStoragePermissionPrompt(listOf("UNKNOWN", "SKIPPED", "", "")))
        assertNull(restoreStoragePermissionPrompt(listOf("LOCAL_MEDIA", "UNKNOWN", "", "")))
    }

    @Test
    fun `reauthorization outcome requires grant match and verified access`() {
        assertEquals(StorageReauthorizationOutcome.FAILED, storageReauthorizationOutcome(false, true, true))
        assertEquals(StorageReauthorizationOutcome.MISMATCH, storageReauthorizationOutcome(true, false, false))
        assertEquals(StorageReauthorizationOutcome.FAILED, storageReauthorizationOutcome(true, true, false))
        assertEquals(StorageReauthorizationOutcome.RESTORED, storageReauthorizationOutcome(true, true, true))
    }
}
