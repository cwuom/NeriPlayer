package moe.ouom.neriplayer.util

import android.net.Uri
import android.webkit.WebResourceRequest
import java.io.File

internal fun isExactHostOrSubdomain(host: String?, rootDomain: String): Boolean {
    return moe.ouom.neriplayer.util.network.isExactHostOrSubdomain(host, rootDomain)
}

internal fun isAllowedHttpsUri(uri: Uri?, allowHost: (String?) -> Boolean): Boolean {
    return moe.ouom.neriplayer.util.network.isAllowedHttpsUri(uri, allowHost)
}

internal fun shouldBlockMainFrameNavigation(
    isForMainFrame: Boolean,
    isAllowedNavigation: Boolean
): Boolean {
    return moe.ouom.neriplayer.util.network.shouldBlockMainFrameNavigation(
        isForMainFrame = isForMainFrame,
        isAllowedNavigation = isAllowedNavigation
    )
}

internal fun isAllowedMainFrameRequest(
    request: WebResourceRequest?,
    allowUri: (Uri) -> Boolean
): Boolean {
    return moe.ouom.neriplayer.util.network.isAllowedMainFrameRequest(request, allowUri)
}

internal fun isFileInsideDirectory(file: File, directory: File): Boolean {
    return moe.ouom.neriplayer.util.network.isFileInsideDirectory(file, directory)
}
