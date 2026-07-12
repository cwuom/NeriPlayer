package moe.ouom.neriplayer.util.io

import java.io.File

/**
 * tmp + rename 原子写入，防止进程崩溃时丢失整个文件。
 * rename 跨文件系统失败时退化为直写 + 删 tmp。
 */
fun File.writeTextAtomically(text: String) {
    val tmp = File(parentFile, "${name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        writeText(text)
        tmp.delete()
    }
}
