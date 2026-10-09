package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadAudioPublicationReceiptTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storage = ManagedDownloadStorage
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var rootDir: File
    private lateinit var root: ManagedDownloadRootHandle.FileRoot
    private lateinit var pendingFile: File
    private lateinit var pending: StoredEntry
    private lateinit var target: File

    @Before
    fun setUp() {
        rootDir = temporaryFolder.newFolder("library")
        root = ManagedDownloadRootHandle.FileRoot(rootDir)
        pendingFile = File(rootDir, "$FINAL_NAME.npdl_pending.op-1.pending").apply { writeText("complete audio") }
        pending = entry(pendingFile)
        target = File(rootDir, FINAL_NAME)
    }

    @After
    fun tearDown() {
        storage.clearTreeDirectoryCache()
        storage.invalidateSnapshotCache(context)
    }

    @Test
    fun `recording a publication target requires complete pending identity`() {
        assertThrows(IOException::class.java) {
            storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath)
        }

        writePendingMetadata(JSONObject().put("name", "Song"))
        assertThrows(IOException::class.java) {
            storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath)
        }

        writePendingMetadata(identity())
        target.writeText("complete audio")
        storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath, publicationFileIdentity(target.absolutePath))

        val receipt = JSONObject(File(rootDir, ".tmp/$FINAL_NAME.npmeta.pending.json").readText())
            .getJSONObject("audioPublicationReceipt")
        assertEquals(pending.name, receipt.getString("sourceName"))
        assertEquals(target.absolutePath, receipt.getString("targetReference"))
        assertEquals(FINAL_NAME, receipt.getString("targetName"))
        assertEquals(64, receipt.getString("sha256").length)
    }

    @Test
    fun `verified targets must match the recorded receipt and digest`() {
        writePendingMetadata(identity())
        target.writeText("complete audio")
        storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath, publicationFileIdentity(target.absolutePath))

        assertTrue(verify())
        assertFalse(verify(pendingName = "Other.mp3.npdl_pending.op-2.pending"))
        assertFalse(verify(targetReference = File(rootDir, "Other.mp3").absolutePath))
        assertFalse(verify(pendingReference = File(rootDir, "missing.pending").absolutePath))

        target.writeText("truncated")
        assertFalse(verify())
        assertTrue(verify(requireCompleteTarget = false))

        pendingFile.delete()
        assertFalse(verify(requireCompleteTarget = false))
    }

    @Test
    fun `publication pending marker is written to formal metadata and sealed after verification`() {
        writePendingMetadata(identity())
        target.writeText("complete audio")
        storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath, publicationFileIdentity(target.absolutePath))

        storage.markAudioPublicationPending(context, root, pending, FINAL_NAME)
        val formal = File(rootDir, "$FINAL_NAME.npmeta.json")
        assertTrue(JSONObject(formal.readText()).getBoolean("audioPublicationPending"))
        assertTrue(storage.readAudioPublicationMetadata(context, root, FINAL_NAME)!!.getBoolean("audioPublicationPending"))

        storage.sealAudioPublicationReceipt(context, root, entry(target))

        val sealed = JSONObject(formal.readText())
        assertFalse(sealed.getBoolean("audioPublicationPending"))
        assertEquals(pending.name, sealed.getJSONObject("audioPublicationReceipt").getString("sourceName"))
    }

    @Test
    fun `sealing refuses to complete an unverified publication`() {
        writePendingMetadata(identity())
        target.writeText("complete audio")
        storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath, publicationFileIdentity(target.absolutePath))
        storage.markAudioPublicationPending(context, root, pending, FINAL_NAME)
        target.writeText("partial")

        assertThrows(IOException::class.java) {
            storage.sealAudioPublicationReceipt(context, root, entry(target))
        }
        assertTrue(JSONObject(File(rootDir, "$FINAL_NAME.npmeta.json").readText()).getBoolean("audioPublicationPending"))
    }

    @Test
    fun `pending marker refuses formal metadata owned by another download`() {
        writePendingMetadata(identity())
        storage.recordAudioPublicationTarget(context, root, pending, target.absolutePath)
        File(rootDir, "$FINAL_NAME.npmeta.json").writeText(identity(operationId = "op-other").toString())

        assertThrows(IOException::class.java) {
            storage.markAudioPublicationPending(context, root, pending, FINAL_NAME)
        }
        assertFalse(JSONObject(File(rootDir, "$FINAL_NAME.npmeta.json").readText()).has("audioPublicationPending"))
    }

    @Test
    fun `resume copy is refused without a verifiable receipt`() {
        writePendingMetadata(identity())

        assertFalse(
            storage.resumeAudioPublicationCopy(
                context, root, pending.name, FINAL_NAME, target.absolutePath, pending.reference
            )
        )
    }

    @Test
    fun `receipts survive metadata rewrites only for the same owner`() {
        val receipt = JSONObject().put("sourceName", "a")
        val previous = identity().put("audioPublicationPending", true).put("audioPublicationReceipt", receipt).toString()

        val sameOwner = JSONObject(preserveAudioPublicationReceipt(previous, identity().toString()))
        assertTrue(sameOwner.getBoolean("audioPublicationPending"))
        assertEquals("a", sameOwner.getJSONObject("audioPublicationReceipt").getString("sourceName"))

        val completed = identity().put("audioPublicationPending", false).toString()
        assertTrue(JSONObject(preserveAudioPublicationReceipt(previous, completed)).getBoolean("audioPublicationPending"))
        assertFalse(
            JSONObject(preserveAudioPublicationReceipt(previous, completed, allowPublicationCompletion = true))
                .getBoolean("audioPublicationPending")
        )

        val otherOwner = identity(operationId = "op-other").toString()
        assertEquals(otherOwner, preserveAudioPublicationReceipt(previous, otherOwner))
        val legacyOwner = JSONObject().put("stableKey", STABLE_KEY).toString()
        assertEquals(legacyOwner, preserveAudioPublicationReceipt(previous, legacyOwner))
        assertEquals("{}", preserveAudioPublicationReceipt("{broken", "{}"))
        assertEquals("{}", preserveAudioPublicationReceipt(null, "{}"))
    }

    @Test
    fun `publication references compare documents rather than raw strings`() {
        val document = "content://com.example.docs/tree/root/document/primary%3ASong.mp3"
        val sameDocument = "content://com.example.docs/document/primary%3ASong.mp3"

        assertTrue(samePublicationReference("/music/Song.mp3", "/music/Song.mp3"))
        assertFalse(samePublicationReference("", ""))
        assertFalse(samePublicationReference("/music/Song.mp3", document))
        assertTrue(samePublicationReference(document, sameDocument))
        assertFalse(samePublicationReference(document, "content://other.docs/document/primary%3ASong.mp3"))
        assertFalse(samePublicationReference(document, "content://com.example.docs/document/primary%3AOther.mp3"))
        assertFalse(samePublicationReference(document, "content://com.example.docs/not-a-document"))
    }

    @Test
    fun `empty publication targets are reclaimable only with a complete matching receipt`() {
        val receipt = JSONObject()
            .put("sourceName", pending.name)
            .put("sourceReference", pending.reference)
            .put("targetName", FINAL_NAME)
            .put("targetReference", target.absolutePath)
            .put("sha256", "a".repeat(64))
        val publication = identity().put("audioPublicationReceipt", receipt)
        val formal = identity().put("audioPublicationPending", true).put("audioFileName", FINAL_NAME)

        assertTrue(canReclaimEmptyPublicationTarget(formal, publication, STABLE_KEY, FINAL_NAME, target.absolutePath))
        assertFalse(canReclaimEmptyPublicationTarget(formal, identity(), STABLE_KEY, FINAL_NAME, target.absolutePath))
        assertFalse(canReclaimEmptyPublicationTarget(formal, publication, "other|key|", FINAL_NAME, target.absolutePath))
        assertFalse(
            canReclaimEmptyPublicationTarget(
                JSONObject(formal.toString()).put("audioPublicationPending", false),
                publication, STABLE_KEY, FINAL_NAME, target.absolutePath
            )
        )
        assertFalse(
            canReclaimEmptyPublicationTarget(formal, publication, STABLE_KEY, FINAL_NAME, File(rootDir, "x").absolutePath)
        )
        assertFalse(
            canReclaimEmptyPublicationTarget(
                formal,
                JSONObject(publication.toString()).put("audioPublicationReceipt", JSONObject(receipt.toString()).put("sha256", "short")),
                STABLE_KEY, FINAL_NAME, target.absolutePath
            )
        )
    }

    private fun verify(
        pendingName: String = pending.name,
        targetReference: String = target.absolutePath,
        pendingReference: String = pending.reference,
        requireCompleteTarget: Boolean = true
    ): Boolean {
        return storage.isVerifiedAudioPublicationTarget(
            context, root, pendingName, FINAL_NAME, targetReference, pendingReference, requireCompleteTarget
        )
    }

    private fun writePendingMetadata(metadata: JSONObject) {
        File(rootDir, "$FINAL_NAME.npmeta.pending.json").writeText(metadata.toString())
    }

    private fun identity(operationId: String = "op-1"): JSONObject {
        return JSONObject().put("stableKey", STABLE_KEY).put("operationId", operationId).put("name", "Song")
    }

    private fun entry(file: File): StoredEntry {
        return StoredEntry(file.name, file.absolutePath, file.absolutePath, file.absolutePath, file.length(), file.lastModified())
    }

    private companion object {
        const val FINAL_NAME = "Song.mp3"
        const val STABLE_KEY = "1|Album|"
    }
}
