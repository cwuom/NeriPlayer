package moe.ouom.neriplayer.core.player.service.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarControllerTrustTest {
    @Test
    fun `a forged package is rejected even with an otherwise trusted uid`() {
        assertFalse(trusted(belongs = false, own = true, system = true, controller = true, google = true, systemGoogle = true))
    }

    @Test
    fun `own app and system clients require actual package ownership`() {
        assertTrue(trusted(own = true))
        assertTrue(trusted(system = true))
        assertFalse(trusted(belongs = false, own = true))
        assertFalse(trusted(belongs = false, system = true))
    }

    @Test
    fun `system authorized media controllers are accepted`() {
        assertTrue(trusted(controller = true))
        assertFalse(trusted())
    }

    @Test
    fun `auto requires a matching system Google services signing identity`() {
        assertTrue(trusted(google = true, systemGoogle = true))
        assertFalse(trusted(google = true))
        assertFalse(trusted(systemGoogle = true))
    }

    @Test
    fun `two spoofed user installed Google packages cannot authorize each other`() {
        assertFalse(trusted(google = true, systemGoogle = false))
        assertFalse(trusted(packageName = "fake.car", google = true, systemGoogle = true))
    }

    private fun trusted(
        packageName: String = ANDROID_AUTO_PACKAGE,
        belongs: Boolean = true,
        own: Boolean = false,
        system: Boolean = false,
        controller: Boolean = false,
        google: Boolean = false,
        systemGoogle: Boolean = false,
    ) = isTrustedCarClient(packageName, belongs, own, system, controller, google, systemGoogle)
}
