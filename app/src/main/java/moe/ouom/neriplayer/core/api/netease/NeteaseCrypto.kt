package moe.ouom.neriplayer.core.api.netease

import moe.ouom.neriplayer.util.JsonUtil
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.api.netease/NeteaseCrypto
 * Created: 2025/8/10
 */

/** 加解密工具 */
object NeteaseCrypto {
    private const val base62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val presetKey = "0CoJUm6Qyw8W8jud"
    private const val iv = "0102030405060708"
    private const val linuxKey = "rFgB&h#%2?^eDg:Q"
    private const val eapiKey = "e82ckenh8dichen8"
    private const val eapiFormat = "%s-36cd479b6b5-%s-36cd479b6b5-%s"
    private const val eapiSalt = "nobody%suse%smd5forencrypt"
    private const val publicKeyPem = """
        -----BEGIN PUBLIC KEY-----
        MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFb
        t7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZ
        MldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB
        -----END PUBLIC KEY-----
    """

    fun randomKey(): String {
        val random = Random()
        val sb = StringBuilder()
        repeat(16) { sb.append(base62[random.nextInt(base62.length)]) }
        return sb.toString()
    }

    private fun reverseString(input: String) = input.reversed()

    private fun aesEncrypt(text: String, key: String, ivStr: String, mode: String, format: String): String {
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        val cipher: Cipher = when (mode.lowercase(Locale.getDefault())) {
            "cbc" -> {
                val ci = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val ivSpec = IvParameterSpec(ivStr.toByteArray(StandardCharsets.UTF_8))
                ci.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                ci
            }
            "ecb" -> {
                val ci = Cipher.getInstance("AES/ECB/PKCS5Padding")
                ci.init(Cipher.ENCRYPT_MODE, secretKey)
                ci
            }
            else -> throw IllegalArgumentException("未知 AES 模式: $mode")
        }
        val encrypted = cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8))
        return when (format.lowercase(Locale.getDefault())) {
            "base64" -> Base64.getEncoder().encodeToString(encrypted)
            "hex" -> encrypted.joinToString("") { "%02x".format(it) }
            "hex".uppercase(Locale.getDefault()) -> encrypted.joinToString("") { "%02x".format(it) }.uppercase(Locale.getDefault())
            else -> throw IllegalArgumentException("未知加密输出格式: $format")
        }
    }

    private fun aesDecrypt(cipherText: String, key: String, ivStr: String, mode: String, format: String): ByteArray {
        val data = when (format.lowercase(Locale.getDefault())) {
            "base64" -> Base64.getDecoder().decode(cipherText)
            "hex" -> {
                val len = cipherText.length
                val arr = ByteArray(len / 2)
                var i = 0
                while (i < len) {
                    arr[i / 2] = (((Character.digit(cipherText[i], 16) shl 4) +
                            Character.digit(cipherText[i + 1], 16))).toByte()
                    i += 2
                }
                arr
            }
            "" -> cipherText.toByteArray(StandardCharsets.UTF_8)
            else -> throw IllegalArgumentException("未知解码格式: $format")
        }
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES")
        val plain = when (mode.lowercase(Locale.getDefault())) {
            "cbc" -> {
                val ci = Cipher.getInstance("AES/CBC/PKCS5Padding")
                ci.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ivStr.toByteArray(StandardCharsets.UTF_8)))
                ci.doFinal(data)
            }
            "ecb" -> {
                val ci = Cipher.getInstance("AES/ECB/PKCS5Padding")
                ci.init(Cipher.DECRYPT_MODE, secretKey)
                ci.doFinal(data)
            }
            else -> throw IllegalArgumentException("未知 AES 模式: $mode")
        }
        // PKCS#7 去填充
        val pad = plain.last().toInt()
        return plain.copyOfRange(0, plain.size - pad)
    }

    /** RSA 加密随机密钥，使用与官方客户端一致的无填充算法。 */
    private fun rsaEncrypt(text: String): String {
        return try {
            val cleanedKey = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keyBytes = Base64.getDecoder().decode(cleanedKey)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val pubKey = KeyFactory.getInstance("RSA")
                .generatePublic(keySpec) as java.security.interfaces.RSAPublicKey

            val message = java.math.BigInteger(1, text.toByteArray(StandardCharsets.UTF_8))
            val result = message.modPow(pubKey.publicExponent, pubKey.modulus)

            var bytes = result.toByteArray()
            if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            throw RuntimeException("RSA 加密失败", e)
        }
    }


    fun md5Hex(data: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun weApiEncrypt(payload: Map<String, Any>): Map<String, String> {
        val json = JsonUtil.toJson(payload)
        val secretKey = randomKey()
        val enc1 = aesEncrypt(json, presetKey, iv, "cbc", "base64")
        val params = aesEncrypt(enc1, secretKey, iv, "cbc", "base64")
        val encSecKey = rsaEncrypt(reverseString(secretKey))
        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    fun linuxApiEncrypt(payload: Map<String, Any>) =
        mapOf("eparams" to aesEncrypt(JsonUtil.toJson(payload), linuxKey, "", "ecb", "hex"))

    fun eApiEncrypt(url: String, payload: Map<String, Any>): Map<String, String> {
        val data = JsonUtil.toJson(payload)
        val message = String.format(
            eapiFormat,
            url.replace("/eapi", "/api"),
            data,
            md5Hex(String.format(eapiSalt, url.replace("/eapi", "/api"), data))
        )
        val cipher = aesEncrypt(message, eapiKey, "", "ecb", "hex").uppercase(Locale.getDefault())
        return mapOf("params" to cipher)
    }

    fun linuxApiDecrypt(cipher: String): String {
        val plain = aesDecrypt(cipher, linuxKey, "", "ecb", "hex")
        return String(plain, StandardCharsets.UTF_8)
    }

    fun anonymous(deviceId: String): String {
        val xorKey = "3go8&$8*3*3h0k(2)2"
        val bytes = deviceId.toByteArray(StandardCharsets.UTF_8)
        val xorBytes = ByteArray(bytes.size) { (bytes[it].toInt() xor xorKey[it % xorKey.length].code).toByte() }
        val md5 = md5Hex(String(xorBytes, StandardCharsets.UTF_8))
        val encoded = Base64.getUrlEncoder().encodeToString(md5.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val content = "$deviceId $encoded"
        return Base64.getUrlEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8))
    }

    fun generateWnmcId(): String {
        val letters = CharArray(6) { ('a' + Random().nextInt(26)) }
        val timestamp = System.currentTimeMillis()
        return "${letters.joinToString("")}.$timestamp.01.0"
    }
}