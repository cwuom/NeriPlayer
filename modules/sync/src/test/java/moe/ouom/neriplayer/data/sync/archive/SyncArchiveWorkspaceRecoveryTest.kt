package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.sync.archive.v4.SyncArchiveV4Bridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveWorkspaceRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun repositoryReclaimsLegacyWorkspaceWithoutOwnerAndPreservesOtherCacheFiles() {
        val directory = temporary.newFolder()
        val abandoned = File(directory, "sync-stage-${UUID.randomUUID()}").apply { mkdir() }
        File(abandoned, "main.records").writeBytes(byteArrayOf(1, 2, 3))
        File(abandoned, "legacy.records").writeBytes(byteArrayOf(4, 5))
        File(abandoned, "compact-fixture").apply { mkdir() }
            .resolve("lyric-bodies").writeBytes(byteArrayOf(6, 7, 8))
        val retained = File(directory, "legacy-source.raw").apply { writeBytes(byteArrayOf(9)) }
        val playback = File(directory, "playback-staging/dataset-fixture/records").apply {
            checkNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(11))
        }
        val objectFile = File(directory, "neriplayer-sync-v4-${"a".repeat(64)}.zst")
            .apply { writeBytes(byteArrayOf(10)) }

        SyncArchiveRepository(directory)

        assertFalse("repository initialization must reclaim raw records left by a terminated process", abandoned.exists())
        assertArrayEquals(byteArrayOf(9), retained.readBytes())
        assertArrayEquals(byteArrayOf(11), playback.readBytes())
        assertArrayEquals(byteArrayOf(10), objectFile.readBytes())
    }

    @Test
    fun anotherRepositoryPreservesAnOwnedWorkspaceAndCloseIsIdempotent() {
        val directory = temporary.newFolder()
        val workspace = SyncArchiveWorkspace.create(directory)
        val raw = File(workspace.directory, "main.records").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val owner = ownerFile(directory, workspace.directory.name)
        try {
            SyncArchiveRepository(directory)
            SyncArchiveWorkspace.recoverAbandoned(directory)
            assertArrayEquals(byteArrayOf(1, 2, 3), raw.readBytes())
            assertTrue(owner.isFile)
        } finally {
            workspace.close()
        }
        workspace.close()
        assertFalse(workspace.directory.exists())
        assertFalse(owner.exists())
    }

    @Test
    fun sameJvmRecoveryDoesNotReleaseTheActiveWorkspaceOsLock() {
        val directory = temporary.newFolder()
        SyncArchiveWorkspace.create(directory).use { workspace ->
            File(workspace.directory, "main.records").writeBytes(byteArrayOf(1))
            SyncArchiveRepository(directory)
            SyncArchiveWorkspace.recoverAbandoned(directory)
            val child = startChild(directory, "probe-lock", ownerFile(directory, workspace.directory.name))
            try {
                assertEquals("locked", awaitChildReady(child))
                assertTrue(child.process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(child.log.readText(), 0, child.process.exitValue())
                assertTrue(workspace.directory.isDirectory)
            } finally {
                stopChild(child)
            }
        }
    }

    @Test
    fun anAncestorAliasPreservesTheManagedWorkspaceOsLock() {
        val parent = temporary.newFolder()
        val directory = File(parent, "cache").apply { mkdir() }
        val alias = File(temporary.root, "aliased-parent").toPath()
        Files.createSymbolicLink(alias, parent.toPath())
        try {
            SyncArchiveWorkspace.create(directory).use { workspace ->
                val aliasDirectory = File(alias.toFile(), "cache")
                SyncArchiveRepository(aliasDirectory)
                SyncArchiveWorkspace.recoverAbandoned(aliasDirectory)
                val child = startChild(directory, "probe-lock", ownerFile(aliasDirectory, workspace.directory.name))
                try {
                    assertEquals("locked", awaitChildReady(child))
                    assertTrue(child.process.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(child.log.readText(), 0, child.process.exitValue())
                    assertTrue(workspace.directory.isDirectory)
                } finally {
                    stopChild(child)
                }
            }
        } finally {
            Files.deleteIfExists(alias)
        }
    }

    @Test
    fun anotherRepositoryKeepsTheRawStreamOfAnActiveLoadedArchive() = runBlocking {
        val directory = temporary.newFolder()
        val bridge = SyncArchiveV4Bridge(directory, SyncArchiveCache(directory))
        val manifest = fixture(SyncArchiveRepository.MANIFEST_FILE_NAME)
        bridge.load(manifest, true, { Result.success(fixture(it)) }) {}.use { loaded ->
            val expected = loaded.main().use { it.readBytes() }
            assertTrue(expected.isNotEmpty())

            SyncArchiveRepository(directory)

            assertArrayEquals(expected, loaded.main().use { it.readBytes() })
            assertEquals(1, stageDirectories(directory).size)
        }
        assertTrue(stageDirectories(directory).isEmpty())
    }

    @Test
    fun anotherProcessKeepsItsWorkspaceWhileTheOwnerLockIsLive() {
        val directory = temporary.newFolder()
        val child = startChild(directory, "hold")
        try {
            val stage = awaitChildWorkspace(child)
            SyncArchiveRepository(directory)
            SyncArchiveWorkspace.recoverAbandoned(directory)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(stage, "main.records").readBytes())
            assertTrue(ownerFile(directory, stage.name).isFile)

            child.process.outputStream.use { it.write(1); it.flush() }
            assertTrue(child.process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(child.log.readText(), 0, child.process.exitValue())
            assertFalse(stage.exists())
            assertFalse(ownerFile(directory, stage.name).exists())
        } finally {
            stopChild(child)
        }
    }

    @Test
    fun repositoryReclaimsRawFilesAndOwnerAfterAProcessExitsWithoutClose() {
        val directory = temporary.newFolder()
        val child = startChild(directory, "halt")
        try {
            val stage = awaitChildWorkspace(child)
            assertTrue(child.process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(child.log.readText(), HALT_EXIT, child.process.exitValue())
            assertTrue(stage.isDirectory)
            assertTrue(ownerFile(directory, stage.name).isFile)

            SyncArchiveRepository(directory)

            assertFalse(stage.exists())
            assertFalse(ownerFile(directory, stage.name).exists())
        } finally {
            stopChild(child)
        }
    }

    @Test
    fun concurrentRepositoriesRecoverOnlyWorkspacesWhoseOwnersHaveFinished() {
        val directory = temporary.newFolder()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker -> executor.submit {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                repeat(6) { iteration ->
                    SyncArchiveWorkspace.create(directory).use { workspace ->
                        val raw = File(workspace.directory, "main.records")
                        val expected = byteArrayOf(worker.toByte(), iteration.toByte())
                        raw.writeBytes(expected)
                        SyncArchiveRepository(directory)
                        SyncArchiveWorkspace.recoverAbandoned(directory)
                        assertArrayEquals(expected, raw.readBytes())
                    }
                }
            } }
            start.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
            SyncArchiveWorkspace.recoverAbandoned(directory)
            assertTrue(stageDirectories(directory).isEmpty())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".owner") })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun recoveryPreservesNoncanonicalStageNamesAndUnrelatedCacheEntries() {
        val directory = temporary.newFolder()
        val uuid = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val unrelated = listOf("sync-stage-in-progress", "sync-stage-$uuid-extra",
            "sync-stage-${uuid.uppercase()}", "other-cache").map { name ->
            File(directory, name).apply { mkdir() }.resolve("keep").apply { writeBytes(byteArrayOf(4)) }
        } + File(directory, ".sync-stage-$uuid.owner.tmp").apply { writeBytes(byteArrayOf(5)) }
        val expected = unrelated.associateWith(File::readBytes)

        SyncArchiveRepository(directory)

        expected.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test
    fun recoveryUnlinksNestedSymlinksWithoutFollowingTheirTargets() {
        val directory = temporary.newFolder()
        val stage = legacyStage(directory)
        val foreign = temporary.newFolder()
        val foreignFile = File(foreign, "keep").apply { writeBytes(byteArrayOf(9)) }
        val nested = File(stage, "compact-fixture").apply { mkdir() }
        val links = listOf(File(nested, "foreign-directory").toPath(), File(stage, "foreign-file").toPath())
        Files.createSymbolicLink(links[0], foreign.toPath())
        Files.createSymbolicLink(links[1], foreignFile.toPath())
        try {
            SyncArchiveRepository(directory)

            assertFalse(stage.exists())
            assertTrue(foreign.isDirectory)
            assertArrayEquals(byteArrayOf(9), foreignFile.readBytes())
            links.forEach { assertFalse(Files.exists(it, LinkOption.NOFOLLOW_LINKS)) }
        } finally {
            links.forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun recoveryDoesNotFollowAStageDirectorySymlink() {
        val directory = temporary.newFolder()
        val foreign = temporary.newFolder()
        val keep = File(foreign, "main.records").apply { writeBytes(byteArrayOf(7)) }
        val link = File(directory, "sync-stage-${UUID.randomUUID()}").toPath()
        Files.createSymbolicLink(link, foreign.toPath())
        try {
            SyncArchiveRepository(directory)

            assertArrayEquals(byteArrayOf(7), keep.readBytes())
            assertTrue(Files.isSymbolicLink(link))
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun recoveryDoesNotFollowAnOwnerSymlinkOrDeleteItsUnprovenStage() {
        val directory = temporary.newFolder()
        val stage = legacyStage(directory)
        val foreign = temporary.newFile().apply { writeBytes(byteArrayOf(8)) }
        val owner = ownerFile(directory, stage.name).toPath()
        Files.createSymbolicLink(owner, foreign.toPath())
        try {
            SyncArchiveRepository(directory)

            assertTrue(stage.isDirectory)
            assertArrayEquals(byteArrayOf(8), foreign.readBytes())
            assertTrue(Files.isSymbolicLink(owner))
        } finally {
            Files.deleteIfExists(owner)
        }
    }

    @Test
    fun recoveryRetainsFailedCleanupOwnershipAndCanRetryAfterPermissionsAreRestored() {
        val directory = temporary.newFolder()
        val stage = legacyStage(directory)
        val nested = File(stage, "compact-fixture").apply { mkdir() }
        File(nested, "lyric-bodies").writeBytes(byteArrayOf(1, 2))
        val originalPermissions = Files.getPosixFilePermissions(nested.toPath())
        try {
            Files.setPosixFilePermissions(nested.toPath(), emptySet<PosixFilePermission>())

            assertThrows(IOException::class.java) { SyncArchiveWorkspace.recoverAbandoned(directory) }

            assertTrue("incomplete cleanup must remain recoverable", stage.exists())
            assertTrue("the failed workspace must retain its ownership file", ownerFile(directory, stage.name).isFile)
        } finally {
            Files.setPosixFilePermissions(nested.toPath(), originalPermissions)
        }

        SyncArchiveWorkspace.recoverAbandoned(directory)

        assertFalse(stage.exists())
        assertFalse(ownerFile(directory, stage.name).exists())
    }

    @Test
    fun recoveryReclaimsAnOrphanOwnerFileOnlyAfterItsLockIsReleased() {
        val directory = temporary.newFolder()
        val stageName = "sync-stage-${UUID.randomUUID()}"
        val owner = ownerFile(directory, stageName)
        RandomAccessFile(owner, "rw").use { stream ->
            stream.channel.lock().use {
                SyncArchiveRepository(directory)
                assertTrue(owner.isFile)
            }
        }

        SyncArchiveWorkspace.recoverAbandoned(directory)

        assertFalse(owner.exists())
    }

    @Test
    fun recoveryRejectsASymlinkedRootGuardWithoutChangingItsTarget() {
        val directory = temporary.newFolder()
        val stage = legacyStage(directory)
        val foreign = temporary.newFile().apply { writeBytes(byteArrayOf(5)) }
        val guard = File(directory, ".sync-stage.guard").toPath()
        Files.createSymbolicLink(guard, foreign.toPath())
        try {
            assertThrows(IOException::class.java) { SyncArchiveRepository(directory) }
            assertTrue(stage.isDirectory)
            assertArrayEquals(byteArrayOf(5), foreign.readBytes())
        } finally {
            Files.deleteIfExists(guard)
        }
    }

    @Test
    fun workspaceCreationRefusesASymlinkedCacheRoot() {
        val foreign = temporary.newFolder()
        val keep = File(foreign, "keep").apply { writeBytes(byteArrayOf(6)) }
        val root = File(temporary.root, "linked-cache").toPath()
        Files.createSymbolicLink(root, foreign.toPath())
        try {
            assertThrows(IOException::class.java) { SyncArchiveWorkspace.create(root.toFile()) }
            assertArrayEquals(byteArrayOf(6), keep.readBytes())
            assertEquals(listOf(keep), foreign.listFiles().orEmpty().toList())
        } finally {
            Files.deleteIfExists(root)
        }
    }

    private fun legacyStage(directory: File): File =
        File(directory, "sync-stage-${UUID.randomUUID()}").apply {
            check(mkdir())
            resolve("main.records").writeBytes(byteArrayOf(1))
        }

    private fun ownerFile(directory: File, stageName: String) = File(directory, ".$stageName.owner")

    private fun stageDirectories(directory: File) = directory.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.startsWith("sync-stage-") }

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/sync/archive/v4-frozen/$name"))
        .use { it.readBytes() }

    private data class Child(val process: Process, val directory: File, val ready: File, val log: File)

    private fun startChild(directory: File, mode: String, owner: File? = null): Child {
        val ready = File(temporary.root, "child-${UUID.randomUUID()}.ready")
        val log = temporary.newFile()
        val java = File(System.getProperty("java.home"), "bin/java")
        val command = arrayListOf(java.absolutePath, "-cp", childClassPath(),
            SyncArchiveWorkspaceRecoveryProcess::class.java.name, mode, directory.absolutePath, ready.absolutePath)
        owner?.let { command += it.absolutePath }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true).redirectOutput(log).start()
        return Child(process, directory, ready, log)
    }

    private fun awaitChildWorkspace(child: Child): File {
        return File(child.directory, awaitChildReady(child))
    }

    private fun awaitChildReady(child: Child): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!child.ready.isFile && child.process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("child did not create a workspace: ${child.log.readText()}", child.ready.isFile)
        return child.ready.readText()
    }

    private fun stopChild(child: Child) {
        if (child.process.isAlive) child.process.destroyForcibly()
        assertTrue(child.process.waitFor(10, TimeUnit.SECONDS))
    }

    private fun childClassPath(): String {
        val entries = linkedSetOf<String>()
        entries += requireNotNull(System.getProperty("java.class.path")).split(File.pathSeparator).filter(String::isNotBlank)
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.urLs.filter { it.protocol == "file" }.forEach { entries += File(it.toURI()).absolutePath }
        }
        return entries.joinToString(File.pathSeparator)
    }

    private companion object { const val HALT_EXIT = 73 }
}

object SyncArchiveWorkspaceRecoveryProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args[0] == "probe-lock") {
            FileChannel.open(File(args[3]).toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val lock = channel.tryLock()
                try {
                    File(args[2]).writeText(if (lock == null) "locked" else "acquired")
                } finally {
                    lock?.close()
                }
            }
            return
        }
        val workspace = SyncArchiveWorkspace.create(File(args[1]))
        File(workspace.directory, "main.records").writeBytes(byteArrayOf(1, 2, 3))
        File(workspace.directory, "legacy.records").writeBytes(byteArrayOf(4))
        File(args[2]).writeText(workspace.directory.name)
        if (args[0] == "halt") Runtime.getRuntime().halt(73)
        try {
            System.`in`.read()
        } finally {
            workspace.close()
        }
    }
}
