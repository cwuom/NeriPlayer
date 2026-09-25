package moe.ouom.neriplayer.core.download.storage.backend

import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedTemporaryWriteJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `journal persists provider URI and observed display name`() {
        val context = mock(Context::class.java)
        val parentUri = mock(Uri::class.java)
        val temporaryUri = mock(Uri::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        `when`(parentUri.toString()).thenReturn("content://provider/parent")
        `when`(temporaryUri.toString()).thenReturn("content://provider/temp-opaque-id")
        val target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parentUri),
            displayName = "Artist - Song.npmeta.json",
            mimeType = "application/json"
        )
        val requestedName = ManagedTemporaryWriteArtifacts.displayNameFor(
            target = target,
            nonce = "0123456789abcdef"
        )

        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordCreated(
                context = context,
                temporaryUri = temporaryUri,
                target = target,
                requestedDisplayName = requestedName
            )
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordActualDisplayName(
                context = context,
                temporaryUri = temporaryUri,
                requestedDisplayName = requestedName,
                actualDisplayName = "$requestedName.json"
            )
        )

        val entry = requireNotNull(
            PersistentManagedTemporaryWriteJournal.snapshotForTest(context)?.single()
        )
        assertEquals("content://provider/temp-opaque-id", entry.uri)
        assertEquals(requestedName, entry.requestedDisplayName)
        assertEquals("$requestedName.json", entry.actualDisplayName)
        assertEquals(
            ManagedTemporaryWriteArtifacts.targetNamePrefix(target),
            entry.targetPrefix
        )
    }

    @Test
    fun `startup recovery cannot claim a temporary document with an active lease`() {
        val parentUri = mock(Uri::class.java)
        `when`(parentUri.toString()).thenReturn("content://provider/parent-active")
        val target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parentUri),
            displayName = "Artist - Active.flac",
            mimeType = "audio/flac"
        )
        val lease = requireNotNull(
            ManagedTemporaryWriteArtifacts.acquire(
                target = target,
                nonce = "1023456789abcdef"
            )
        )
        try {
            assertTrue(
                ManagedTemporaryWriteArtifacts.isActiveSafWrite(
                    parentUri = parentUri.toString(),
                    displayName = lease.displayName
                )
            )
        } finally {
            lease.close()
        }
        assertFalse(
            ManagedTemporaryWriteArtifacts.isActiveSafWrite(
                parentUri = parentUri.toString(),
                displayName = lease.displayName
            )
        )
    }

    @Test
    fun `old owner reconciliation cannot take over a reused provider URI`() {
        val context = mock(Context::class.java)
        val parentUri = mock(Uri::class.java)
        val temporaryUri = mock(Uri::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        `when`(parentUri.toString()).thenReturn("content://provider/parent")
        `when`(temporaryUri.toString()).thenReturn("content://provider/reused-id")
        val target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parentUri),
            displayName = "Artist - Song.npmeta.json",
            mimeType = "application/json"
        )
        val oldName = ManagedTemporaryWriteArtifacts.displayNameFor(
            target = target,
            nonce = "0123456789abcdef"
        )
        val newName = ManagedTemporaryWriteArtifacts.displayNameFor(
            target = target,
            nonce = "fedcba9876543210"
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordCreated(
                context = context,
                temporaryUri = temporaryUri,
                target = target,
                requestedDisplayName = oldName
            )
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordCreated(
                context = context,
                temporaryUri = temporaryUri,
                target = target,
                requestedDisplayName = newName
            )
        )

        assertTrue(
            PersistentManagedTemporaryWriteJournal.reconcileCreatedUri(
                context = context,
                temporaryUri = temporaryUri,
                requestedDisplayName = oldName
            )
        )
        assertEquals(
            newName,
            PersistentManagedTemporaryWriteJournal.snapshotForTest(context)
                ?.single()
                ?.requestedDisplayName
        )
        assertFalse(
            PersistentManagedTemporaryWriteJournal.recordActualDisplayName(
                context = context,
                temporaryUri = temporaryUri,
                requestedDisplayName = oldName,
                actualDisplayName = "$oldName.json"
            )
        )
        assertEquals(
            null,
            PersistentManagedTemporaryWriteJournal.snapshotForTest(context)
                ?.single()
                ?.actualDisplayName
        )
    }

    @Test
    fun `completed write removes its recovery entry without querying the renamed URI`() {
        val context = mock(Context::class.java)
        val parentUri = mock(Uri::class.java)
        val temporaryUri = mock(Uri::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        `when`(parentUri.toString()).thenReturn("content://provider/parent-completed")
        `when`(temporaryUri.toString()).thenReturn("content://provider/completed-opaque-id")
        val target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parentUri),
            displayName = "Artist - Completed.flac",
            mimeType = "audio/flac"
        )
        val requestedName = ManagedTemporaryWriteArtifacts.displayNameFor(
            target = target,
            nonce = "1123456789abcdef"
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordCreated(
                context = context,
                temporaryUri = temporaryUri,
                target = target,
                requestedDisplayName = requestedName
            )
        )

        assertTrue(
            PersistentManagedTemporaryWriteJournal.completeCreatedUri(
                context = context,
                temporaryUri = temporaryUri,
                requestedDisplayName = requestedName
            )
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.snapshotForTest(context).orEmpty().isEmpty()
        )
    }

    @Test
    fun `provider missing exception is classified as a completed stale entry`() {
        val providerFailure = IllegalArgumentException(
            "Failed to determine if primary:Downloads/song.pending is child of " +
                "primary:Downloads",
            FileNotFoundException("Missing file for primary:Downloads/song.pending")
        )

        assertTrue(
            PersistentManagedTemporaryWriteJournal.isMissingDocumentQueryFailure(providerFailure)
        )
    }

    @Test
    fun `old completion cannot remove a newer owner of a reused provider URI`() {
        val context = mock(Context::class.java)
        val parentUri = mock(Uri::class.java)
        val temporaryUri = mock(Uri::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        `when`(parentUri.toString()).thenReturn("content://provider/parent-reused")
        `when`(temporaryUri.toString()).thenReturn("content://provider/reused-completed-id")
        val target = StorageTarget.SafTarget(
            parent = StorageReference.SafRef(parentUri),
            displayName = "Artist - Reused.flac",
            mimeType = "audio/flac"
        )
        val oldName = ManagedTemporaryWriteArtifacts.displayNameFor(
            target = target,
            nonce = "2123456789abcdef"
        )
        val newName = ManagedTemporaryWriteArtifacts.displayNameFor(
            target = target,
            nonce = "3123456789abcdef"
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordCreated(
                context = context,
                temporaryUri = temporaryUri,
                target = target,
                requestedDisplayName = oldName
            )
        )
        assertTrue(
            PersistentManagedTemporaryWriteJournal.recordCreated(
                context = context,
                temporaryUri = temporaryUri,
                target = target,
                requestedDisplayName = newName
            )
        )

        assertTrue(
            PersistentManagedTemporaryWriteJournal.completeCreatedUri(
                context = context,
                temporaryUri = temporaryUri,
                requestedDisplayName = oldName
            )
        )
        assertEquals(
            newName,
            PersistentManagedTemporaryWriteJournal.snapshotForTest(context)
                ?.single()
                ?.requestedDisplayName
        )
    }
}
