package moe.ouom.neriplayer.data.sync.webdav

internal fun shouldAllowUnconditionalWebDavWrite(
    expectedFingerprint: String?,
    currentFingerprint: String?
): Boolean {
    return !expectedFingerprint.isNullOrBlank() &&
        expectedFingerprint == currentFingerprint
}
