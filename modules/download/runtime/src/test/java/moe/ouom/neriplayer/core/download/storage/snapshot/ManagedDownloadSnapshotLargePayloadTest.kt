package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ManagedDownloadSnapshotLargePayloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `disk store persists and restores a large lyrics library with a 256 MiB heap`() {
        val log = temporaryFolder.newFile()
        val java = File(System.getProperty("java.home"), "bin/java")
        val process = ProcessBuilder(
            java.absolutePath,
            "-Xmx256m",
            "-cp",
            childClassPath(),
            ManagedDownloadSnapshotLargePayloadProcess::class.java.name,
            temporaryFolder.newFolder().absolutePath
        ).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue("Snapshot subprocess timed out", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals(log.readText(), 0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        }
    }

    private fun childClassPath(): String {
        val entries = linkedSetOf<String>()
        entries += requireNotNull(System.getProperty("java.class.path"))
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
        generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.urLs.filter { it.protocol == "file" }.forEach { entries += File(it.toURI()).absolutePath }
        }
        return entries.joinToString(File.pathSeparator)
    }
}

object ManagedDownloadSnapshotLargePayloadProcess {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        check(Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024)
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(File(args.single()))
        val database = mock(NeriUserDataDatabase::class.java)
        `when`(database.syncMetadataDao()).thenReturn(mock(SyncMetadataDao::class.java))

        val lyrics = "[00:01.00] A line in a downloaded lyric\n".repeat(512)
        val audio = List(2_400) { index ->
            val name = "song-$index.mp3"
            val reference = "/downloads/$name"
            ManagedDownloadStorage.StoredEntry(name, reference, "file://$reference", reference, 42L, 1L)
        }
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = audio,
            metadataEntries = emptyList(),
            metadataByAudioName = audio.associate { entry ->
                entry.name to DownloadedAudioMetadata(
                    stableKey = entry.name,
                    audioFileName = entry.name,
                    matchedLyric = lyrics
                )
            },
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )

        assertTrue(ManagedDownloadSnapshotRoomStore(context, database).persist("library", snapshot))
        assertTrue(ManagedDownloadSnapshotDiskCache.cacheFile(context).length() > 128L * 1024 * 1024)

        val restored = ManagedDownloadSnapshotRoomStore(context, database).restore("library")
        assertEquals(audio, restored?.second?.audioEntries)
        val restoredMetadata = checkNotNull(restored?.second?.metadataByAudioName)
        assertEquals(audio.size, restoredMetadata.size)
        assertTrue(restoredMetadata.values.all { it.matchedLyric == lyrics })
    }
}
