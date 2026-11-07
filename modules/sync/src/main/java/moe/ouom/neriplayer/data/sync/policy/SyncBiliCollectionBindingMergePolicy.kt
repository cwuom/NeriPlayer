package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_ALBUM
import moe.ouom.neriplayer.data.model.sync.SyncBiliCollectionBinding

/** 单设备保留的绑定上限，避免异常数据把同步报文撑爆 */
private const val MAX_SYNC_BILI_COLLECTION_BINDINGS = 512

object SyncBiliCollectionBindingMergePolicy {
    fun sanitize(bindings: Iterable<SyncBiliCollectionBinding>): List<SyncBiliCollectionBinding> {
        val byKey = linkedMapOf<String, SyncBiliCollectionBinding>()
        bindings.forEach { binding ->
            val normalized = normalize(binding) ?: return@forEach
            byKey[normalized.targetKey] = byKey[normalized.targetKey]
                ?.let { existing -> newer(existing, normalized) }
                ?: normalized
        }
        return byKey.values
            .sortedWith(compareByDescending<SyncBiliCollectionBinding> { it.updatedAt }.thenBy { it.targetKey })
            .take(MAX_SYNC_BILI_COLLECTION_BINDINGS)
    }

    fun merge(
        local: Iterable<SyncBiliCollectionBinding>,
        remote: Iterable<SyncBiliCollectionBinding>
    ): List<SyncBiliCollectionBinding> = sanitize(local + remote)

    fun same(
        left: Iterable<SyncBiliCollectionBinding>,
        right: Iterable<SyncBiliCollectionBinding>
    ): Boolean = sanitize(left) == sanitize(right)

    private fun normalize(binding: SyncBiliCollectionBinding): SyncBiliCollectionBinding? {
        val targetKey = binding.targetKey.trim()
        if (targetKey.isEmpty()) return null
        if (binding.isDeleted) {
            return binding.copy(
                targetKey = targetKey,
                bvid = "",
                tracks = emptyList(),
                updatedAt = binding.updatedAt.coerceAtLeast(0L)
            )
        }
        val bvid = binding.bvid.trim()
        if (bvid.isEmpty()) return null
        val tracks = binding.tracks
            .filter { it.cid > 0L }
            .distinctBy { it.cid }
            .sortedBy { it.page }
        if (tracks.isEmpty()) return null
        return binding.copy(
            targetKey = targetKey,
            targetType = binding.targetType.trim().ifBlank { BILI_BINDING_TARGET_ALBUM },
            targetName = binding.targetName.trim(),
            targetSubtitle = binding.targetSubtitle.trim(),
            bvid = bvid,
            videoTitle = binding.videoTitle.trim(),
            tracks = tracks,
            updatedAt = binding.updatedAt.coerceAtLeast(0L)
        )
    }

    private fun newer(
        left: SyncBiliCollectionBinding,
        right: SyncBiliCollectionBinding
    ): SyncBiliCollectionBinding {
        if (left.updatedAt != right.updatedAt) {
            return if (left.updatedAt > right.updatedAt) left else right
        }
        // 时间戳相同：删除优先，避免两端来回复活
        if (left.isDeleted != right.isDeleted) {
            return if (left.isDeleted) left else right
        }
        // 时间戳与删除标记都相同：按内容稳定决胜。两端各自合并必须得到同一结果，
        // 否则本机保留自己的、对方保留它的，归档会来回翻转
        return if (stableOrderKey(left) >= stableOrderKey(right)) left else right
    }

    private fun stableOrderKey(binding: SyncBiliCollectionBinding): String = buildString {
        append(binding.bvid).append('|')
        append(binding.targetName).append('|')
        append(binding.videoTitle).append('|')
        binding.tracks.sortedBy { it.cid }.forEach { track ->
            append(track.cid).append(':').append(track.part).append(';')
        }
    }
}
