package moe.ouom.neriplayer.data.config

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ConfigExportOptions(val includeLoginState: Boolean = false)

class ConfigBackupPasswordException : Exception("Incorrect password or damaged config backup")

class ConfigBackupPasswordRequiredException : Exception("Config backup requires a password")

internal object ConfigBackupLimits {
    const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
    const val MAX_ENVELOPE_BYTES = 3 * 1024 * 1024

    fun requireBytes(raw: String, maximum: Int) {
        require(raw.length <= maximum) { "Config backup is too large" }
        val bytes = raw.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= maximum) { "Config backup is too large" }
        } finally {
            bytes.fill(0)
        }
    }
}

internal object ConfigBackupEncryption {
    private const val KIND = "moe.ouom.neriplayer.config.encrypted"
    private const val FORMAT_VERSION = 1
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private const val ITERATIONS = 600_000
    private const val CIPHER = "AES-256-GCM"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val MAX_PASSWORD_CHARACTERS = 1024
    private const val MAX_CIPHERTEXT_BYTES = ConfigBackupLimits.MAX_PLAINTEXT_BYTES + TAG_BITS / 8
    private val json = Json { prettyPrint = true }
    private val associatedData = "$KIND|$FORMAT_VERSION|$KDF|$ITERATIONS|$CIPHER".toByteArray(Charsets.UTF_8)

    @Serializable
    private data class Envelope(
        val kind: String,
        val formatVersion: Int,
        val kdf: String,
        val iterations: Int,
        val cipher: String,
        val salt: String,
        val iv: String,
        val ciphertext: String
    )

    fun isEncrypted(root: JsonObject): Boolean =
        (root["kind"] as? JsonPrimitive)?.contentOrNull == KIND

    fun encrypt(raw: String, password: CharArray): String {
        requirePassword(password)
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val plaintext = raw.toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            withKey(password, salt) { key -> cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext) }
        } finally {
            plaintext.fill(0)
        }
        return json.encodeToString(
            Envelope.serializer(),
            Envelope(KIND, FORMAT_VERSION, KDF, ITERATIONS, CIPHER, encode(salt), encode(iv), encode(ciphertext))
        )
    }

    fun decrypt(raw: String, password: CharArray?): String {
        val envelope = parseEnvelope(raw)
        val salt = decode(envelope.salt, SALT_BYTES, SALT_BYTES)
        val iv = decode(envelope.iv, IV_BYTES, IV_BYTES)
        val ciphertext = decode(envelope.ciphertext, TAG_BITS / 8, MAX_CIPHERTEXT_BYTES)
        if (password == null) throw ConfigBackupPasswordRequiredException()
        requirePassword(password)
        val plaintext = try {
            withKey(password, salt) { key -> cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(ciphertext) }
        } catch (_: AEADBadTagException) {
            throw ConfigBackupPasswordException()
        }
        return try {
            require(plaintext.size <= ConfigBackupLimits.MAX_PLAINTEXT_BYTES) { "Config backup is too large" }
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun parseEnvelope(raw: String): Envelope {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), raw)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("Invalid encrypted config backup")
        }
        require(
            envelope.kind == KIND && envelope.formatVersion == FORMAT_VERSION &&
                envelope.kdf == KDF && envelope.iterations == ITERATIONS && envelope.cipher == CIPHER
        ) { "Unsupported encrypted config backup format" }
        return envelope
    }

    private fun requirePassword(password: CharArray) {
        require(password.isNotEmpty() && password.size <= MAX_PASSWORD_CHARACTERS) { "Invalid config backup password length" }
    }

    private fun <T> withKey(password: CharArray, salt: ByteArray, block: (SecretKeySpec) -> T): T {
        val specification = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        val encoded = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
        return try {
            block(SecretKeySpec(encoded, "AES"))
        } finally {
            encoded.fill(0)
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(associatedData)
        }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(raw: String, minimumBytes: Int, maximumBytes: Int): ByteArray {
        require(raw.length <= ((maximumBytes + 2) / 3) * 4) { "Invalid encrypted config backup data" }
        val decoded = try {
            Base64.getDecoder().decode(raw)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid encrypted config backup data")
        }
        require(decoded.size in minimumBytes..maximumBytes) { "Invalid encrypted config backup data" }
        require(encode(decoded) == raw) { "Invalid encrypted config backup data" }
        return decoded
    }
}
