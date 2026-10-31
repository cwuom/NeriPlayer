package moe.ouom.neriplayer.data.local.media.metadata

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.IdentityHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy

internal fun <T> withMetadataRecoveryDescriptors(block: suspend MetadataRecoveryDescriptors.() -> T): T =
    runBlocking(Dispatchers.IO) { MetadataRecoveryDescriptors().use { it.run(block) } }

internal class MetadataRecoveryDescriptors : Closeable {
    var beforeTruncate: (FileDescriptor) -> Unit = {}
    var beforePathReplace: (Path, Path) -> Unit = { _, _ -> }

    private data class DescriptorView(
        val heldFile: File,
        val owner: ParcelFileDescriptor,
        val accessMode: Int,
        var device: Long,
        var inode: Long
    )

    private val views = IdentityHashMap<FileDescriptor, DescriptorView>()
    private val channels = IdentityHashMap<FileDescriptor, FileChannel>()
    private val descriptorNumbers = mutableMapOf<Int, DescriptorView>()
    private val borrowedStreams = mutableListOf<Closeable>()
    private val heldPaths = mutableListOf<Path>()

    fun track(
        descriptor: ParcelFileDescriptor,
        file: File,
        deviceOffset: Long = 0L,
        forcedInode: Long? = null
    ): ParcelFileDescriptor {
        val device = (Files.getAttribute(file.toPath(), "unix:dev") as Number).toLong() + deviceOffset
        val inode = forcedInode ?: (Files.getAttribute(file.toPath(), "unix:ino") as Number).toLong()
        views[descriptor.fileDescriptor]?.let { view ->
            view.device = device
            view.inode = inode
            return descriptor
        }
        return wrap(descriptor, file, ParcelFileDescriptor.MODE_READ_WRITE, device, inode)
    }

    suspend fun <T> run(block: suspend MetadataRecoveryDescriptors.() -> T): T {
        mockStatic(ParcelFileDescriptor::class.java, CALLS_REAL_METHODS).use { descriptors ->
            descriptors.`when`<ParcelFileDescriptor> {
                ParcelFileDescriptor.open(any(File::class.java), anyInt())
            }.thenAnswer { call ->
                val file = call.getArgument<File>(0)
                val mode = call.getArgument<Int>(1)
                if (!file.exists() && mode and ParcelFileDescriptor.MODE_CREATE == 0) {
                    throw FileNotFoundException(file.absolutePath)
                }
                val owner = call.callRealMethod() as ParcelFileDescriptor
                wrap(
                    owner, file, mode and ParcelFileDescriptor.MODE_READ_WRITE,
                    (Files.getAttribute(file.toPath(), "unix:dev") as Number).toLong(),
                    (Files.getAttribute(file.toPath(), "unix:ino") as Number).toLong()
                )
            }
            // Robolectric 的 fstat 丢失身份，dup 会重开原路径；桥接只补这些宿主缺口
            mockStatic(Os::class.java, CALLS_REAL_METHODS).use { descriptorOs ->
                descriptorOs.`when`<StructStat> { Os.fstat(any()) }.thenAnswer { call ->
                    val view = views.getValue(call.getArgument(0))
                    val file = view.heldFile
                    StructStat(
                        view.device, view.inode,
                        (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt(),
                        1, 0, 0, 0, file.length(), 0, file.lastModified() / 1_000, 0, 4_096, 0
                    )
                }
                descriptorOs.`when`<Long> { Os.lseek(any(), anyLong(), eq(OsConstants.SEEK_SET)) }.thenAnswer { call ->
                    channel(call.getArgument(0)).position(call.getArgument<Long>(1)).position()
                }
                descriptorOs.`when`<Unit> { Os.ftruncate(any(), anyLong()) }.thenAnswer { call ->
                    val descriptor = call.getArgument<FileDescriptor>(0)
                    beforeTruncate(descriptor)
                    FileOutputStream(descriptor).also { borrowedStreams += it }
                        .channel.truncate(call.getArgument(1))
                    null
                }
                descriptorOs.`when`<Unit> { Os.fsync(any()) }.thenAnswer { call ->
                    call.getArgument<FileDescriptor>(0).sync()
                    null
                }
                mockStatic(Files::class.java, CALLS_REAL_METHODS).use { paths ->
                    paths.`when`<Path> { Files.setLastModifiedTime(any(Path::class.java), any(FileTime::class.java)) }
                        .thenAnswer { call ->
                            val path = call.getArgument<Path>(0)
                            val number = path.toString().removePrefix("/proc/self/fd/").toIntOrNull()
                            val view = number?.let(descriptorNumbers::get)
                            if (path.toString().startsWith("/proc/self/fd/") && view != null) {
                                Files.setLastModifiedTime(view.heldFile.toPath(), call.getArgument(1))
                                path
                            } else call.callRealMethod()
                        }
                    paths.`when`<Path> {
                        Files.move(any(Path::class.java), any(Path::class.java),
                            eq(StandardCopyOption.REPLACE_EXISTING), eq(StandardCopyOption.ATOMIC_MOVE))
                    }.thenAnswer { call ->
                        beforePathReplace(call.getArgument(0), call.getArgument(1))
                        call.callRealMethod()
                    }
                    return block()
                }
            }
        }
    }

    private fun wrap(
        owner: ParcelFileDescriptor,
        file: File,
        accessMode: Int,
        device: Long,
        inode: Long
    ): ParcelFileDescriptor {
        val heldPath = Files.createTempFile("metadata-held-inode-", ".tmp")
        Files.delete(heldPath)
        Files.createLink(heldPath, file.toPath())
        heldPaths.add(heldPath)
        val descriptor = owner.fileDescriptor
        val number = owner.fd
        val view = DescriptorView(heldPath.toFile(), owner, accessMode, device, inode)
        views[descriptor] = view
        descriptorNumbers[number] = view
        return spy(owner).also { wrapped ->
            // spy 的 shadow 不拥有 RandomAccessFile，所有生命周期操作仍委托真实 owner
            doReturn(descriptor).`when`(wrapped).fileDescriptor
            doReturn(number).`when`(wrapped).fd
            doAnswer { owner.close(); null }.`when`(wrapped).close()
            doAnswer {
                // 宿主重开只复制初始偏移，真实 dup 的共享偏移由设备测试验证
                val duplicate = ParcelFileDescriptor.open(view.heldFile, view.accessMode)
                views.getValue(duplicate.fileDescriptor).also {
                    it.device = view.device
                    it.inode = view.inode
                }
                channel(duplicate.fileDescriptor).position(channel(descriptor).position())
                duplicate
            }.`when`(wrapped).dup()
        }
    }

    private fun channel(descriptor: FileDescriptor): FileChannel = channels.getOrPut(descriptor) {
        FileInputStream(descriptor).also { borrowedStreams += it }.channel
    }

    override fun close() {
        views.values.forEach { runCatching { it.owner.close() } }
        borrowedStreams.asReversed().forEach { runCatching(it::close) }
        heldPaths.asReversed().forEach(Files::deleteIfExists)
    }
}
