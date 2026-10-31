package moe.ouom.neriplayer.platform.bilibili.binding

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.BiliCollectionBindingDao
import moe.ouom.neriplayer.data.local.database.entity.BiliCollectionBindingEntity
import moe.ouom.neriplayer.data.model.bilibili.binding.BILI_BINDING_TARGET_SONG
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBinding
import moe.ouom.neriplayer.data.model.bilibili.binding.BiliCollectionBindingTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliCollectionBindingRepositoryTest {
    private val track = BiliCollectionBindingTrack(
        songId = 1L,
        songName = "骨中月",
        cid = 10L,
        page = 1,
        part = "骨中月",
        durationSec = 236
    )

    private val active = BiliCollectionBinding(
        targetKey = "netease:song:1",
        targetType = BILI_BINDING_TARGET_SONG,
        targetId = 1L,
        targetName = "骨中月",
        targetSubtitle = "镜予歌",
        bvid = "BV1xx411c7mD",
        avid = 42L,
        videoTitle = "骨中月合集",
        tracks = listOf(track),
        updatedAt = 10L
    )

    @Test
    fun `unbind keeps a tombstone that only the sync snapshot sees`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        val repository = BiliCollectionBindingRepository(dao)
        repository.save(active)

        repository.remove(active.targetKey)

        assertTrue(repository.all().isEmpty())
        assertNull(repository.get(active.targetKey))
        val tombstone = repository.snapshot().single()
        assertTrue(tombstone.isDeleted)
        assertEquals(active.targetKey, tombstone.targetKey)
        assertEquals("", tombstone.bvid)
        assertTrue(tombstone.tracks.isEmpty())
        assertTrue(tombstone.updatedAt > active.updatedAt)
    }

    @Test
    fun `tombstone applied from sync stays stored so the old record cannot come back`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        val repository = BiliCollectionBindingRepository(dao)
        repository.save(active)
        repository.remove(active.targetKey)

        repository.replaceAll(repository.snapshot(), 0L)

        assertTrue(repository.all().isEmpty())
        assertTrue(repository.snapshot().single().isDeleted)
        assertTrue(dao.rows.values.single().bvid.isEmpty())
    }

    @Test
    fun `stale sync snapshot does not overwrite a newer local change`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        var localMutationVersion = 7L
        val repository = BiliCollectionBindingRepository(dao, { localMutationVersion })
        repository.save(active)
        localMutationVersion = 9L

        val applied = repository.replaceAll(emptyList(), expectedMutationVersion = 7L)

        assertFalse(applied)
        assertEquals(active, repository.get(active.targetKey))
    }

    @Test
    fun `tombstone outruns a record stamped by a device whose clock is ahead`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        val repository = BiliCollectionBindingRepository(dao)
        val future = active.copy(updatedAt = System.currentTimeMillis() + 86_400_000L)
        repository.save(future)

        repository.remove(future.targetKey)

        assertTrue(repository.snapshot().single().updatedAt > future.updatedAt)
    }

    @Test
    fun `local writes advance the sync mutation version before releasing the lock`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        var mutations = 0
        val repository = BiliCollectionBindingRepository(dao, markMutation = { mutations++ })
        repository.save(active)
        repository.remove(active.targetKey)

        assertEquals(2, mutations)
    }

    @Test
    fun `binding the song again replaces the tombstone`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        val repository = BiliCollectionBindingRepository(dao)
        repository.save(active)
        repository.remove(active.targetKey)

        val rebinding = active.copy(updatedAt = 20L)
        repository.save(rebinding)

        assertEquals(rebinding, repository.get(active.targetKey))
        assertFalse(repository.snapshot().single().isDeleted)
    }

    @Test
    fun `binding reloads from disk without honouring stale tombstones`() = runTest {
        val dao = FakeBiliCollectionBindingDao()
        BiliCollectionBindingRepository(dao).let { repository ->
            repository.save(active)
            repository.remove(active.targetKey)
        }

        val reopened = BiliCollectionBindingRepository(dao)

        assertNull(reopened.get(active.targetKey))
        assertTrue(reopened.snapshot().single().isDeleted)
    }

    private class FakeBiliCollectionBindingDao : BiliCollectionBindingDao {
        val rows = linkedMapOf<String, BiliCollectionBindingEntity>()

        override suspend fun getAll(): List<BiliCollectionBindingEntity> =
            rows.values.sortedByDescending { it.updatedAt }

        override suspend fun getByKey(targetKey: String): BiliCollectionBindingEntity? = rows[targetKey]

        override suspend fun upsert(binding: BiliCollectionBindingEntity) {
            rows[binding.targetKey] = binding
        }

        override suspend fun upsertAll(bindings: List<BiliCollectionBindingEntity>) {
            bindings.forEach { binding -> rows[binding.targetKey] = binding }
        }

        override suspend fun deleteByKey(targetKey: String) {
            rows.remove(targetKey)
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
