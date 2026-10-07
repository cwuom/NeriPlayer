package moe.ouom.neriplayer.core.player.service.car.artwork

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import java.io.FileNotFoundException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class CarArtworkProviderAccessTest {

    private val key = carArtworkKey("song", "https://cover/a")
    private val application = mock(Context::class.java).also {
        `when`(it.packageName).thenReturn(PACKAGE)
        `when`(it.applicationInfo).thenReturn(ApplicationInfo().apply { uid = APP_UID })
        `when`(it.packageManager).thenReturn(mock(PackageManager::class.java))
        `when`(it.checkPermission(anyString(), anyInt(), anyInt())).thenReturn(PackageManager.PERMISSION_DENIED)
    }
    private lateinit var binder: MockedStatic<Binder>

    @Before
    fun mockCallingUid() {
        binder = mockStatic(Binder::class.java)
        callerUid(APP_UID)
    }

    @After
    fun releaseCallingUid() {
        binder.close()
    }

    @Test
    fun `only versioned artwork paths under this package authority are accepted`() {
        assertEquals(key, CarArtworkProvider.validatedKey(application, artworkUri()))

        val rejected = listOf(
            artworkUri(scheme = "file"),
            artworkUri(authority = "com.example.car-artwork"),
            artworkUri(query = "size=512"),
            artworkUri(fragment = "preview"),
            artworkUri(path = "/v2/$key"),
            artworkUri(path = null)
        )
        rejected.forEach { assertNull(CarArtworkProvider.validatedKey(application, it)) }
    }

    @Test
    fun `trusted callers learn the jpeg type of valid artwork only`() {
        val provider = attachedProvider()

        assertEquals("image/jpeg", provider.getType(artworkUri()))
        assertNull(provider.getType(artworkUri(path = "/v1/../secret")))
        assertNull(CarArtworkProvider().getType(artworkUri()))
    }

    @Test
    fun `untrusted callers are rejected before the uri is inspected`() {
        callerUid(UNTRUSTED_UID)
        val provider = attachedProvider()
        val uri = artworkUri()

        assertThrows(SecurityException::class.java) { provider.getType(uri) }
        val denied = assertThrows(SecurityException::class.java) { provider.openFile(uri, "r") }
        assertEquals("Artwork access denied", denied.message)
        verifyNoInteractions(uri)
    }

    @Test
    fun `artwork files open read only and only for valid attached requests`() {
        val provider = attachedProvider()

        assertEquals(
            "Artwork is read only",
            assertThrows(FileNotFoundException::class.java) { provider.openFile(artworkUri(), "rw") }.message
        )
        assertEquals(
            "Artwork unavailable",
            assertThrows(FileNotFoundException::class.java) {
                CarArtworkProvider().openFile(artworkUri(), "r")
            }.message
        )
        assertEquals(
            "Invalid artwork URI",
            assertThrows(FileNotFoundException::class.java) {
                provider.openFile(artworkUri(fragment = "x"), "r")
            }.message
        )
    }

    private fun callerUid(uid: Int) {
        binder.`when`<Int> { Binder.getCallingUid() }.thenReturn(uid)
    }

    private fun attachedProvider(): CarArtworkProvider =
        spy(CarArtworkProvider()).also { doReturn(application).`when`(it).context }

    private fun artworkUri(
        scheme: String? = "content",
        authority: String? = "$PACKAGE.car-artwork",
        path: String? = "/v1/$key",
        query: String? = null,
        fragment: String? = null
    ): Uri = mock(Uri::class.java).also {
        `when`(it.scheme).thenReturn(scheme)
        `when`(it.authority).thenReturn(authority)
        `when`(it.encodedPath).thenReturn(path)
        `when`(it.encodedQuery).thenReturn(query)
        `when`(it.encodedFragment).thenReturn(fragment)
    }

    private companion object {
        const val PACKAGE = "moe.ouom.neriplayer"
        const val APP_UID = 10_123
        const val UNTRUSTED_UID = 10_999
    }
}
