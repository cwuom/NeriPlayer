package moe.ouom.neriplayer.common.io

import java.io.FileOutputStream
import java.io.File
import java.io.Writer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 先刷盘临时文件, 再在同一目录原子替换目标文件
 */
fun File.writeTextAtomically(text: String) {
    writeAtomically { output -> output.write(text.toByteArray(Charsets.UTF_8)) }
}

fun File.writeUtf8Atomically(write: (Writer) -> Unit) {
    writeAtomically { output ->
        val writer = output.bufferedWriter(Charsets.UTF_8)
        write(writer)
        writer.flush()
    }
}

private fun File.writeAtomically(write: (FileOutputStream) -> Unit) {
    val parent = parentFile ?: File(".")
    parent.mkdirs()
    val tmp = File.createTempFile(".${name}.tmp.", ".pending", parent)
    try {
        FileOutputStream(tmp).use { output ->
            write(output)
            output.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    } finally {
        if (tmp.exists()) {
            runCatching { tmp.delete() }
        }
    }
}
