package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ColorOsConfigJournal(private val file: File) {
    internal suspend fun <T> serialized(block: suspend () -> T): T = operationMutex.withLock { block() }

    internal fun load(): State? {
        if (!file.exists()) return null
        try {
            if (file.length() > MAX_JOURNAL_BYTES) invalid()
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) invalid()
                if (input.readInt() != SCHEMA_VERSION) invalid()
                val size = input.readInt()
                if (size !in 1..MAX_JOURNAL_BYTES) invalid()
                val bytes = ByteArray(size).also(input::readFully)
                val digest = ByteArray(32).also(input::readFully)
                if (input.read() != -1 || !MessageDigest.isEqual(digest, sha256(bytes))) invalid()
                DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                    val state = State(
                        data.readString(), data.readString(), data.readRow(), data.readRow(), data.readRow(),
                        data.readBoolean(), data.readBoolean(), data.readBoolean(), data.readBoolean()
                    )
                    if (data.read() != -1) invalid()
                    return state
                }
            }
        } catch (exception: ColorOsConfigException) {
            throw exception
        } catch (_: Exception) {
            invalid()
        }
    }

    internal fun save(state: State) {
        val content = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeString(state.applicationId)
                data.writeString(state.assetFingerprint)
                data.writeRow(state.baseline)
                data.writeRow(state.expected)
                data.writeRow(state.target)
                data.writeBoolean(state.restoring)
                data.writeBoolean(state.pending)
                data.writeBoolean(state.reloadRequested)
                data.writeBoolean(state.uncertain)
            }
        }.toByteArray()
        val parent = file.absoluteFile.parentFile ?: invalid()
        check(parent.isDirectory || parent.mkdirs()) { "journal_directory" }
        val temporary = File(parent, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                val data = DataOutputStream(output)
                data.writeInt(MAGIC)
                data.writeInt(SCHEMA_VERSION)
                data.writeInt(content.size)
                data.write(content)
                data.write(sha256(content))
                data.flush()
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    internal fun clear() {
        check(!file.exists() || file.delete()) { "journal_delete" }
    }

    internal data class State(
        val applicationId: String,
        val assetFingerprint: String,
        val baseline: ColorOsRusRow,
        val expected: ColorOsRusRow,
        val target: ColorOsRusRow,
        val restoring: Boolean,
        val pending: Boolean,
        val reloadRequested: Boolean,
        val uncertain: Boolean
    )

    private fun DataOutputStream.writeRow(row: ColorOsRusRow) {
        writeString(row.version)
        writeString(row.xml)
        writeBoolean(row.hasMd5)
        writeNullableString(row.md5)
        writeBoolean(row.hasIsOpen)
        writeNullableString(row.isOpen)
    }

    private fun DataInputStream.readRow() = ColorOsRusRow(
        readString(), readString(), readBoolean(), readNullableString(), readBoolean(), readNullableString()
    )

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > ColorOsConfigXml.MAX_XML_BYTES) invalid()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        if (size !in 0..ColorOsConfigXml.MAX_XML_BYTES) invalid()
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun invalid(): Nothing = throw ColorOsConfigException(ColorOsConfigStatus.ERROR, "journal_invalid")

    private companion object {
        const val MAGIC = 0x434F4C59
        const val SCHEMA_VERSION = 1
        const val MAX_JOURNAL_BYTES = 1024 * 1024
        // separate UI instances must never replace each other's original recovery baseline
        val operationMutex = Mutex()
    }
}
