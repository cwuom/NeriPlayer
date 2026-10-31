package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMetadataRecoveryThroughputTest {
    @Test
    fun repeatedLargeReplacementsVerifyBytesIdentityAndTimestamp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "metadata-throughput-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val times = mutableListOf<Long>()
        try {
            val target = File(directory, "owned-audio.bin")
            val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
            target.outputStream().use { output -> repeat(384) { output.write(chunk) } }
            val originalTime = target.lastModified()
            val identity = android.system.Os.stat(target.absolutePath).st_ino
            repeat(4) { round ->
                val staging = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
                val backup = File(staging, "backup-$round.bin")
                val updated = File(staging, "updated-$round.bin")
                target.copyTo(backup)
                target.copyTo(updated)
                RandomAccessFile(updated, "rw").use { it.seek(1024L); it.write(round + 50) }
                val expected = digest(updated)
                val started = SystemClock.elapsedRealtime()
                val prepared = LocalMediaMetadataRecoveryStore.begin(
                    context, target.absolutePath, backup, updated, originalTime
                )
                val replacing = LocalMediaMetadataRecoveryStore.markReplacing(prepared)
                assertTrue(LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
                    context = context, targetReference = target.absolutePath, source = updated,
                    recoveryRecord = replacing, lastModifiedMs = originalTime
                ))
                assertTrue(LocalMediaMetadataRecoveryStore.targetMatches(context, target.absolutePath, expected))
                LocalMediaMetadataRecoveryStore.complete(LocalMediaMetadataRecoveryStore.markTargetVerified(replacing))
                times += SystemClock.elapsedRealtime() - started
                assertEquals(expected, digest(target))
                assertEquals(originalTime, target.lastModified())
                assertEquals(identity, android.system.Os.stat(target.absolutePath).st_ino)
                assertFalse(replacing.journalFile.exists())
            }
            Log.i("MetadataThroughput", "bytes=${target.length()}, elapsedMs=$times")
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
