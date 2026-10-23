package moe.ouom.neriplayer.core.player.privileged.coloros

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.player.shizuku.ShizukuPermissionHelper
import rikka.shizuku.Shizuku

object ColorOsPrivilegedBackendFactory {
    suspend fun open(context: Context, mode: ColorOsAccessMode): ColorOsConfigBackend {
        if (Build.VERSION.SDK_INT < 36) throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "android_version")
        val channel: ColorOsTransportChannel
        val uid: Int
        when (mode) {
            ColorOsAccessMode.ROOT -> {
                uid = 0
                channel = suspendCancellableCoroutine { continuation ->
                    Dispatchers.IO.dispatch(continuation.context, Runnable {
                        if (!continuation.isActive) return@Runnable
                        try {
                            val process = ProcessBuilder(ColorOsTransportProtocol.rootCommand(context.applicationInfo.sourceDir, Process.myUid() / 100_000)).start()
                            val created = ColorOsRootChannel(process)
                            continuation.resume(created) { _, resource, _ -> resource.close() }
                        } catch (error: Throwable) { continuation.resumeWith(Result.failure(error)) }
                    })
                }
            }
            ColorOsAccessMode.SHIZUKU -> {
                if (!ShizukuPermissionHelper.ensurePermission()) {
                    throw ColorOsConfigException(ColorOsConfigStatus.PERMISSION_DENIED, "shizuku_permission_denied")
                }
                uid = Shizuku.getUid()
                if (uid != 0 && uid != 2000) throw ColorOsConfigException(ColorOsConfigStatus.PERMISSION_DENIED, "shizuku_uid")
                channel = bindShizuku(context.applicationContext)
            }
        }
        val backend = ColorOsTransportBackend(uid, channel)
        try {
            backend.read()
            return backend
        } catch (error: Throwable) {
            backend.close()
            throw error
        }
    }

    private suspend fun bindShizuku(context: Context): ColorOsTransportChannel = withContext(Dispatchers.Main.immediate) {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val args = Shizuku.UserServiceArgs(ComponentName(context, ColorOsUserService::class.java))
            .daemon(false).tag("neriplayer_coloros_${Process.myUid()}_$suffix")
            .processNameSuffix("coloros_lyrics_$suffix").version(1)
        val handler = Handler(Looper.getMainLooper())
        val removed = AtomicBoolean()
        lateinit var connection: ServiceConnection
        fun remove() {
            if (removed.compareAndSet(false, true)) handler.post {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
        try {
            withTimeout(15_000) {
                suspendCancellableCoroutine { continuation ->
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (service == null) {
                                remove()
                                if (continuation.isActive) continuation.resumeWith(Result.failure(ColorOsConfigException(ColorOsConfigStatus.ERROR, "shizuku_null_binder")))
                                return
                            }
                            val created = ColorOsShizukuChannel(service, ::remove)
                            if (removed.get()) created.close()
                            else continuation.resume(created) { _, resource, _ -> resource.close() }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            remove()
                            if (continuation.isActive) continuation.resumeWith(Result.failure(ColorOsConfigException(ColorOsConfigStatus.ERROR, "shizuku_disconnected")))
                        }
                    }
                    continuation.invokeOnCancellation { remove() }
                    try {
                        if (continuation.isActive && !removed.get()) Shizuku.bindUserService(args, connection)
                    }
                    catch (error: Throwable) { remove(); continuation.resumeWith(Result.failure(error)) }
                }
            }
        } catch (error: Throwable) {
            remove()
            if (error is TimeoutCancellationException) throw ColorOsConfigException(ColorOsConfigStatus.ERROR, "shizuku_bind_timeout")
            throw error
        }
    }
}
