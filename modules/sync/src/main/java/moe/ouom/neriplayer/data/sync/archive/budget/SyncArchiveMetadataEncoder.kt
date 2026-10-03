@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive.budget

import java.io.IOException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule

internal class SyncArchiveMetadataEncoder(
    private val availableBytes: Long,
    private val availableObjects: Long,
    private val checkActive: () -> Unit
) : AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    var objects = 0L
        private set
    private var stringBytes = 0L

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
            if (objects == availableObjects) throw IOException("Sync retained objects exceed safe capacity")
            if (objects % 1024L == 0L) checkActive()
            objects++
        }
        return this
    }

    // 默认嵌套对象也会由解码构造，不能仅统计报文中非默认字段
    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

    override fun encodeValue(value: Any) = Unit
    override fun encodeNull() = Unit

    override fun encodeString(value: String) {
        var index = 0
        while (index < value.length) {
            if (index % 4096 == 0) checkActive()
            val width = utf8Width(value, index)
            if (width.toLong() > availableBytes - stringBytes) throw IOException("Sync retained payload exceeds safe capacity")
            stringBytes += width
            index += if (width == 4) 2 else 1
        }
    }

    private fun utf8Width(value: String, index: Int): Int {
        val char = value[index]
        if (char.code <= 0x7f) return 1
        if (char.code <= 0x7ff) return 2
        if (char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) return 4
        // ProtoBuf 使用 JVM UTF-8 编码，无配对代理位按单字节替代
        return if (char.isSurrogate()) 1 else 3
    }
}
