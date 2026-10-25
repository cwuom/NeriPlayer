package moe.ouom.neriplayer.core.player.privileged.coloros

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import moe.ouom.neriplayer.core.player.shizuku.ShizukuPermissionHelper

@Keep
class ColorOsUserService(context: Context) : Binder() {
    private val appUid = context.applicationInfo.uid

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ColorOsServiceContract.DESTROY || code == ColorOsServiceContract.AIDL_DESTROY_ID) {
            ColorOsServiceContract.authorizeDestroy(Binder.getCallingUid(), appUid, Process.myUid())
            exitProcess(0)
        }
        if (code != ColorOsServiceContract.EXCHANGE) return super.onTransact(code, data, reply, flags)
        ColorOsTransportProtocol.authorize(Binder.getCallingUid(), appUid)
        data.enforceInterface(ColorOsServiceContract.DESCRIPTOR)
        if (data.dataAvail() > ColorOsTransportProtocol.MAX_FRAME_BYTES + 8) ColorOsTransportProtocol.malformed()
        val bytes = data.createByteArray() ?: ColorOsTransportProtocol.malformed()
        if (data.dataAvail() != 0 || reply == null) ColorOsTransportProtocol.malformed()
        val request = ColorOsTransportProtocol.decode(bytes)
        val identity = Binder.clearCallingIdentity()
        val response = try {
            if (Build.VERSION.SDK_INT < 36) throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "android_version")
            ColorOsPrivilegedOperations(appUid / 100_000).execute(request)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        reply.writeNoException()
        reply.writeByteArray(ColorOsTransportProtocol.checkSize(response))
        if (ColorOsTransportProtocol.validateRequest(request) == "close") exitProcess(0)
        return true
    }
}

internal object ColorOsServiceContract {
    const val DESCRIPTOR = "moe.ouom.neriplayer.coloros.RestrictedConfig"
    const val EXCHANGE = IBinder.FIRST_CALL_TRANSACTION
    // Shizuku documents the raw Binder code separately from the AIDL method id
    const val DESTROY = 16_777_115
    const val AIDL_DESTROY_ID = 16_777_114

    fun authorizeDestroy(caller: Int, appUid: Int, serviceUid: Int) {
        if (caller == appUid) ColorOsTransportProtocol.authorize(caller, appUid)
        else if (caller != serviceUid || (serviceUid != 0 && serviceUid != 2000)) throw SecurityException("destroy_uid")
    }
}

internal class ColorOsShizukuChannel(
    private val binder: IBinder,
    private val removeBinding: () -> Unit,
    private val permissionCheck: () -> Boolean = ShizukuPermissionHelper::isPermissionGranted
) : ColorOsTransportChannel {
    private val closed = AtomicBoolean()

    override fun exchange(request: String): String {
        check(!closed.get())
        if (!permissionCheck()) throw SecurityException("shizuku_permission_revoked")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ColorOsServiceContract.DESCRIPTOR)
            data.writeByteArray(ColorOsTransportProtocol.checkSize(request))
            if (!binder.transact(ColorOsServiceContract.EXCHANGE, data, reply, 0)) {
                ColorOsTransportProtocol.malformed()
            }
            if (reply.dataSize() > ColorOsTransportProtocol.MAX_FRAME_BYTES + 16) ColorOsTransportProtocol.malformed()
            reply.readException()
            val bytes = reply.createByteArray() ?: ColorOsTransportProtocol.malformed()
            if (reply.dataAvail() != 0) ColorOsTransportProtocol.malformed()
            return ColorOsTransportProtocol.decode(bytes)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val data = Parcel.obtain()
        try {
            // one-way destruction can interrupt an in-flight provider Binder call
            runCatching { binder.transact(ColorOsServiceContract.DESTROY, data, null, IBinder.FLAG_ONEWAY) }
        } finally {
            data.recycle()
            removeBinding()
        }
    }
}
