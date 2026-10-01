package moe.ouom.neriplayer.data.config

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class ConfigFileManagerConstructionTest {
    @Test
    fun generatingABackupNameDoesNotOpenCredentialStorage() {
        val context = mock(Context::class.java)
        val manager = ConfigFileManager(context)

        assertTrue(manager.generateBackupFileName().isNotBlank())
        verifyNoInteractions(context)
    }
}
