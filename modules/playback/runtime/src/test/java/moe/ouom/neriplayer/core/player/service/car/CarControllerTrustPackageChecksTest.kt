package moe.ouom.neriplayer.core.player.service.car

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class CarControllerTrustPackageChecksTest {

    private val packageManager = mock(PackageManager::class.java).also {
        `when`(it.checkSignatures(anyString(), anyString())).thenReturn(PackageManager.SIGNATURE_NO_MATCH)
    }
    private val sessionManager = mock(MediaSessionManager::class.java)
    private val context = mock(Context::class.java).also {
        `when`(it.applicationInfo).thenReturn(ApplicationInfo().apply { uid = APP_UID })
        `when`(it.packageManager).thenReturn(packageManager)
        `when`(it.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
        `when`(it.getSystemService(MediaSessionManager::class.java)).thenReturn(sessionManager)
    }

    @Test
    fun `artwork is served to the app itself the system and media content controllers`() {
        `when`(
            context.checkPermission(Manifest.permission.MEDIA_CONTENT_CONTROL, -1, CONTROLLER_UID)
        ).thenReturn(PackageManager.PERMISSION_GRANTED)

        assertTrue(CarControllerTrust.isArtworkTrusted(context, APP_UID))
        assertTrue(CarControllerTrust.isArtworkTrusted(context, Process.SYSTEM_UID))
        assertTrue(CarControllerTrust.isArtworkTrusted(context, CONTROLLER_UID))
        verify(packageManager, never()).getPackagesForUid(anyInt())
    }

    @Test
    fun `artwork for other callers requires android auto signed like system google services`() {
        packagesFor(UNKNOWN_UID)
        packagesFor(OTHER_APP_UID, "com.example.car")
        packagesFor(AUTO_UID, ANDROID_AUTO_PACKAGE)

        assertFalse(CarControllerTrust.isArtworkTrusted(context, UNKNOWN_UID))
        assertFalse(CarControllerTrust.isArtworkTrusted(context, OTHER_APP_UID))
        assertFalse(CarControllerTrust.isArtworkTrusted(context, AUTO_UID))

        signAutoLikeGoogleServices()
        googleServicesFlags(0)
        assertFalse(CarControllerTrust.isArtworkTrusted(context, AUTO_UID))

        googleServicesFlags(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        assertTrue(CarControllerTrust.isArtworkTrusted(context, AUTO_UID))
    }

    @Test
    fun `missing google services makes android auto untrusted`() {
        packagesFor(AUTO_UID, ANDROID_AUTO_PACKAGE)
        signAutoLikeGoogleServices()
        `when`(packageManager.getApplicationInfo(GOOGLE_SERVICES, 0))
            .thenThrow(PackageManager.NameNotFoundException(GOOGLE_SERVICES))

        assertFalse(CarControllerTrust.isArtworkTrusted(context, AUTO_UID))
        assertFalse(CarControllerTrust.isTrusted(context, ANDROID_AUTO_PACKAGE, AUTO_UID))
    }

    @Test
    fun `client package must belong to the calling uid`() {
        packagesFor(APP_UID, "moe.ouom.neriplayer")

        assertFalse(CarControllerTrust.isTrusted(context, "com.example.spoof", APP_UID))
        assertTrue(CarControllerTrust.isTrusted(context, "moe.ouom.neriplayer", APP_UID))
    }

    @Test
    fun `system clients and system approved media controllers are trusted`() {
        packagesFor(Process.SYSTEM_UID, "android")
        packagesFor(CONTROLLER_UID, "com.example.controller")
        `when`(sessionManager.isTrustedForMediaControl(any())).thenReturn(true)

        assertTrue(CarControllerTrust.isTrusted(context, "android", Process.SYSTEM_UID))
        assertTrue(CarControllerTrust.isTrusted(context, "com.example.controller", CONTROLLER_UID))
        verify(packageManager, never()).checkSignatures(anyString(), anyString())
    }

    @Test
    fun `unavailable or failing media session checks do not grant trust`() {
        packagesFor(CONTROLLER_UID, "com.example.controller")
        `when`(sessionManager.isTrustedForMediaControl(any())).thenThrow(SecurityException("denied"))

        assertFalse(CarControllerTrust.isTrusted(context, "com.example.controller", CONTROLLER_UID))

        `when`(context.getSystemService(MediaSessionManager::class.java)).thenReturn(null)
        assertFalse(CarControllerTrust.isTrusted(context, "com.example.controller", CONTROLLER_UID))
    }

    @Test
    fun `android auto is trusted only with a google signature and system google services`() {
        packagesFor(AUTO_UID, ANDROID_AUTO_PACKAGE, "com.example.helper")

        assertFalse(CarControllerTrust.isTrusted(context, AUTO_UID))

        signAutoLikeGoogleServices()
        googleServicesFlags(0)
        assertFalse(CarControllerTrust.isTrusted(context, AUTO_UID))

        googleServicesFlags(ApplicationInfo.FLAG_SYSTEM)
        assertTrue(CarControllerTrust.isTrusted(context, AUTO_UID))
        assertFalse(CarControllerTrust.isTrusted(context, "com.example.helper", AUTO_UID))
    }

    private fun packagesFor(uid: Int, vararg packages: String) {
        val owned: Array<String>? = if (packages.isEmpty()) null else arrayOf(*packages)
        `when`(packageManager.getPackagesForUid(uid)).thenReturn(owned)
    }

    private fun signAutoLikeGoogleServices() {
        `when`(packageManager.checkSignatures(ANDROID_AUTO_PACKAGE, GOOGLE_SERVICES))
            .thenReturn(PackageManager.SIGNATURE_MATCH)
    }

    private fun googleServicesFlags(flags: Int) {
        val info = ApplicationInfo().apply { this.flags = flags }
        `when`(packageManager.getApplicationInfo(GOOGLE_SERVICES, 0)).thenReturn(info)
    }

    private companion object {
        const val APP_UID = 10_123
        const val CONTROLLER_UID = 10_200
        const val UNKNOWN_UID = 10_300
        const val OTHER_APP_UID = 10_400
        const val AUTO_UID = 10_500
        const val GOOGLE_SERVICES = "com.google.android.gms"
    }
}
