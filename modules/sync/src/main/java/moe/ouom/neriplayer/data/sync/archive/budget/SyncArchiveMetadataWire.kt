@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.archive.budget

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.model.sync.*

internal object SyncArchiveMetadataWire {
    private val descriptors = listOf(
        SyncPlaylist.serializer().descriptor, SyncSong.serializer().descriptor,
        SyncFavoritePlaylist.serializer().descriptor, SyncSong.serializer().descriptor,
        SyncRecentPlay.serializer().descriptor, SyncLogEntry.serializer().descriptor,
        SyncRecentPlayDeletion.serializer().descriptor, SyncTrackStat.serializer().descriptor,
        SyncPlaybackStatBucket.serializer().descriptor, SyncPlaylistSongDeletion.serializer().descriptor,
        SyncPlaylistUsageStat.serializer().descriptor, SyncLocalPlaylistPlaybackStat.serializer().descriptor,
        SyncLocalPlaylistPlaybackBucket.serializer().descriptor, SyncBiliVideoSkipRule.serializer().descriptor,
        SyncSong.serializer().descriptor, SyncPlaylistUsageDeletion.serializer().descriptor
    )
    private val children = ConcurrentHashMap<String, Map<Int, SerialDescriptor>>()

    fun inspect(kind: Int, payload: ByteArray, budget: SyncArchiveMetadataBudget, checkActive: () -> Unit) {
        Scanner(payload, budget, checkActive).root(kind)
    }

    private fun nested(descriptor: SerialDescriptor): Map<Int, SerialDescriptor> = children.getOrPut(descriptor.serialName) {
        buildMap {
            for (index in 0 until descriptor.elementsCount) {
                val field = descriptor.getElementDescriptor(index)
                val child = if (field.kind == StructureKind.LIST) field.getElementDescriptor(0) else field
                if (child.kind == StructureKind.CLASS || child.kind == StructureKind.OBJECT) {
                    val number = descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().firstOrNull()?.number ?: index + 1
                    put(number, child)
                }
            }
        }
    }

    private class Scanner(private val payload: ByteArray, private val budget: SyncArchiveMetadataBudget,
        private val checkActive: () -> Unit) {
        private var visited = 0
        private var songPresent = false

        fun root(kind: Int) {
            scan(descriptors[kind - 1], 0, payload.size, kind)
            if (kind == 5 && !songPresent) budget.addObject()
        }

        private fun scan(descriptor: SerialDescriptor, start: Int, end: Int, rootKind: Int = 0) {
            val cursor = SyncArchiveWireCursor(payload, end, start)
            val fields = nested(descriptor)
            while (cursor.next()) {
                validateMembershipHeader(rootKind, cursor.tag)
                if (cursor.wire != 2) continue
                val child = fields[cursor.tag] ?: continue
                if (rootKind == 5 && cursor.tag == 2) songPresent = true
                addChild()
                scan(child, cursor.bodyStart, cursor.bodyEnd)
            }
        }

        private fun addChild() {
            if (visited++ % 1024 == 0) checkActive()
            budget.addObject()
        }

        private fun validateMembershipHeader(kind: Int, tag: Int) {
            if ((kind == 1 && tag == 3) || (kind == 3 && tag == 6)) throw IOException("Sync playlist header embeds songs")
        }
    }
}
