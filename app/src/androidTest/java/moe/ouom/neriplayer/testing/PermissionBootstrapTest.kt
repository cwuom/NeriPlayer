package moe.ouom.neriplayer.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionBootstrapTest {

    @Test
    fun grantPlaybackRuntimePermissions() {
        grantRuntimePermissions(*playbackRuntimePermissions())
    }
}
