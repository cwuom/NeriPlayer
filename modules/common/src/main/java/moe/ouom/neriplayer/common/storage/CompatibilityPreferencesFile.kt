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
    private val initializationFile = File(directory, "$identifier.initializing")
    private lateinit var key: SecretKeySpec
    private var initializationPending = false

    fun hasArtifacts(): Boolean = dataFile.exists() || keyFile.exists() || initializationFile.exists()

    fun hasUnpublishedInitialization(): Boolean {
        if (dataFile.exists() || !initializationFile.exists()) return false
        return try {
            val encoded = readKey(initializationFile)
            !keyFile.exists() || MessageDigest.isEqual(encoded, readKey(keyFile))
        } catch (_: Exception) {
            false
        }
    }

    fun open(): SharedPreferences {
        ensureDirectory()
        val values = if (initializationFile.exists() || !hasArtifacts()) {
            initialize(initialValues())
        } else {
            if (!dataFile.isFile || !keyFile.isFile) throw IOException("Incomplete account storage")
            key = SecretKeySpec(readKey(keyFile), "AES")
            read()
        }
        initializationPending = false
        return SoftwareEncryptedSharedPreferences(this, values)
    }

    fun openDeferred(): SharedPreferences {
        if (hasArtifacts() && !hasUnpublishedInitialization()) return open()
        ensureDirectory()
        val probe = File.createTempFile(".$identifier.", ".probe", directory)
        try {
            FileOutputStream(probe).use { it.fd.sync() }
        } finally {
            if (!probe.delete()) throw IOException("Cannot remove account storage probe")
        }
        initializationPending = true
        return SoftwareEncryptedSharedPreferences(this, initialValues())
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create account storage")
    }

    private fun initialValues(): Map<String?, Any> =
        mapOf(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING to true)

    private fun initialize(initialValues: Map<String?, Any>): Map<String?, Any> {
        if (!initializationFile.exists()) {
            writeAtomically(initializationFile, ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) })
        }
        val encoded = readKey(initializationFile)
        if (dataFile.exists() && !keyFile.isFile) throw IOException("Incomplete account storage")
        if (!keyFile.exists()) writeAtomically(keyFile, encoded)
        if (!MessageDigest.isEqual(encoded, readKey(keyFile))) throw IOException("Account storage key changed")
        key = SecretKeySpec(encoded, "AES")
        val values = if (dataFile.exists()) {
            read()
        } else {
            writeReady(initialValues)
            initialValues
        }
        // 已发布的数据只认证并保留，避免把中断前成功写入的凭据重置为空
        if (!initializationFile.delete()) throw IOException("Cannot finish account storage initialization")
        return values
    }

    private fun readKey(file: File): ByteArray {
        if (!file.isFile || file.length() != KEY_BYTES.toLong()) throw IOException("Invalid account storage key")
        return file.readBytes().also {
            if (it.size != KEY_BYTES) throw IOException("Invalid account storage key")
        }
    }

    fun write(values: Map<String?, Any>) {
        if (initializationPending) {
            if (hasArtifacts() && !initializationFile.exists()) throw IOException("Account storage appeared before first write")
            if (initialize(values) != values) throw IOException("Account storage changed during initialization")
            initializationPending = false
            return
        }
        writeReady(values)
    }

    private fun writeReady(values: Map<String?, Any>) {
        if (!keyFile.isFile || keyFile.length() != KEY_BYTES.toLong() ||
            !MessageDigest.isEqual(key.encoded, keyFile.readBytes())
        ) throw IOException("Account storage key changed")
        // 已打开的实例也必须保留随后损坏的文件，不能用内存状态掩盖磁盘故障
        if (dataFile.exists()) read()
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
        val encrypted = HEADER + nonce + cipher.doFinal(encode(values))
        if (encrypted.size > MAX_FILE_BYTES) throw IOException("Account storage is too large")
        writeAtomically(dataFile, encrypted)
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val pending = File.createTempFile(".$identifier.", ".pending", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                if (destination != dataFile || initializationFile.exists()) throw error
                Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
