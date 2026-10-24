package moe.ouom.neriplayer.testing

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriPlayerInstrumentationArgumentsTest {
    @Test
    fun additionalExclusionPreservesExistingFiltersAndOriginalArguments() {
        val original = Bundle().apply {
            putString("notClass", "example.ExternalLyricsTest")
            putString("neriplayerAdditionalExcludedClass", "example.RotationTest")
            putString("numShards", "2")
            putString("shardIndex", "1")
            putString("notAnnotation", "example.ManualTest")
        }

        val result = withAdditionalExcludedTestClass(original)

        assertEquals("example.ExternalLyricsTest,example.RotationTest", result.getString("notClass"))
        assertEquals("2", result.getString("numShards"))
        assertEquals("1", result.getString("shardIndex"))
        assertEquals("example.ManualTest", result.getString("notAnnotation"))
        assertFalse(result.containsKey("neriplayerAdditionalExcludedClass"))
        assertEquals("example.ExternalLyricsTest", original.getString("notClass"))
        assertEquals("example.RotationTest", original.getString("neriplayerAdditionalExcludedClass"))
    }

    @Test
    fun additionalExclusionWorksWithoutExistingExclusions() {
        val result = withAdditionalExcludedTestClass(Bundle().apply {
            putString("neriplayerAdditionalExcludedClass", "example.RotationTest")
        })

        assertEquals("example.RotationTest", result.getString("notClass"))
    }

    @Test
    fun duplicateExclusionIsNotAddedAgain() {
        val result = withAdditionalExcludedTestClass(Bundle().apply {
            putString("notClass", "example.ExternalLyricsTest,example.RotationTest")
            putString("neriplayerAdditionalExcludedClass", "example.RotationTest")
        })

        assertEquals("example.ExternalLyricsTest,example.RotationTest", result.getString("notClass"))
        assertFalse(result.containsKey("neriplayerAdditionalExcludedClass"))
    }

    @Test
    fun absentOrBlankAdditionalExclusionPreservesDedicatedHostSelection() {
        assertTrue(withAdditionalExcludedTestClass(null).isEmpty)
        val original = Bundle().apply { putString("class", "example.RotationTest") }
        assertEquals("example.RotationTest", withAdditionalExcludedTestClass(original).getString("class"))
        original.putString("neriplayerAdditionalExcludedClass", " ")

        val result = withAdditionalExcludedTestClass(original)

        assertEquals("example.RotationTest", result.getString("class"))
        assertFalse(result.containsKey("notClass"))
        assertFalse(result.containsKey("neriplayerAdditionalExcludedClass"))
    }
}
