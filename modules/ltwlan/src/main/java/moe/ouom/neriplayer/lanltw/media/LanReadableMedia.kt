package moe.ouom.neriplayer.lanltw.media

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.lan.LanLocalMedia

/** Read capability only: deliberately has no delete or ownership operation. */
class LanReadableMedia(val byteLength: Long, private val current: () -> Boolean = { true },
    private val input: () -> InputStream) {
    fun checkCurrent() { check(current()) { "SOURCE_CHANGED" } }
    fun open(): InputStream { checkCurrent(); return input() }
    companion object {
        fun file(file: File): LanReadableMedia {
            val size = file.length(); val modified = file.lastModified()
            return LanReadableMedia(size, { file.isFile && file.length() == size && file.lastModified() == modified }, file::inputStream)
        }
        suspend fun fingerprint(current: () -> Boolean = { true }, open: () -> InputStream): Pair<LanReadableMedia, String> = withContext(Dispatchers.IO) {
            check(current()) { "SOURCE_CHANGED" }
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            open().use { source ->
                val bytes = ByteArray(32768)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(bytes)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    check(size <= LanLocalMedia.MAX_BYTES) { "MEMORY_LIMIT" }
                    hash.update(bytes, 0, count)
                }
            }
            check(size > 0) { "AUDIO_FORMAT" }
            check(current()) { "SOURCE_CHANGED" }
            val expected = hash.digest()
            val media = LanReadableMedia(size, current) { VerifiedInput(open(), size, expected) }
            media to expected.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Verifies bytes during the required read, without an extra copy or second validation pass. */
private class VerifiedInput(input: InputStream, private val expectedSize: Long, private val expected: ByteArray) : FilterInputStream(input) {
    private val hash = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var verified = false
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = `in`.read(bytes, offset, length)
        if (read < 0) { if (count != expectedSize) throw IOException("SOURCE_CHANGED"); return -1 }
        if (read == 0) return 0
        count += read
        if (count > expectedSize) throw IOException("SOURCE_CHANGED")
        hash.update(bytes, offset, read)
        if (count == expectedSize && !verified) {
            verified = true
            if (!MessageDigest.isEqual(expected, hash.digest())) throw IOException("SOURCE_CHANGED")
        }
        return read
    }
}
