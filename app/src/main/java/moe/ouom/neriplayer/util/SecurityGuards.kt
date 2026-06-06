package moe.ouom.neriplayer.util

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
 */

import android.net.Uri
import android.webkit.WebResourceRequest
import java.io.File
import java.util.Locale

private fun normalizeSecurityHost(host: String?): String {
    return host
        ?.trim()
        ?.trimEnd('.')
        ?.lowercase(Locale.US)
        .orEmpty()
}

internal fun isExactHostOrSubdomain(host: String?, rootDomain: String): Boolean {
    val normalizedHost = normalizeSecurityHost(host)
    val normalizedRootDomain = normalizeSecurityHost(rootDomain)
    if (normalizedHost.isBlank() || normalizedRootDomain.isBlank()) {
        return false
    }
    return normalizedHost == normalizedRootDomain ||
        normalizedHost.endsWith(".$normalizedRootDomain")
}

internal fun isAllowedHttpsUri(uri: Uri?, allowHost: (String?) -> Boolean): Boolean {
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true)) {
        return false
    }
    return allowHost(uri.host)
}

internal fun shouldBlockMainFrameNavigation(
    isForMainFrame: Boolean,
    isAllowedNavigation: Boolean
): Boolean = isForMainFrame && !isAllowedNavigation

internal fun isAllowedMainFrameRequest(
    request: WebResourceRequest?,
    allowUri: (Uri) -> Boolean
): Boolean {
    val currentRequest = request ?: return false
    return !shouldBlockMainFrameNavigation(
        isForMainFrame = currentRequest.isForMainFrame,
        isAllowedNavigation = allowUri(currentRequest.url)
    )
}

internal fun isFileInsideDirectory(file: File, directory: File): Boolean {
    val filePath = runCatching { file.canonicalFile.toPath() }
        .getOrElse { file.absoluteFile.toPath() }
    val directoryPath = runCatching { directory.canonicalFile.toPath() }
        .getOrElse { directory.absoluteFile.toPath() }
    return filePath.startsWith(directoryPath)
}
