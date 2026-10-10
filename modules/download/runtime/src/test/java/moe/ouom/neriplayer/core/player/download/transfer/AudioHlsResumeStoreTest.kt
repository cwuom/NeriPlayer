package moe.ouom.neriplayer.core.player.download.transfer

import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class AudioHlsResumeStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `playlist fingerprint ignores volatile query tokens and non timing playlist lines`() {
        val store = AudioHlsResumeStore()
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n #EXTINF:9.9,\nseg1.ts\n#EXT-X-MEDIA-SEQUENCE:5"
        val first = store.buildPlaylistFingerprint(listOf("https://CDN.example/seg1.ts?sig=a&id=1"), playlist)
        val refreshed = store.buildPlaylistFingerprint(
            listOf("https://cdn.example/seg1.ts?id=1&sig=b&token=t"),
            "#EXTM3U\n#EXT-X-VERSION:3\n" + playlist
        )
        val retimed = store.buildPlaylistFingerprint(
            listOf("https://cdn.example/seg1.ts?id=1"),
            playlist.replace("#EXTINF:9.9", "#EXTINF:8.0")
        )
        val withoutPlaylist = store.buildPlaylistFingerprint(listOf("https://cdn.example/seg1.ts?id=1"))

        assertTrue(Regex("[0-9a-f]{64}").matches(first))
        assertEquals(first, refreshed)
        assertNotEquals(first, retimed)
        assertNotEquals(first, withoutPlaylist)
        assertEquals(withoutPlaylist, store.buildPlaylistFingerprint(listOf("https://cdn.example/seg1.ts?id=1")))
    }

    @Test
    fun `prefix digest hashes exactly the requested bytes across small read buffers`() {
        val store = AudioHlsResumeStore(readBufferBytes = 2)
        val file = tempFolder.newFile("segment.bin").apply { writeText("abcdef") }

        assertEquals(sha256("abc"), store.sha256FilePrefix(file, 3L))
        assertEquals(sha256("abcdef"), store.sha256FilePrefix(file, 6L))
        assertEquals(sha256(""), store.sha256FilePrefix(file, 0L))
        assertThrows(IllegalArgumentException::class.java) { store.sha256FilePrefix(file, -1L) }
        assertThrows(EOFException::class.java) { store.sha256FilePrefix(file, 10L) }
    }

    @Test
    fun `digest snapshot keeps the running digest usable and falls back to rehashing`() {
        val store = AudioHlsResumeStore()
        val file = tempFolder.newFile("prefix.bin").apply { writeText("abc") }
        val running = MessageDigest.getInstance("SHA-256").apply { update("abc".toByteArray()) }

        assertEquals(sha256("abc"), store.digestHexSnapshot(running, file, 3L))
        running.update("def".toByteArray())
        assertEquals(sha256("abcdef"), running.digest().joinToString("") { "%02x".format(it) })
        assertEquals(sha256("abc"), store.digestHexSnapshot(NonCloneableDigest(), file, 3L))
    }

    @Test
    fun `remembered checkpoint survives a new store and stays scoped to playlist and operation`() {
        val destFile = File(tempFolder.newFolder("staging"), "npdl_song.m4a.download")
        val writer = AudioHlsResumeStore()
        val playlist = "a".repeat(64)
        val prefix = "b".repeat(64)

        writer.remember(destFile, playlist, 3, 42L, prefix, "operation-1", 7L)
        val reader = AudioHlsResumeStore()
        val expected = AudioDownloadManager.HlsResumeState(
            playlistFingerprint = playlist,
            nextSegmentIndex = 3,
            downloadedBytes = 42L,
            durablePrefixSha256 = prefix,
            operationId = "operation-1",
            mediaSequence = 7L
        )

        assertTrue(reader.has(destFile))
        assertEquals(expected, reader.resolve(destFile, playlist, " operation-1 "))
        assertEquals(expected, writer.resolve(destFile, playlist, "operation-1"))
        assertNull(reader.resolve(destFile, "c".repeat(64), "operation-1"))
        assertNull(reader.resolve(destFile, playlist, "operation-2"))
        assertNull(reader.resolve(destFile, playlist))
        reader.clear(destFile)
        assertFalse(reader.has(destFile))
        assertNull(AudioHlsResumeStore().resolve(destFile, playlist, "operation-1"))
        assertFalse(reader.has(null))
    }

    @Test
    fun `failed first checkpoint write leaves no resumable state`() {
        val blocker = tempFolder.newFile("blocker")
        val store = AudioHlsResumeStore(checkpointFileFor = { File(blocker, it.name + ".hls.json") })
        val destFile = File(tempFolder.root, "npdl_song.m4a.download")

        assertThrows(IOException::class.java) {
            store.remember(destFile, "a".repeat(64), 1, 10L, "b".repeat(64), "operation-1", null)
        }
        assertFalse(store.has(destFile))
        assertNull(store.resolve(destFile, "a".repeat(64), "operation-1"))
    }

    @Test
    fun `failed checkpoint update keeps the previously durable state`() {
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val blocker = tempFolder.newFile("blocker")
        var failWrites = false
        val store = AudioHlsResumeStore(checkpointFileFor = { working ->
            if (failWrites) File(blocker, working.name) else File(checkpointDir, working.name + ".hls.json")
        })
        val destFile = File(tempFolder.root, "npdl_song.m4a.download")
        val playlist = "a".repeat(64)

        store.remember(destFile, playlist, 1, 10L, "b".repeat(64), "operation-1", null)
        failWrites = true
        assertThrows(IOException::class.java) {
            store.remember(destFile, playlist, 2, 20L, "c".repeat(64), "operation-1", null)
        }

        val retained = requireNotNull(store.resolve(destFile, playlist, "operation-1"))
        assertEquals(1, retained.nextSegmentIndex)
        assertEquals(10L, retained.downloadedBytes)
    }

    @Test
    fun `unreadable or invalid persisted checkpoints are ignored`() {
        val checkpointDir = tempFolder.newFolder("checkpoints")
        val store = AudioHlsResumeStore(checkpointFileFor = { File(checkpointDir, it.name + ".hls.json") })
        val directoryCheckpoint = File(tempFolder.root, "npdl_dir.m4a.download")
        File(checkpointDir, directoryCheckpoint.name + ".hls.json").mkdirs()
        val corruptCheckpoint = File(tempFolder.root, "npdl_corrupt.m4a.download")
        File(checkpointDir, corruptCheckpoint.name + ".hls.json").writeText("{not json")

        assertNull(store.resolve(directoryCheckpoint, "a".repeat(64)))
        assertNull(store.resolve(corruptCheckpoint, "a".repeat(64)))
        assertNull(store.resolve(File(tempFolder.root, "npdl_missing.m4a.download"), "a".repeat(64)))
    }

    @Test
    fun `checkpoint codec accepts legacy field names and rejects unsafe values`() {
        val store = AudioHlsResumeStore()
        val playlist = "a".repeat(64)
        val prefix = "b".repeat(64)

        assertEquals(
            AudioDownloadManager.HlsResumeState(playlist, 2, 64L, prefix),
            store.deserialize(
                """{"playlistFingerprint":"$playlist","nextSegmentIndex":2,"downloadedBytes":64,""" +
                    """"durablePrefixSha256":"$prefix","mediaSequence":-1}"""
            )
        )
        assertNull(store.deserialize(null))
        assertNull(store.deserialize("""{"playlistDigestSha256":"short","nextSegmentIndex":0}"""))
        assertNull(
            store.deserialize(
                """{"playlistDigestSha256":"$playlist","nextSegmentIndex":-1,"durableBytes":0,""" +
                    """"durablePrefixSha256":"$prefix"}"""
            )
        )
        val state = AudioDownloadManager.HlsResumeState(playlist, 1, 5L, prefix, "operation-1", 9L)
        assertEquals(state, store.deserialize(store.serialize(state)))
    }

    private class NonCloneableDigest : MessageDigest("non-cloneable") {
        override fun engineUpdate(input: Byte) = Unit

        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = Unit

        override fun engineDigest(): ByteArray = ByteArray(0)

        override fun engineReset() = Unit
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
