package moe.ouom.neriplayer.data.platform.youtube

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import moe.ouom.neriplayer.util.NPLogger

internal val YOUTUBE_BACKGROUND_WEBVIEW_GUARD_ORIGIN_RULES: Set<String> = setOf(
    "https://www.youtube.com",
    "https://music.youtube.com"
)

internal fun buildYouTubeBackgroundMediaSessionGuardScript(): String {
    return """
        (() => {
          try {
            const mediaSession = globalThis.navigator?.mediaSession;
            if (!mediaSession) {
              return;
            }
            const noop = function() {};
            const patch = (target) => {
              if (!target || typeof target.setPositionState !== 'function') {
                return;
              }
              Object.defineProperty(target, 'setPositionState', {
                configurable: true,
                writable: true,
                value: noop
              });
            };
            patch(globalThis.MediaSession?.prototype);
            patch(mediaSession);
          } catch (error) {}
        })();
    """.trimIndent()
}

internal fun installYouTubeBackgroundWebViewGuard(
    webView: WebView,
    logTag: String
): ScriptHandler? {
    val scriptHandler = if (
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    ) {
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                buildYouTubeBackgroundMediaSessionGuardScript(),
                YOUTUBE_BACKGROUND_WEBVIEW_GUARD_ORIGIN_RULES
            )
        }.onFailure { error ->
            NPLogger.w(logTag, "Failed to install YouTube background WebView guard", error)
        }.getOrNull()
    } else {
        NPLogger.d(logTag, "Document-start script unsupported for YouTube background WebView guard")
        null
    }

    if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
        runCatching {
            WebViewCompat.setAudioMuted(webView, true)
        }.onFailure { error ->
            NPLogger.w(logTag, "Failed to mute YouTube background WebView audio", error)
        }
    }

    return scriptHandler
}

internal fun removeYouTubeBackgroundWebViewGuard(scriptHandler: ScriptHandler?) {
    runCatching {
        scriptHandler?.remove()
    }
}
