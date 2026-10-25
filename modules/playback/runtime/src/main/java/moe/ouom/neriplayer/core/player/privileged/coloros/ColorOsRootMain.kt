package moe.ouom.neriplayer.core.player.privileged.coloros

import android.os.Build
import android.os.Process
import androidx.annotation.Keep
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean

@Keep
object ColorOsRootMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (Build.VERSION.SDK_INT < 36 || Process.myUid() != 0 || args.size != 1) return
        val targetUser = args[0].toIntOrNull()?.takeIf { it >= 0 } ?: return
        val operations = ColorOsPrivilegedOperations(targetUser)
        try {
            while (true) {
                val request = ColorOsTransportProtocol.readFrame(System.`in`)
                val reply = operations.execute(request)
                ColorOsTransportProtocol.writeFrame(System.out, reply)
                if (ColorOsTransportProtocol.validateRequest(request) == "close") break
            }
        } catch (_: EOFException) {
            // closing the owning app's stdin also ends the privileged session
        } catch (_: Exception) {
            // a broken protocol must terminate the process without accepting another command
        }
    }
}

internal class ColorOsRootChannel(private val process: java.lang.Process) : ColorOsTransportChannel {
    private val closed = AtomicBoolean()
    private val errorText = StringBuilder()
    private val stderr = Thread({
        runCatching {
            process.errorStream.bufferedReader().use { reader ->
                val buffer = CharArray(1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(errorText) {
                        val remaining = 4096 - errorText.length
                        if (remaining > 0) errorText.append(buffer, 0, minOf(read, remaining))
                    }
                }
            }
        }
    }, "ColorOsRootStderr").apply { isDaemon = true; start() }

    override fun exchange(request: String): String {
        try {
            check(!closed.get())
            ColorOsTransportProtocol.writeFrame(process.outputStream, request)
            return ColorOsTransportProtocol.readFrame(process.inputStream)
        } catch (error: Exception) {
            val denial = synchronized(errorText) { errorText.toString() }
            if (denial.contains("denied", true) || denial.contains("not allowed", true)) {
                throw SecurityException("root_permission_denied", error)
            }
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        process.destroyForcibly()
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        stderr.interrupt()
    }
}
