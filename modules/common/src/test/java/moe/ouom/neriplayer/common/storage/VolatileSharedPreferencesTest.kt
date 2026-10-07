package moe.ouom.neriplayer.common.storage

import android.content.SharedPreferences
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VolatileSharedPreferencesTest {
    @Test
    fun `values round trip with their types and defaults`() {
        val preferences = VolatileSharedPreferences.shared(uniqueName())
        preferences.edit()
            .putString("string", "value")
            .putStringSet("set", mutableSetOf("a", "b"))
            .putInt("int", 7)
            .putLong("long", 8L)
            .putFloat("float", 1.5f)
            .putBoolean("boolean", true)
            .commit()

        assertEquals("value", preferences.getString("string", null))
        assertEquals(setOf("a", "b"), preferences.getStringSet("set", null))
        assertEquals(7, preferences.getInt("int", 0))
        assertEquals(8L, preferences.getLong("long", 0L))
        assertEquals(1.5f, preferences.getFloat("float", 0f))
        assertTrue(preferences.getBoolean("boolean", false))
        assertEquals("fallback", preferences.getString("missing", "fallback"))
        assertEquals(3, preferences.getInt("string", 3))
        assertEquals(6, preferences.all.size)
    }

    @Test
    fun `remove null puts and clear follow editor semantics`() {
        val preferences = VolatileSharedPreferences.shared(uniqueName())
        preferences.edit().putString("a", "1").putString("b", "2").putString("c", "3").apply()

        preferences.edit().remove("a").putString("b", null).apply()
        assertFalse(preferences.contains("a"))
        assertFalse(preferences.contains("b"))
        assertTrue(preferences.contains("c"))

        preferences.edit().clear().putString("d", "4").apply()
        assertNull(preferences.getString("c", null))
        assertEquals("4", preferences.getString("d", null))
    }

    @Test
    fun `stored sets cannot be mutated through returned copies`() {
        val preferences = VolatileSharedPreferences.shared(uniqueName())
        val source = mutableSetOf("a")
        preferences.edit().putStringSet("set", source).apply()
        source += "b"
        preferences.getStringSet("set", null)!!.add("c")
        assertEquals(setOf("a"), preferences.getStringSet("set", null))
    }

    @Test
    fun `listeners see every changed key until unregistered`() {
        val preferences = VolatileSharedPreferences.shared(uniqueName())
        val changed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { source, key ->
            assertSame(preferences, source)
            changed += key
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        preferences.edit().putString("a", "1").putInt("b", 2).apply()
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        preferences.edit().putString("c", "3").apply()
        assertEquals(listOf("a", "b"), changed)
    }

    @Test
    fun `instances are shared per name inside the process`() {
        val name = uniqueName()
        assertNull(VolatileSharedPreferences.existing(name))
        val first = VolatileSharedPreferences.shared(name)
        first.edit().putString("token", "kept").apply()
        assertSame(first, VolatileSharedPreferences.shared(name))
        assertEquals("kept", VolatileSharedPreferences.existing(name)?.getString("token", null))
    }

    private fun uniqueName() = "volatile-test-${UUID.randomUUID()}"
}
