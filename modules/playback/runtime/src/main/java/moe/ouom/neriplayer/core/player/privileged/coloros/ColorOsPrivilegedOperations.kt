package moe.ouom.neriplayer.core.player.privileged.coloros

import android.os.Process
import androidx.annotation.RequiresApi
import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

@RequiresApi(36)
internal class ColorOsPrivilegedOperations(private val targetUser: Int) {
    fun execute(request: String): String {
        // an uninterruptible vendor Binder must not leave a privileged child and token alive
        val deadline = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ColorOsOperationDeadline").apply { isDaemon = true }
        }
        val abort = deadline.schedule({ Process.killProcess(Process.myPid()) }, 12, TimeUnit.SECONDS)
        return try { executeBounded(request) } finally { abort.cancel(false); deadline.shutdownNow() }
    }

    private fun executeBounded(request: String): String {
        val response = JSONObject().put("uid", Process.myUid())
        try {
            val operation = ColorOsTransportProtocol.validateRequest(request)
            if (Process.myUid() != 0 && Process.myUid() != 2000) throw SecurityException("privileged_uid")
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/", "Landroid/content/", "Landroid/os/")
            val provider = ColorOsAndroidProvider(targetUser)
            when (operation) {
                "read" -> response.put("row", provider.read()?.let(ColorOsTransportProtocol::rowJson) ?: JSONObject.NULL)
                "cas" -> {
                    val json = ColorOsTransportProtocol.parse(request)
                    val expected = ColorOsTransportProtocol.row(json.getJSONObject("expected"))
                    val replacement = ColorOsTransportProtocol.row(json.getJSONObject("replacement"))
                    response.put("count", provider.compareAndSet(expected, replacement))
                }
                "reload" -> {
                    ColorOsReloadProcess.request(targetUser)
                    // this confirms am completion, not SystemUI's consumption of the new config
                    response.put("accepted", true)
                }
                "close" -> response.put("accepted", true)
            }
        } catch (error: Throwable) {
            val cause = unwrapColorOsReflection(error)
            val status = when (cause) {
                is ColorOsConfigException -> cause.status
                is SecurityException -> ColorOsConfigStatus.PERMISSION_DENIED
                is ReflectiveOperationException, is LinkageError -> ColorOsConfigStatus.UNSUPPORTED
                else -> ColorOsConfigStatus.ERROR
            }
            val detail = when (cause) {
                is ColorOsConfigException -> cause.detailCode
                is SecurityException -> "privileged_permission_denied"
                is ReflectiveOperationException, is LinkageError -> "provider_api_unsupported"
                else -> "privileged_operation_failed"
            }
            response.put("error", status.name).put("detail", detail)
        }
        return response.toString().also(ColorOsTransportProtocol::checkSize)
    }
}

internal object ColorOsReloadProcess {
    private const val OUTPUT_LIMIT = 16 * 1024
    private const val TIMEOUT_SECONDS = 10L

    fun request(targetUser: Int) {
        val process = ProcessBuilder(ColorOsTransportProtocol.reloadCommand(targetUser)).redirectErrorStream(true).start()
        val reader = FutureTask { readBounded(process.inputStream, OUTPUT_LIMIT) }
        Thread(reader, "ColorOsReloadOutput").apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw ColorOsConfigException(ColorOsConfigStatus.RELOAD_FAILED, "reload_timeout")
            }
            val output = reader.get(1, TimeUnit.SECONDS)
            ColorOsTransportProtocol.verifyReload(process.exitValue(), output)
        } catch (error: Exception) {
            val cause = if (error is java.util.concurrent.ExecutionException) error.cause ?: error else error
            if (cause is ColorOsConfigException) throw cause
            throw ColorOsConfigException(ColorOsConfigStatus.RELOAD_FAILED, "reload_process_failed")
        } finally {
            process.destroyForcibly()
            reader.cancel(true)
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun readBounded(input: InputStream, limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) throw ColorOsConfigException(ColorOsConfigStatus.RELOAD_FAILED, "reload_output_size")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
