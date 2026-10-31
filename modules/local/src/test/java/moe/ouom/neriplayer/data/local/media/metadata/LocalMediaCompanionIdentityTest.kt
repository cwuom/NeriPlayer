package moe.ouom.neriplayer.data.local.media.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaCompanionIdentityTest {

    @Test
    fun `same inode on different devices does not prove the same file`() {
        assertFalse(sameCompanionFileIdentity("65029:106579", "82:106579"))
        assertTrue(sameCompanionFileIdentity("82:106579", "82:106579"))
    }

    @Test
    fun `replaced files with a new inode are still rejected`() {
        assertFalse(sameCompanionFileIdentity("82:106579", "82:106580"))
        assertFalse(sameCompanionFileIdentity("65029:106579", "82:106580"))
    }

    @Test
    fun `missing or malformed identities only match themselves`() {
        assertTrue(sameCompanionFileIdentity(null, null))
        assertFalse(sameCompanionFileIdentity(null, "82:1"))
        assertFalse(sameCompanionFileIdentity("82:1", null))
        assertFalse(sameCompanionFileIdentity("malformed", "other"))
        assertFalse(sameCompanionFileIdentity("82:", "65029:"))
    }
}
