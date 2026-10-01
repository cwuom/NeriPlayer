package moe.ouom.neriplayer.util.io

import java.io.FileOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 先刷盘临时文件, 再在同一目录原子替换目标文件
 */
fun File.writeTextAtomically(text: String) {
    val parent = parentFile ?: File(".")
    parent.mkdirs()
    val tmp = File.createTempFile(".${name}.tmp.", ".pending", parent)
    try {
        FileOutputStream(tmp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
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
