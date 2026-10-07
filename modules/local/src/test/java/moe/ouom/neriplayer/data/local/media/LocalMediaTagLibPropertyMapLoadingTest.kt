package moe.ouom.neriplayer.data.local.media

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class LocalMediaTagLibPropertyMapLoadingTest {

    @Test
    fun `property maps are unavailable when the tag reader cannot load while the caller keeps its descriptor`() {
        val descriptor = mock(ParcelFileDescriptor::class.java)
        val duplicate = mock(ParcelFileDescriptor::class.java)
        doReturn(duplicate).`when`(descriptor).dup()
        doReturn(41).`when`(duplicate).detachFd()

        val propertyMap = LocalMediaSupport.loadTagLibPropertyMap(descriptor)

        assertNull(propertyMap)
        verify(descriptor).dup()
        verify(duplicate).detachFd()
        verify(descriptor, never()).detachFd()
        verify(descriptor, never()).close()
    }
}
