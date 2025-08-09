package moe.ouom.neriplayer.core.api.netease

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐 API 加密模块
 * 提供 weapi / linuxapi / eapi 三种加密方式，以及 AES、RSA 工具方法
 */
object NeteaseCrypto {
    // AES CBC 模式固定 IV
    private const val iv: String = "0102030405060708"
    // weapi 固定密钥
    private const val presetKey: String = "0CoJUm6Qyw8W8jud"
    // linuxapi 固定密钥
    private const val linuxapiKey: String = "rFgB&h#%2?^eDg:Q"
    // eapi 固定密钥
    private const val eapiKey: String = "e82ckenh8dichen8"
    // Base62 字符集，用于生成随机 AES 密钥
    private val base62: CharArray =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()

    // RSA 公钥，用于加密随机 AES 密钥
    private const val publicKeyPem: String = """
        -----BEGIN PUBLIC KEY-----
        MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFb
        t7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZ
        MldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB
        -----END PUBLIC KEY-----
    """

    /**
     * AES 加密工具
     * @param text 待加密文本
     * @param mode CBC / ECB
     * @param key 密钥
     * @param iv IV（only CBC）
     * @param format 输出格式："base64" / "hex"
     */
    private fun aesEncrypt(
        text: String,
        mode: String,
        key: String,
        iv: String,
        format: String = "base64"
    ): String {
        val cipherMode = when (mode.uppercase()) {
            "CBC" -> "AES/CBC/PKCS5Padding"
            "ECB" -> "AES/ECB/PKCS5Padding"
            else -> throw IllegalArgumentException("不支持的 AES 模式: $mode")
        }
        val cipher = Cipher.getInstance(cipherMode)
        val secretKey: SecretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        if (mode.uppercase() == "CBC") {
            val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        }
        val encryptedBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return when (format.lowercase()) {
            "base64" -> Base64.getEncoder().encodeToString(encryptedBytes)
            "hex" -> toHex(encryptedBytes)
            else -> throw IllegalArgumentException("不支持的输出格式: $format")
        }
    }

    /**
     * RSA 公钥加密
     * 用于加密 AES 随机密钥
     * @return 大写 HEX 字符串
     */
    private fun rsaEncrypt(plain: String, pem: String): String {
        val cleanedPem = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val decodedKey = Base64.getDecoder().decode(cleanedPem)
        val keySpec = X509EncodedKeySpec(decodedKey)
        val keyFactory = KeyFactory.getInstance("RSA")
        val publicKey = keyFactory.generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return toHex(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /**
     * byte[] 转大写十六进制字符串
     */
    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val hex = ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1)
            sb.append(hex)
        }
        return sb.toString().uppercase()
    }

    /**
     * 生成随机 Base62 字符串（用于随机 AES 密钥）
     */
    private fun randomBase62(length: Int = 16): String {
        val random = SecureRandom()
        val chars = CharArray(length) { base62[random.nextInt(base62.size)] }
        return String(chars)
    }

    /**
     * weapi 加密流程：
     *  1. 生成 16 位随机 AES 密钥 secretKey
     *  2. 先用 presetKey + IV 对数据 AES-CBC 加密
     *  3. 再用 secretKey + IV 对结果 AES-CBC 加密
     *  4. 将 secretKey 倒序后用 RSA 公钥加密，得到 encSecKey
     */
    fun weapi(payload: Any): Map<String, String> {
        val text = payload as? String ?: jsonStringify(payload)
        val secretKey = randomBase62(16)
        val firstPass = aesEncrypt(text, "cbc", presetKey, iv)
        val secondPass = aesEncrypt(firstPass, "cbc", secretKey, iv)
        val encSecKey = rsaEncrypt(secretKey.reversed(), publicKeyPem)
        return mapOf("params" to secondPass, "encSecKey" to encSecKey)
    }

    /**
     * linuxapi 加密流程：
     *  1. 将数据转为 JSON 字符串
     *  2. 用固定 linuxapiKey 进行 AES-ECB 加密
     *  3. 输出 hex 格式，键为 eparams
     */
    fun linuxapi(payload: Any): Map<String, String> {
        val text = payload as? String ?: jsonStringify(payload)
        return mapOf("eparams" to aesEncrypt(text, "ecb", linuxapiKey, "", "hex"))
    }

    /**
     * eapi 加密流程：
     *  1. 生成 message = "nobody${url}use${text}md5forencrypt"
     *  2. 计算 message 的 MD5
     *  3. 拼接 data = "${url}-36cd479b6b5-${text}-36cd479b6b5-${md5}"
     *  4. 用固定 eapiKey 进行 AES-ECB 加密（hex 输出）
     */
    fun eapi(url: String, payload: Any): Map<String, String> {
        val text = payload as? String ?: jsonStringify(payload)
        val message = "nobody${url}use${text}md5forencrypt"
        val md5 = md5Hex(message)
        val data = "${url}-36cd479b6b5-${text}-36cd479b6b5-${md5}"
        return mapOf("params" to aesEncrypt(data, "ecb", eapiKey, "", "hex"))
    }

    /**
     * DEBUG: eapi 解密
     */
    fun decrypt(cipherHex: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val secretKey = SecretKeySpec(eapiKey.toByteArray(Charsets.UTF_8), "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        val decrypted = cipher.doFinal(hexStringToByteArray(cipherHex))
        return String(decrypted, Charsets.UTF_8).trim()
    }

    /**
     * 计算 MD5
     */
    private fun md5Hex(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 十六进制字符串转 byte[]
     */
    private fun hexStringToByteArray(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            result[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    /**
     * 简易 JSON 序列化
     * 支持 String、数字、Bool、Map、List、null
     */
    private fun jsonStringify(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"" + value.replace("\"", "\\\"") + "\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                jsonStringify(k.toString()) + ":" + jsonStringify(v)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { jsonStringify(it) }
            else -> jsonStringify(value.toString())
        }
    }
}
