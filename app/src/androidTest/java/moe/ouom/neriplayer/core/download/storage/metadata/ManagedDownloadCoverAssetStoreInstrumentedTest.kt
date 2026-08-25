package moe.ouom.neriplayer.core.download.storage.metadata

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import moe.ouom.neriplayer.core.download.storage.ROOT_DIR_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadCoverAssetStoreInstrumentedTest {
    @Test
    fun externalCoverWithoutPreferredNameIsMaterializedOnceWithShortHash() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testDirectory = File(
            baseContext.cacheDir,
            "cover-default-materialize-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val externalMusicDirectory = File(testDirectory, "external-music").apply { mkdirs() }
        val filesDirectory = File(testDirectory, "files").apply { mkdirs() }
        val source = File(testDirectory, "remote-cover.jpg").apply {
            writeBytes("cover-payload".toByteArray())
        }
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getExternalFilesDir(type: String?): File = externalMusicDirectory

            override fun getFilesDir(): File = filesDirectory
        }
        val expectedName = "remote-cover-" +
            ManagedDownloadCoverAssetStore.sha256(source.readBytes()).take(8) + ".jpg"
        ManagedDownloadStorage.updateCustomDirectoryUri(null)

        try {
            val materialized = ManagedDownloadCoverAssetStore.materialize(
                context = context,
                reference = source.absolutePath,
                preferredFileName = null
            )
            val covers = File(
                externalMusicDirectory,
                "$ROOT_DIR_NAME/$COVER_SUBDIRECTORY"
            ).listFiles().orEmpty().filter { file ->
                file.isFile && file.name != ".nomedia"
            }

            assertEquals(expectedName, materialized?.fileName)
            assertTrue(materialized?.reference != source.absolutePath)
            assertEquals(listOf(expectedName), covers.map(File::getName))
            assertTrue(covers.single().readBytes().contentEquals(source.readBytes()))
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun ordinaryMaterializationKeepsExactlyOneShortNamedCover() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testDirectory = File(
            baseContext.cacheDir,
            "cover-materialize-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val externalMusicDirectory = File(testDirectory, "external-music").apply { mkdirs() }
        val filesDirectory = File(testDirectory, "files").apply { mkdirs() }
        val source = File(testDirectory, "remote-cover.jpg").apply {
            writeBytes("cover-payload".toByteArray())
        }
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getExternalFilesDir(type: String?): File = externalMusicDirectory

            override fun getFilesDir(): File = filesDirectory
        }
        val shortName = "Artist - Song-12345678.jpg"
        ManagedDownloadStorage.updateCustomDirectoryUri(null)

        try {
            val first = ManagedDownloadCoverAssetStore.materialize(
                context = context,
                reference = source.absolutePath,
                preferredFileName = shortName
            )
            val fingerprint = ManagedDownloadCoverAssetStore.materialize(
                context = context,
                reference = first?.reference,
                preferredFileName = null
            )
            val covers = File(
                externalMusicDirectory,
                "$ROOT_DIR_NAME/$COVER_SUBDIRECTORY"
            ).listFiles().orEmpty().filter { file ->
                file.isFile && file.name != ".nomedia"
            }

            assertEquals(shortName, first?.fileName)
            assertEquals(first?.reference, fingerprint?.reference)
            assertEquals(first?.assetHash, fingerprint?.assetHash)
            assertEquals(listOf(shortName), covers.map(File::getName))
            assertTrue(covers.single().readBytes().contentEquals(source.readBytes()))
            assertFalse(
                covers.any { file ->
                    file.nameWithoutExtension.matches(Regex("[0-9a-fA-F]{64}"))
                }
            )
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun concurrentSameNameCoverCommitLeavesOneCompleteManagedFile() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val testDirectory = File(
            baseContext.cacheDir,
            "cover-concurrent-commit-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val externalMusicDirectory = File(testDirectory, "external-music").apply { mkdirs() }
        val filesDirectory = File(testDirectory, "files").apply { mkdirs() }
        val context = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getExternalFilesDir(type: String?): File = externalMusicDirectory

            override fun getFilesDir(): File = filesDirectory
        }
        val firstPayload = ByteArray(512 * 1024) { 1 }
        val secondPayload = ByteArray(512 * 1024) { 2 }
        val fileName = "Artist - Song-12345678.jpg"
        ManagedDownloadStorage.updateCustomDirectoryUri(null)

        try {
            val start = CompletableDeferred<Unit>()
            coroutineScope {
                listOf(firstPayload, secondPayload).map { payload ->
                    async(Dispatchers.IO) {
                        start.await()
                        ManagedDownloadStorage.commitCoverBytes(
                            context = context,
                            bytes = payload,
                            fileName = fileName,
                            mimeType = "image/jpeg"
                        )
                    }
                }.also { start.complete(Unit) }.awaitAll()
            }
            val coverDirectory = File(
                externalMusicDirectory,
                "$ROOT_DIR_NAME/$COVER_SUBDIRECTORY"
            )
            val covers = coverDirectory.listFiles().orEmpty().filter { file ->
                file.isFile && file.name != ".nomedia"
            }

            assertEquals(listOf(fileName), covers.map(File::getName))
            val committedBytes = covers.single().readBytes()
            assertTrue(
                committedBytes.contentEquals(firstPayload) ||
                    committedBytes.contentEquals(secondPayload)
            )
            assertTrue(coverDirectory.listFiles().orEmpty().none { file ->
                file.name.endsWith(".pending")
            })
        } finally {
            testDirectory.deleteRecursively()
        }
    }
}
