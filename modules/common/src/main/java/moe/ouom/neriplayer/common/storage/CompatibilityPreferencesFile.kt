package moe.ouom.neriplayer.common.storage

import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class CompatibilityPreferencesFile(private val directory: File, name: String) {
    private val identifier = MessageDigest.getInstance("SHA-256")
        .digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val dataFile = File(directory, "$identifier.prefs")
    val keyFile = File(directory, "$identifier.key")
    private lateinit var key: SecretKeySpec

    fun hasArtifacts(): Boolean = dataFile.exists() || keyFile.exists()

    fun open(): SharedPreferences {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create account storage")
        val existing = hasArtifacts()
        key = if (existing) {
            if (!dataFile.isFile || !keyFile.isFile) throw IOException("Incomplete account storage")
            if (keyFile.length() != KEY_BYTES.toLong()) throw IOException("Invalid account storage key")
            val encoded = keyFile.readBytes()
            if (encoded.size != KEY_BYTES) throw IOException("Invalid account storage key")
            SecretKeySpec(encoded, "AES")
        } else {
            val encoded = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            if (!keyFile.createNewFile()) throw IOException("Account storage key already exists")
            FileOutputStream(keyFile).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            SecretKeySpec(encoded, "AES")
        }
        val values = if (existing) read() else emptyMap<String?, Any>().also { write(it) }
        return SoftwareEncryptedSharedPreferences(this, values)
    }

    fun write(values: Map<String?, Any>) {
        if (!keyFile.isFile || keyFile.length() != KEY_BYTES.toLong() ||
            !MessageDigest.isEqual(key.encoded, keyFile.readBytes())
        ) throw IOException("Account storage key changed")
        // 已打开的实例也必须保留随后损坏的文件，不能用内存状态掩盖磁盘故障
        if (dataFile.exists()) read()
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
        val encrypted = HEADER + nonce + cipher.doFinal(encode(values))
        if (encrypted.size > MAX_FILE_BYTES) throw IOException("Account storage is too large")
        val pending = File.createTempFile(".$identifier.", ".pending", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(encrypted)
                output.fd.sync()
            }
            try {
                Files.move(pending.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (pending.exists()) pending.delete()
        }
    }

    private fun read(): Map<String?, Any> {
        if (dataFile.length() > MAX_FILE_BYTES) throw IOException("Account storage is too large")
        val bytes = dataFile.readBytes()
        if (bytes.size < HEADER.size + NONCE_BYTES + TAG_BITS / 8 ||
            !bytes.copyOfRange(0, HEADER.size).contentEquals(HEADER)
        ) throw IOException("Invalid account storage format")
        val nonce = bytes.copyOfRange(HEADER.size, HEADER.size + NONCE_BYTES)
        val encrypted = bytes.copyOfRange(HEADER.size + NONCE_BYTES, bytes.size)
        return decode(cipher(Cipher.DECRYPT_MODE, nonce).doFinal(encrypted))
    }

    private fun cipher(mode: Int, nonce: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(HEADER + identifier.toByteArray(Charsets.US_ASCII))
    }

    private fun encode(values: Map<String?, Any>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(values.size)
            values.forEach { (key, value) ->
                output.writeBoolean(key != null)
                if (key != null) output.writeString(key)
                when (value) {
                    is String -> { output.writeByte(STRING); output.writeString(value) }
                    is Int -> { output.writeByte(INT); output.writeInt(value) }
                    is Long -> { output.writeByte(LONG); output.writeLong(value) }
                    is Float -> { output.writeByte(FLOAT); output.writeFloat(value) }
                    is Boolean -> { output.writeByte(BOOLEAN); output.writeBoolean(value) }
                    is Set<*> -> {
                        output.writeByte(STRING_SET)
                        output.writeInt(value.size)
                        value.forEach { output.writeString(it as String) }
                    }
                    else -> throw IOException("Unsupported account preference type")
                }
            }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): Map<String?, Any> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val values = LinkedHashMap<String?, Any>()
        repeat(input.readCount()) {
            val key = if (input.readBoolean()) input.readString() else null
            val value: Any = when (input.readUnsignedByte()) {
                STRING -> input.readString()
                INT -> input.readInt()
                LONG -> input.readLong()
                FLOAT -> input.readFloat()
                BOOLEAN -> input.readBoolean()
                STRING_SET -> LinkedHashSet<String>().apply { repeat(input.readCount()) { add(input.readString()) } }
                else -> throw IOException("Unsupported account preference type")
            }
            if (values.put(key, value) != null) throw IOException("Duplicate account preference key")
        }
        if (input.available() != 0) throw IOException("Trailing account storage data")
        values
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Account preference is too large")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        if (size < 0 || size > available()) throw IOException("Invalid account preference size")
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readCount(): Int = readInt().also {
        if (it < 0 || it > available()) throw IOException("Invalid account preference count")
    }

    private companion object {
        val HEADER = byteArrayOf(0x4e, 0x41, 0x43, 0x31)
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val STRING = 1
        const val INT = 2
        const val LONG = 3
        const val FLOAT = 4
        const val BOOLEAN = 5
        const val STRING_SET = 6
    }
}
