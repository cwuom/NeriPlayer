package moe.ouom.neriplayer.testing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.parseRawCookieText
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle

class DebugCookieImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_IMPORT_AUTH -> handleImport(intent)
            ACTION_CLEAR_AUTH -> handleClear(intent)
            else -> {
                resultCode = Activity.RESULT_CANCELED
                resultData = "unsupported action"
            }
        }
    }

    private fun handleImport(intent: Intent) {
        val platform = intent.getStringExtra(EXTRA_PLATFORM)
            ?.trim()
            ?.lowercase()
            .orEmpty()
        val cookies = parseRawCookieText(
            resolveCookiePayload(intent)
        )
        if (cookies.isEmpty()) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "empty cookie payload"
            return
        }

        when (platform) {
            PLATFORM_BILI -> {
                if (cookies["SESSDATA"].isNullOrBlank()) {
                    fail("missing SESSDATA")
                    return
                }
                AppContainer.biliCookieRepo.saveCookies(cookies)
                succeed(platform, cookies.keys)
            }

            PLATFORM_NETEASE -> {
                val validation = AppContainer.neteaseCookieRepo.validateCookies(cookies)
                if (!validation.isAccepted) {
                    fail("invalid netease cookies")
                    return
                }
                AppContainer.neteaseCookieRepo.saveCookies(validation.sanitizedCookies)
                succeed(platform, validation.sanitizedCookies.keys)
            }

            PLATFORM_YOUTUBE -> {
                val normalized = YouTubeAuthBundle(
                    cookieHeader = cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" },
                    cookies = cookies,
                    savedAt = System.currentTimeMillis()
                ).normalized()
                if (!normalized.isUsable()) {
                    fail("invalid youtube cookies")
                    return
                }
                AppContainer.youtubeAuthRepo.saveAuth(normalized)
                succeed(platform, normalized.cookies.keys)
            }

            else -> fail("unknown platform: $platform")
        }
    }

    private fun handleClear(intent: Intent) {
        when (
            intent.getStringExtra(EXTRA_PLATFORM)
                ?.trim()
                ?.lowercase()
                .orEmpty()
        ) {
            PLATFORM_BILI -> AppContainer.biliCookieRepo.clear()
            PLATFORM_NETEASE -> AppContainer.neteaseCookieRepo.clear()
            PLATFORM_YOUTUBE -> AppContainer.youtubeAuthRepo.clear()
            PLATFORM_ALL, "" -> {
                AppContainer.biliCookieRepo.clear()
                AppContainer.neteaseCookieRepo.clear()
                AppContainer.youtubeAuthRepo.clear()
            }

            else -> {
                fail("unknown platform")
                return
            }
        }
        resultCode = Activity.RESULT_OK
        resultData = "cleared"
    }

    private fun succeed(platform: String, keys: Iterable<String>) {
        resultCode = Activity.RESULT_OK
        resultData = "imported $platform cookies: ${keys.joinToString(",")}"
    }

    private fun fail(message: String) {
        resultCode = Activity.RESULT_CANCELED
        resultData = message
    }

    private fun resolveCookiePayload(intent: Intent): String {
        val encoded = intent.getStringExtra(EXTRA_COOKIE_BASE64).orEmpty()
        if (encoded.isNotBlank()) {
            return runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
        }
        return intent.getStringExtra(EXTRA_COOKIE).orEmpty()
    }

    companion object {
        const val ACTION_IMPORT_AUTH = "moe.ouom.neriplayer.debug.IMPORT_AUTH"
        const val ACTION_CLEAR_AUTH = "moe.ouom.neriplayer.debug.CLEAR_AUTH"

        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_COOKIE_BASE64 = "cookie_base64"

        const val PLATFORM_BILI = "bili"
        const val PLATFORM_NETEASE = "netease"
        const val PLATFORM_YOUTUBE = "youtube"
        const val PLATFORM_ALL = "all"
    }
}
