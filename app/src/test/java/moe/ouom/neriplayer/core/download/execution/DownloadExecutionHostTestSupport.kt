package moe.ouom.neriplayer.core.download.execution

import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost
import moe.ouom.neriplayer.core.download.execution.host.tryAcquireHostAdmission
import moe.ouom.neriplayer.core.download.execution.host.tryAcquireHostAdmissionSuspending
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionOperationJournal
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.ListenableWorker
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import moe.ouom.neriplayer.core.download.observability.DownloadPumpSelectionTrace
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

abstract class DownloadExecutionHostTestSupport {





















































































    internal val testJournal = InMemoryDownloadExecutionOperationJournal()

    internal fun mockContext(activeClearFence: Boolean = false): Context {
        return mock(Context::class.java).also { context ->
            `when`(context.applicationContext).thenReturn(context)
            val preferences = mock(SharedPreferences::class.java)
            val editor = mock(SharedPreferences.Editor::class.java, Answers.RETURNS_SELF)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
            `when`(preferences.getBoolean(anyString(), anyBoolean())).thenReturn(activeClearFence)
            `when`(preferences.edit()).thenReturn(editor)
        }
    }

    internal fun statefulMockContext(): Context {
        return mock(Context::class.java).also { context ->
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.filesDir).thenReturn(
                File(
                    System.getProperty("java.io.tmpdir") ?: ".",
                    "neriplayer-download-host-test-${System.nanoTime()}"
                )
            )
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(
                StatefulSharedPreferences()
            )
        }
    }

    internal fun sampleSong(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 1234L,
            coverUrl = null
        )
    }

    internal fun locateProjectFile(path: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }

    internal fun methodBody(source: String, methodName: String): String {
        val signatureStart = sequenceOf(
            "override suspend fun $methodName(",
            "internal suspend fun DefaultDownloadExecutionHost.$methodName(",
            "internal fun DefaultDownloadExecutionHost.$methodName(",
            "private suspend fun $methodName(",
            "private fun $methodName(",
            "suspend fun $methodName("
        ).map(source::indexOf).firstOrNull { it >= 0 }
            ?: error("method not found: $methodName")
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "method body not found: $methodName" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unterminated method body: $methodName")
    }

    internal class StatefulSharedPreferences : SharedPreferences {
        internal val values = linkedMapOf<String, Any?>()

        override fun contains(key: String): Boolean = synchronized(values) {
            values.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun getAll(): MutableMap<String, *> = synchronized(values) {
            values.toMutableMap()
        }

        override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(values) {
            values[key] as? Boolean ?: defValue
        }

        override fun getFloat(key: String, defValue: Float): Float = synchronized(values) {
            values[key] as? Float ?: defValue
        }

        override fun getInt(key: String, defValue: Int): Int = synchronized(values) {
            values[key] as? Int ?: defValue
        }

        override fun getLong(key: String, defValue: Long): Long = synchronized(values) {
            values[key] as? Long ?: defValue
        }

        override fun getString(key: String, defValue: String?): String? = synchronized(values) {
            values[key] as? String ?: defValue
        }

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?
        ): MutableSet<String>? = synchronized(values) {
            when (val value = values[key]) {
                is Set<*> -> value.filterIsInstance<String>().toMutableSet()
                else -> defValues?.toMutableSet()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        internal inner class Editor : SharedPreferences.Editor {
            internal val updates = linkedMapOf<String, Any?>()
            internal val removals = linkedSetOf<String>()
            internal var clearAll = false

            override fun apply() {
                commit()
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
                updates.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clearAll) values.clear()
                    removals.forEach(values::remove)
                    values.putAll(updates)
                }
                return true
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                put(key, value)

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                put(key, value)

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                put(key, value)

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                put(key, value)

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                put(key, value)

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = put(key, values?.toMutableSet())

            override fun remove(key: String): SharedPreferences.Editor = apply {
                removals += key
                updates.remove(key)
            }

            internal fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
                updates[key] = value
                removals.remove(key)
            }
        }
    }

    internal class CapacityBoundDownloadExecutionJournal(
        internal val delegate: InMemoryDownloadExecutionOperationJournal,
        internal val admissionCapacity: Int,
        releaseFailuresBeforeSuccess: Int = 0
    ) : DownloadExecutionOperationJournal by delegate {
        internal val lock = Any()
        internal val admittedOperationIds = linkedSetOf<String>()
        internal var remainingReleaseFailures = releaseFailuresBeforeSuccess

        override fun tryAcquireHostAdmission(
            context: Context,
            operationId: String,
            capacity: Int
        ): Boolean = synchronized(lock) {
            delegate.hostAdmissionAcquireCount++
            delegate.lastHostAdmissionCapacity = capacity
            if (!delegate.hostAdmissionAllowed || capacity <= 0) return@synchronized false
            if (operationId in admittedOperationIds) return@synchronized true
            if (admittedOperationIds.size >= admissionCapacity) return@synchronized false
            admittedOperationIds += operationId
            true
        }

        override suspend fun tryAcquireHostAdmissionSuspending(
            context: Context,
            operationId: String,
            capacity: Int
        ): Boolean = tryAcquireHostAdmission(context, operationId, capacity)

        override fun releaseHostAdmission(context: Context, operationId: String) {
            synchronized(lock) {
                if (remainingReleaseFailures > 0) {
                    remainingReleaseFailures--
                    throw IllegalStateException("synthetic host admission release failure")
                }
                if (admittedOperationIds.remove(operationId)) {
                    delegate.hostAdmissionReleaseCount++
                }
            }
        }

        override suspend fun releaseHostAdmissionSuspending(
            context: Context,
            operationId: String
        ) {
            releaseHostAdmission(context, operationId)
        }
    }


}
