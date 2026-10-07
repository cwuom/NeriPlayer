package moe.ouom.neriplayer.data.local.audioimport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAudioImportMediaStoreAuthorityTest {
    @Test
    fun `media store and media documents providers are media store authorities`() {
        assertTrue(LocalAudioImportManager.isMediaStoreAuthority("media"))
        assertTrue(LocalAudioImportManager.isMediaStoreAuthority("com.android.providers.media.documents"))
    }

    @Test
    fun `other document providers and missing authorities are not media store authorities`() {
        assertFalse(LocalAudioImportManager.isMediaStoreAuthority("com.android.externalstorage.documents"))
        assertFalse(LocalAudioImportManager.isMediaStoreAuthority("com.android.providers.downloads.documents"))
        assertFalse(LocalAudioImportManager.isMediaStoreAuthority(null))
    }
}
