@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive.budget

import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf

internal data class SyncArchiveMetadataLimits(
    val maxPayloadBytes: Long = 32L * 1024 * 1024,
    val maxObjects: Long = 131_072L
) {
    init {
        require(maxPayloadBytes > 0L && maxObjects > 0L) { "Invalid sync metadata limits" }
    }
}

internal class SyncArchiveMetadataBudget(private val limits: SyncArchiveMetadataLimits) {
    private var payloadBytes = 0L
    private var objects = 0L

    fun isolated(): SyncArchiveMetadataBudget = SyncArchiveMetadataBudget(limits)

    fun beginRecord(size: Int) {
        reserveBytes(size.toLong())
        addObject()
    }

    fun addObject() {
        if (objects == limits.maxObjects) throw IOException("Sync retained objects exceed safe capacity")
        objects++
    }

    fun inspect(kind: Int, payload: ByteArray, checkActive: () -> Unit) {
        SyncArchiveMetadataWire.inspect(kind, payload, this, checkActive)
    }

    fun <T> encode(serializer: KSerializer<T>, value: T, checkActive: () -> Unit): ByteArray {
        val preflight = SyncArchiveMetadataEncoder(limits.maxPayloadBytes - payloadBytes,
            limits.maxObjects - objects, checkActive)
        serializer.serialize(preflight, value)
        val bytes = ProtoBuf.encodeToByteArray(serializer, value)
        reserveBytes(bytes.size.toLong())
        objects += preflight.objects
        return bytes
    }

    private fun reserveBytes(size: Long) {
        if (size > limits.maxPayloadBytes - payloadBytes) throw IOException("Sync retained payload exceeds safe capacity")
        payloadBytes += size
    }
}
