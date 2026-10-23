package moe.ouom.neriplayer.core.player.privileged.coloros

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import java.lang.reflect.InvocationTargetException

internal interface ColorOsExternalProviderSession : AutoCloseable {
    fun query(): Cursor?
    fun update(values: ContentValues, selection: String, args: Array<String>): Int
}

@RequiresApi(36)
internal class ColorOsAndroidProvider(
    private val targetUser: Int,
    private val acquire: () -> ColorOsExternalProviderSession = { ReflectiveColorOsProviderSession.acquire(targetUser) }
) {
    fun read(): ColorOsRusRow? = acquire().use { ColorOsProviderRows.read(it.query()) }

    fun compareAndSet(expected: ColorOsRusRow, replacement: ColorOsRusRow): Int {
        if (expected.hasMd5 != replacement.hasMd5 || expected.hasIsOpen != replacement.hasIsOpen) {
            throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "provider_schema_changed")
        }
        ColorOsTransportProtocol.rowJson(expected)
        ColorOsTransportProtocol.rowJson(replacement)
        var submitted = false
        var updateDenied = false
        try {
            return acquire().use { session ->
                if (ColorOsProviderRows.read(session.query()) != expected) return@use 0
                val (selection, args) = ColorOsProviderRows.selection(expected)
                val values = ContentValues().apply {
                    put("version", replacement.version)
                    put("xml", replacement.xml)
                    if (replacement.hasMd5) put("md5", replacement.md5)
                    if (replacement.hasIsOpen) put("isOpen", replacement.isOpen)
                }
                try {
                    submitted = true
                    session.update(values, selection, args)
                } catch (error: Throwable) {
                    val cause = unwrapColorOsReflection(error)
                    updateDenied = cause is SecurityException
                    throw cause
                }
            }
        } catch (error: Throwable) {
            val cause = unwrapColorOsReflection(error)
            if (!submitted || updateDenied) throw cause
            throw ColorOsConfigException(ColorOsConfigStatus.WRITE_OUTCOME_UNKNOWN, "provider_update_unknown").also {
                it.addSuppressed(cause)
            }
        }
    }
}

@RequiresApi(36)
// 仅在 root/shell 子进程使用 external provider，接口变化由结构化不支持错误处理
@SuppressLint("PrivateApi")
private class ReflectiveColorOsProviderSession(
    private val manager: Any,
    private val managerType: Class<*>,
    private val provider: Any,
    private val token: IBinder,
    private val targetUser: Int
) : ColorOsExternalProviderSession {
    private val providerType = Class.forName("android.content.IContentProvider")
    private val attribution = AttributionSource.Builder(Process.myUid())
        .setPackageName(if (Process.myUid() == 0) "root" else "com.android.shell").build()
    private val uri = ColorOsTransportProtocol.URI.toUri()

    override fun query(): Cursor? {
        val extras = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "filtername = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ColorOsTransportProtocol.FILTER))
        }
        // projection is null so optional vendor columns come from the actual schema
        return providerType.getMethod("query", AttributionSource::class.java, Uri::class.java,
            Array<String>::class.java, Bundle::class.java, Class.forName("android.os.ICancellationSignal"))
            .invoke(provider, attribution, uri, null, extras, null) as? Cursor
    }

    override fun update(values: ContentValues, selection: String, args: Array<String>): Int {
        val extras = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }
        return providerType.getMethod("update", AttributionSource::class.java, Uri::class.java,
            ContentValues::class.java, Bundle::class.java).invoke(provider, attribution, uri, values, extras) as Int
    }

    override fun close() {
        managerType.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
            .invoke(manager, ColorOsTransportProtocol.AUTHORITY, token, targetUser)
    }

    companion object {
        fun acquire(targetUser: Int): ColorOsExternalProviderSession {
            if (Process.myUid() != 0 && Process.myUid() != 2000) throw SecurityException("privileged_uid")
            val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
                ?: throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "activity_manager_missing")
            val managerType = Class.forName("android.app.IActivityManager")
            val token = Binder()
            var acquired = false
            try {
                val holder = managerType.getMethod("getContentProviderExternal", String::class.java,
                    Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                    .invoke(manager, ColorOsTransportProtocol.AUTHORITY, targetUser, token, "neriplayer_coloros")
                    ?: throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "provider_missing")
                acquired = true
                val provider = holder.javaClass.getField("provider").get(holder)
                    ?: throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "provider_interface_missing")
                return ReflectiveColorOsProviderSession(manager, managerType, provider, token, targetUser)
            } catch (error: Throwable) {
                if (acquired) {
                    runCatching {
                        managerType.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                            Int::class.javaPrimitiveType).invoke(manager, ColorOsTransportProtocol.AUTHORITY, token, targetUser)
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw unwrapColorOsReflection(error)
            }
        }
    }
}

internal fun unwrapColorOsReflection(error: Throwable): Throwable {
    if (error !is InvocationTargetException) return error
    val cause = unwrapColorOsReflection(error.targetException)
    error.suppressed.forEach(cause::addSuppressed)
    return cause
}
