package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.ManagedMigrationReplacementPlan
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verifyNoInteractions

class ManagedDownloadStorageCommitWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `reuses existing lowercase directory without creating canonical duplicate`() {
        val root = tempFolder.newFolder("download")
        val lowercaseDirectory = File(root, "covers").apply { mkdirs() }

        val resolved = resolveManagedFileSubdirectory(root, "Covers")

        assertEquals(lowercaseDirectory.canonicalFile, resolved.canonicalFile)
        assertEquals(
            1,
            root.listFiles()
                ?.count { it.isDirectory && it.name.equals("Covers", ignoreCase = true) }
        )
    }

    @Test
    fun `prefers exact directory when case variants both exist`() {
        val root = tempFolder.newFolder("download")
        val exactDirectory = File(root, "Covers").apply { mkdirs() }
        File(root, "covers").mkdirs()

        val resolved = resolveManagedFileSubdirectory(root, "Covers")

        assertEquals(exactDirectory.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun `creates standard directory when no case variant exists`() {
        val root = tempFolder.newFolder("download")

        val resolved = resolveManagedFileSubdirectory(root, "Covers")

        assertTrue(resolved.isDirectory)
        assertEquals(File(root, "Covers").canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun `planned absent SAF migration target does not enumerate the directory again`() {
        val registry = mock(ManagedDownloadTreeChildRegistry::class.java)
        val resolver = ManagedDownloadCommitMigrationTargetResolver(
            treeChildRegistry = registry,
            tag = "ManagedDownloadStorageCommitWriterTest"
        )
        val source = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "/source/song.mp3",
            mediaUri = "file:///source/song.mp3",
            localFilePath = "/source/song.mp3",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )

        val resolved = resolver.resolveTreeTarget(
            context = mock(Context::class.java),
            parent = mock(DocumentFile::class.java),
            displayName = source.name,
            sourceEntry = source,
            targetNames = emptySet(),
            targetEntry = null
        )

        assertTrue(resolved.createdNew)
        assertEquals(source.name, resolved.entry.name)
        verifyNoInteractions(registry)
    }

    @Test
    fun `known SAF target entry avoids a second child enumeration`() {
        val registry = mock(ManagedDownloadTreeChildRegistry::class.java)
        val resolver = ManagedDownloadCommitMigrationTargetResolver(
            treeChildRegistry = registry,
            tag = "ManagedDownloadStorageCommitWriterTest"
        )
        val source = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "content://source/song.mp3",
            mediaUri = "content://source/song.mp3",
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        // 迁移重试时目标索引可能复用同一受信引用, 不需要再次查询目录
        val knownTarget = source.copy()

        val resolved = resolver.resolveTreeTarget(
            context = mock(Context::class.java),
            parent = mock(DocumentFile::class.java),
            displayName = source.name,
            sourceEntry = source,
            targetNames = setOf(source.name),
            targetEntry = knownTarget
        )

        assertTrue(!resolved.createdNew)
        assertEquals(source.name, resolved.entry.name)
        verifyNoInteractions(registry)
    }

    @Test
    fun `cached SAF write hint selects only exact non directory child`() {
        val directory = QueriedTreeChild(
            name = "song.mp3.npmeta.json",
            documentUri = mock(Uri::class.java),
            sizeBytes = null,
            lastModifiedMs = 1L,
            isDirectory = true
        )
        val target = directory.copy(
            documentUri = mock(Uri::class.java),
            sizeBytes = 12L,
            isDirectory = false
        )

        assertEquals(
            target,
            selectCachedSafWriteChild(
                displayName = target.name,
                cachedChildren = listOf(directory, target)
            )
        )
        assertNull(selectCachedSafWriteChild("missing.json", listOf(target)))
        assertNull(selectCachedSafWriteChild(target.name, null))
    }

    @Test
    fun `provider size drift is accepted only when copied bytes read back`() {
        assertTrue(
            isSafProviderSizeDriftRecoverable(
                copiedBytes = 128L,
                reportedBytes = 64L,
                countedBytes = 128L
            )
        )
        assertTrue(
            isSafProviderSizeDriftRecoverable(
                copiedBytes = 128L,
                reportedBytes = null,
                countedBytes = null
            )
        )
        assertFalse(
            isSafProviderSizeDriftRecoverable(
                copiedBytes = 128L,
                reportedBytes = 64L,
                countedBytes = 127L
            )
        )
        assertFalse(
            isSafProviderSizeDriftRecoverable(
                copiedBytes = -1L,
                reportedBytes = 64L,
                countedBytes = 64L
            )
        )
    }

    @Test
    fun `restored SAF replacement requires the backup document identity`() {
        val expectedTarget = safEntry(
            name = "song.mp3",
            documentId = "new"
        )
        val backup = safEntry(
            name = ".np-migration-backup-song",
            documentId = "old"
        )
        val restored = safEntry(
            name = "song.mp3",
            documentId = "old",
            treeUri = false
        )

        assertTrue(
            isRestoredMigrationReplacementTarget(
                expectedTarget = expectedTarget,
                replacementBackup = backup,
                actualTarget = restored
            )
        )
    }

    @Test
    fun `restored SAF replacement rejects an unrelated document with matching metadata`() {
        val expectedTarget = safEntry(name = "song.mp3", documentId = "new")
        val backup = safEntry(
            name = ".np-migration-backup-song",
            documentId = "old",
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )
        val unrelated = safEntry(
            name = "song.mp3",
            documentId = "other",
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )

        assertFalse(
            isRestoredMigrationReplacementTarget(
                expectedTarget = expectedTarget,
                replacementBackup = backup,
                actualTarget = unrelated
            )
        )
    }

    @Test
    fun `file replacement backup keeps the original target fingerprint after rename`() {
        val expectedTarget = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "/target/song.mp3",
            mediaUri = "file:///target/song.mp3",
            localFilePath = "/target/song.mp3",
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )
        val renamedBackup = expectedTarget.copy(
            name = ".np-migration-backup-song",
            reference = "/target/.np-migration-backup-song",
            mediaUri = "file:///target/.np-migration-backup-song",
            localFilePath = "/target/.np-migration-backup-song"
        )

        assertTrue(sameMigrationReplacementBackupIdentity(expectedTarget, renamedBackup))
    }

    @Test
    fun `file replacement backup rejects changed fingerprint after rename`() {
        val expectedTarget = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "/target/song.mp3",
            mediaUri = "file:///target/song.mp3",
            localFilePath = "/target/song.mp3",
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )
        val changedBackup = expectedTarget.copy(
            name = ".np-migration-backup-song",
            reference = "/target/.np-migration-backup-song",
            mediaUri = "file:///target/.np-migration-backup-song",
            localFilePath = "/target/.np-migration-backup-song",
            lastModifiedMs = 8L
        )

        assertFalse(sameMigrationReplacementBackupIdentity(expectedTarget, changedBackup))
    }

    @Test
    fun `file replacement retry accepts an existing backup after target was replaced`() {
        val root = tempFolder.newFolder("target")
        val targetFile = File(root, "song.mp3").apply {
            writeBytes("old".toByteArray())
            setLastModified(7L)
        }
        val originalTarget = ManagedDownloadStorage.StoredEntry(
            name = targetFile.name,
            reference = targetFile.absolutePath,
            mediaUri = "file://${targetFile.absolutePath}",
            localFilePath = targetFile.absolutePath,
            sizeBytes = targetFile.length(),
            lastModifiedMs = targetFile.lastModified()
        )
        val replacementPlan = ManagedMigrationReplacementPlan(
            sourceReference = "/source/song.mp3",
            groupIdentity = "stable:song",
            subdirectory = null,
            targetName = targetFile.name,
            targetEntry = originalTarget,
            backupName = ".np-migration-backup-song"
        )
        val backupFile = File(root, replacementPlan.backupName)
        val targetUri = Uri.parse("file://${targetFile.absolutePath}")
        val backupUri = Uri.parse("file://${backupFile.absolutePath}")
        val sourceEntry = ManagedDownloadStorage.StoredEntry(
            name = targetFile.name,
            reference = "/source/song.mp3",
            mediaUri = "file:///source/song.mp3",
            localFilePath = "/source/song.mp3",
            sizeBytes = 4L,
            lastModifiedMs = 11L
        )
        val writer = ManagedDownloadStorageCommitWriter(
            treeChildRegistry = mock(ManagedDownloadTreeChildRegistry::class.java),
            treeDirectories = mock(ManagedDownloadTreeDirectories::class.java),
            tag = "ManagedDownloadStorageCommitWriterTest"
        )
        val context = mock(Context::class.java)

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uriMock ->
            uriMock.`when`<Uri> { Uri.fromFile(targetFile) }
                .thenReturn(targetUri)
            uriMock.`when`<Uri> { Uri.fromFile(backupFile) }
                .thenReturn(backupUri)
            writer.writeMigrationRootStream(
                context = context,
                root = ManagedDownloadRootHandle.FileRoot(root),
                displayName = targetFile.name,
                mimeType = "audio/mpeg",
                input = ByteArrayInputStream("new!".toByteArray()),
                sourceEntry = sourceEntry,
                targetNames = setOf(targetFile.name),
                targetEntry = originalTarget,
                replacementPlan = replacementPlan
            )

            val currentTarget = ManagedDownloadStorage.StoredEntry(
                name = targetFile.name,
                reference = targetFile.absolutePath,
                mediaUri = "file://${targetFile.absolutePath}",
                localFilePath = targetFile.absolutePath,
                sizeBytes = targetFile.length(),
                lastModifiedMs = targetFile.lastModified()
            )
            val retry = writer.writeMigrationRootStream(
                context = context,
                root = ManagedDownloadRootHandle.FileRoot(root),
                displayName = targetFile.name,
                mimeType = "audio/mpeg",
                input = ByteArrayInputStream("new!".toByteArray()),
                sourceEntry = sourceEntry,
                targetNames = setOf(targetFile.name),
                targetEntry = currentTarget,
                replacementPlan = replacementPlan
            )

            assertFalse(retry.createdNew)
            assertTrue(retry.replacementBackup != null)
            assertEquals("new!", targetFile.readText())
            assertEquals(
                "old",
                backupFile.readText()
            )
        }
    }

    @Test
    fun `SAF restore never bypasses target identity when a backup is present`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/commit/" +
                "ManagedDownloadStorageCommitWriter.kt"
        ).readText()
        val restoreBody = source
            .substringAfter("fun restoreMigrationReplacement(")
            .substringBefore("private fun safDocumentUri(")
        val normalizedBody = restoreBody.replace(Regex("\\s+"), " ")

        assertTrue(
            normalizedBody.contains(
                "isSafeReplacementTargetIdentity("
            )
        )
        assertTrue(
            normalizedBody.contains(
                "expectedBackupName = backup.name"
            )
        )
        assertTrue(normalizedBody.contains("if (targetDocument == null) return null"))
    }

    @Test
    fun `replacement backup name mismatch is never accepted by an identical document`() {
        val target = safEntry(name = "song.mp3", documentId = "same")
        val renamed = target.copy(name = "foreign.mp3")

        assertFalse(
            sameMigrationReplacementBackupIdentity(
                expectedTarget = target,
                actualBackup = renamed,
                expectedBackupName = ".np-migration-backup-song"
            )
        )
    }

    private fun safEntry(
        name: String,
        documentId: String,
        sizeBytes: Long = 42L,
        lastModifiedMs: Long = 7L,
        treeUri: Boolean = true
    ): ManagedDownloadStorage.StoredEntry {
        val reference = if (treeUri) {
            "content://provider/tree/root/document/$documentId"
        } else {
            "content://provider/document/$documentId"
        }
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs
        )
    }

    private fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
