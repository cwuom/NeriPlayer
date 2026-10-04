package moe.ouom.neriplayer.core.player.service.car

import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Process
import android.os.Build
import android.content.pm.ApplicationInfo

internal const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
private const val GOOGLE_SERVICES_PACKAGE = "com.google.android.gms"

internal fun isTrustedCarClient(
    clientPackage: String,
    packageBelongsToUid: Boolean,
    sameApplicationUid: Boolean,
    systemUid: Boolean,
    trustedMediaController: Boolean,
    googleSignatureMatches: Boolean,
    googleServicesSystemApp: Boolean,
): Boolean {
    if (!packageBelongsToUid) return false
    if (sameApplicationUid || systemUid || trustedMediaController) return true
    return clientPackage == ANDROID_AUTO_PACKAGE && googleSignatureMatches && googleServicesSystemApp
}

object CarControllerTrust {
    fun isArtworkTrusted(context: Context, callerUid: Int): Boolean {
        if (callerUid == context.applicationInfo.uid || callerUid == Process.SYSTEM_UID) return true
        if (context.checkPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL, -1, callerUid) ==
            PackageManager.PERMISSION_GRANTED) return true
        val packages = context.packageManager.getPackagesForUid(callerUid).orEmpty()
        return ANDROID_AUTO_PACKAGE in packages && hasGoogleSignature(context, ANDROID_AUTO_PACKAGE) &&
            isSystemGoogleServices(context)
    }

    fun isTrusted(context: Context, callerUid: Int): Boolean =
        context.packageManager.getPackagesForUid(callerUid)
            .orEmpty()
            .any { isTrusted(context, it, callerUid) }

    fun isTrusted(context: Context, clientPackage: String, callerUid: Int): Boolean {
        val packages = context.packageManager.getPackagesForUid(callerUid).orEmpty()
        val belongsToUid = clientPackage in packages
        if (!belongsToUid) return false
        return isTrustedCarClient(
            clientPackage = clientPackage,
            packageBelongsToUid = true,
            sameApplicationUid = callerUid == context.applicationInfo.uid,
            systemUid = callerUid == Process.SYSTEM_UID,
            trustedMediaController = isTrustedMediaController(context, clientPackage, callerUid),
            googleSignatureMatches = hasGoogleSignature(context, clientPackage),
            googleServicesSystemApp = clientPackage == ANDROID_AUTO_PACKAGE && isSystemGoogleServices(context),
        )
    }

    private fun isTrustedMediaController(context: Context, clientPackage: String, uid: Int): Boolean =
        runCatching {
            val manager = context.getSystemService(MediaSessionManager::class.java) ?: return false
            manager.isTrustedForMediaControl(MediaSessionManager.RemoteUserInfo(clientPackage, -1, uid))
        }.getOrDefault(false)

    private fun hasGoogleSignature(context: Context, clientPackage: String): Boolean {
        if (clientPackage != ANDROID_AUTO_PACKAGE) return false
        return context.packageManager.checkSignatures(clientPackage, GOOGLE_SERVICES_PACKAGE) ==
            PackageManager.SIGNATURE_MATCH
    }

    private fun isSystemGoogleServices(context: Context): Boolean = runCatching {
        val manager = context.packageManager
        val application = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getApplicationInfo(GOOGLE_SERVICES_PACKAGE, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            manager.getApplicationInfo(GOOGLE_SERVICES_PACKAGE, 0)
        }
        application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)
}
