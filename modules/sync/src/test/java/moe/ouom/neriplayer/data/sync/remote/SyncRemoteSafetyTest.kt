package moe.ouom.neriplayer.data.sync.remote

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncRemoteSafetyTest {
    @Test
    fun `missing current file reads first available legacy file`() = runTest {
        val files = mutableListOf<String>()
        val result = SyncFallbackFileReader.read("current", listOf("legacy", "json"), { name ->
            files += name
            if (name == "legacy") Result.success("payload") else Result.failure(Missing())
        }, { it is Missing }).getOrThrow()!!
        assertEquals(listOf("current", "legacy"), files)
        assertEquals("legacy", result.fileName)
        assertEquals("payload", result.content)
    }

    @Test
    fun `missing all remote files is the initial upload case`() = runTest {
        val result = SyncFallbackFileReader.read<String>("current", listOf("old"), { Result.failure(Missing()) }, { it is Missing })
        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow())
    }

    @Test
    fun `non missing failure never reads stale fallback`() = runTest {
        for (failAt in listOf("current", "legacy")) {
            val files = mutableListOf<String>()
            val error = IOException("offline")
            val result = SyncFallbackFileReader.read<String>("current", listOf("legacy", "json"), { name ->
                files += name
                Result.failure(if (name == failAt) error else Missing())
            }, { it is Missing })
            assertSame(error, result.exceptionOrNull())
            assertEquals(if (failAt == "current") listOf("current") else listOf("current", "legacy"), files)
        }
    }

    @Test
    fun `corrupt current data aborts without reading valid legacy data`() = runTest {
        val files = mutableListOf<String>()
        val old = SyncDataSerializer.serialize(SyncData(deviceId = "stale"), false)
        val found = SyncFallbackFileReader.read("current", listOf("legacy"), { name ->
            files += name
            Result.success(if (name == "current") "invalid".toByteArray() else old)
        }, { it is Missing }).getOrThrow()!!
        val decoded = SyncRemoteSnapshotDecoder { it }.decode(found.content) { IOException("empty") }
        assertTrue(decoded.isFailure)
        assertEquals(listOf("current"), files)
    }

    @Test
    fun `empty and oversized content fail before sanitizer`() {
        var sanitized = false
        val decoder = SyncRemoteSnapshotDecoder { sanitized = true; it }
        val error = IOException("empty")
        assertSame(error, decoder.decode(ByteArray(0)) { error }.exceptionOrNull())
        val oversized = ByteArray(12 * 1024 * 1024 + 1)
        assertTrue(decoder.decode(oversized) { error }.isFailure)
        assertFalse(sanitized)
    }

    @Test
    fun `valid data reaches sanitizer and sanitizer errors stop decoding`() {
        val bytes = SyncDataSerializer.serialize(SyncData(deviceId = "remote"), false)
        val transformed = SyncRemoteSnapshotDecoder { it.copy(deviceId = "sanitized") }.decode(bytes) { IOException("empty") }
        assertEquals("sanitized", transformed.getOrThrow().deviceId)
        val error = IOException("invalid identity")
        assertSame(error, SyncRemoteSnapshotDecoder { throw error }.decode(bytes) { IOException("empty") }.exceptionOrNull())
    }

    private class Missing : IOException()
}
