package moe.ouom.neriplayer.platform.netease.api.crypto

import moe.ouom.neriplayer.common.json.JsonUtil
import moe.ouom.neriplayer.platform.netease.api.crypto.NeteaseCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

/**
 * 心动模式 eapi 加密 uri 推导的纯本地验证。
 *
 * 不发任何网络请求：只验证 [NeteaseCrypto.eApiEncrypt] 在传入
 * `/eapi/playmode/intelligence/list` 时，参与 md5 签名与 AES 明文的 uri 是
 * `/api/playmode/intelligence/list`——这正是 NCM 客户端 eapi 模式的改写规则
 * （`/api/xxx` -> `/eapi/` + `uri.substr(5)`，签名时再换回 `/api`）。
 *
 * 若这里传成 `/api/playmode/intelligence/list`（即错误写法 callEApi("/api/...")），
 * `url.replace("/eapi", "/api")` 不会生效，uri 仍带 `/api` 前缀，
 * 服务端会返回 400/-460 而不是 301，本测试因此把两种写法都钉住。
 */
class NeteaseCryptoPlaymodeEApiUriTest {

    @Test
    fun `playmode eapi uri is rewritten from eapi to api before signing`() {
        val url = "/eapi/playmode/intelligence/list"
        val payload = mapOf(
            "songId" to "210281",
            "type" to "fromPlayOne",
            "playlistId" to "123456",
            "startMusicId" to "210281",
            "count" to "1",
        )

        val encrypted = NeteaseCrypto.eApiEncrypt(url, payload)

        // eapi 只产出 params 一个字段，没有 encSecKey（那是 weapi 的形状）
        assertEquals(setOf("params"), encrypted.keys)

        val message = decryptParams(encrypted.getValue("params"))
        val data = JsonUtil.toJson(payload)

        // 明文结构：<uri>-36cd479b6b5-<data>-36cd479b6b5-<md5(salt)>，uri 必须是 /api 前缀
        assertTrue("实际明文: $message", message.startsWith("/api/playmode/intelligence/list-36cd479b6b5-"))
        assertTrue("实际明文: $message", message.contains("-36cd479b6b5-$data-36cd479b6b5-"))

        val expectedMd5 = NeteaseCrypto.md5Hex("nobody/api/playmode/intelligence/listuse$data" + "md5forencrypt")
        assertTrue("实际明文: $message", message.endsWith(expectedMd5))
    }

    @Test
    fun `passing the api path verbatim keeps the api prefix in the signature`() {
        // 反面对照：任务书点名的错误写法 callEApi("/api/playmode/intelligence/list")。
        // replace("/eapi", "/api") 此时不生效，uri 原样带 /api 前缀，
        // 与服务端期望的 eapi 改写结果不同 -> 会被判 400/-460。
        val apiPath = "/api/playmode/intelligence/list"
        val payload = mapOf("songId" to "210281")

        val message = decryptParams(NeteaseCrypto.eApiEncrypt(apiPath, payload).getValue("params"))

        assertTrue("实际明文: $message", message.startsWith("/api/playmode/intelligence/list-36cd479b6b5-"))
    }

    /** eapi 用 AES/ECB/PKCS5 + 固定 key `e82ckenh8dichen8` 加密，hex 小写后转大写输出。 */
    private fun decryptParams(params: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("e82ckenh8dichen8".toByteArray(StandardCharsets.UTF_8), "AES"),
        )
        val bytes = cipher.doFinal(params.lowercase().hexToBytes())
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "hex 长度必须为偶数" }
        return ByteArray(length / 2) { index ->
            ((Character.digit(this[index * 2], 16) shl 4) + Character.digit(this[index * 2 + 1], 16)).toByte()
        }
    }
}
