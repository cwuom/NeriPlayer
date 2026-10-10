package moe.ouom.neriplayer.data.local.media.metadata

import android.net.Uri
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.File

class LocalMediaCompanionRecoveryEntryJsonTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var staging: File
    private lateinit var music: File
    private lateinit var lyrics: String

    @Before
    fun setUp() {
        staging = temporaryFolder.newFolder("staging").canonicalFile
        music = temporaryFolder.newFolder("music").canonicalFile
        lyrics = File(music, "song.lrc").absolutePath
    }

    @Test
    fun `blank, null and missing optional fields decode as absent`() {
        val body = JSONObject()
            .put("reference", lyrics)
            .put("createdByTransaction", true)
            .put("backupPath", JSONObject.NULL)
            .put("originalSha256", "   ")
            .put("expectedSha256", EMPTY_SHA256)
            .put("originalLastModifiedMs", 0)
            .put("fileIdentity", "1:2")
            .put("phase", "CREATED")

        assertEquals(
            LocalMediaCompanionRecoveryEntry(
                reference = lyrics,
                backupFile = null,
                originalSha256 = null,
                expectedSha256 = EMPTY_SHA256,
                originalLastModifiedMs = null,
                createdByTransaction = true,
                fileIdentity = "1:2",
                phase = "CREATED"
            ),
            LocalMediaCompanionRecoveryEntry.fromJson(body, staging)
        )
    }

    @Test
    fun `entries round trip through their journal json`() {
        val entry = LocalMediaCompanionRecoveryEntry(
            reference = lyrics,
            backupFile = File(staging, "companion-backup-1.bin"),
            originalSha256 = "a".repeat(64),
            expectedSha256 = "b".repeat(64),
            originalLastModifiedMs = 1_700_000_000_000L,
            createdByTransaction = false,
            fileIdentity = "7:9",
            writeIdentityVerified = true,
            deferredDelete = true,
            intendedFile = File(staging, "companion-intended-1.bin"),
            phase = "WRITTEN",
            previousSha256 = "c".repeat(64),
            previousIdentity = "7:8",
            stagedFile = File(music, ".song.lrc.companion-3.tmp"),
            restoreInputSha256 = "d".repeat(64),
            stagedIdentity = "7:10"
        )

        assertEquals(entry, LocalMediaCompanionRecoveryEntry.fromJson(entry.toJson(), staging))
    }

    @Test
    fun `entries reject foreign files, unproven originals and inconsistent phases`() {
        fun created() = JSONObject().put("reference", lyrics).put("createdByTransaction", true)

        expectFailure<IllegalArgumentException> {
            decode(created().put("backupPath", File(music, "companion-backup-1.bin").absolutePath))
        }
        expectFailure<IllegalArgumentException> {
            decode(
                JSONObject().put("reference", lyrics).put("createdByTransaction", false)
                    .put("backupPath", File(staging, "companion-backup-1.bin").absolutePath)
                    .put("originalSha256", "a".repeat(63))
            )
        }
        expectFailure<IllegalArgumentException> { decode(created().put("phase", "PUBLISHED")) }
        expectFailure<IllegalArgumentException> {
            decode(created().put("intendedPath", File(staging, "companion-intended-1.bin").absolutePath))
        }
        expectFailure<IllegalArgumentException> {
            decode(created().put("phase", "WRITTEN").put("stagedPath", File(staging, ".song.lrc.companion-3.tmp").absolutePath))
        }
    }

    @Test
    fun `staged files of file uri companions must sit next to their target`() {
        val reference = "file://$lyrics"
        val uri = mock(Uri::class.java)
        doReturn(lyrics).`when`(uri).path
        val staged = File(music, ".song.lrc.companion-5.tmp")
        val body = JSONObject()
            .put("reference", reference)
            .put("createdByTransaction", true)
            .put("phase", "ATOMIC_WRITE_INTENT")
            .put("stagedPath", staged.absolutePath)

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertEquals(staged, decode(body).stagedFile)
            expectFailure<IllegalArgumentException> {
                decode(JSONObject(body.toString()).put("stagedPath", File(music, "song.lrc.companion-5.tmp").absolutePath))
            }
        }
    }

    private fun decode(body: JSONObject) = LocalMediaCompanionRecoveryEntry.fromJson(body, staging)

    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
