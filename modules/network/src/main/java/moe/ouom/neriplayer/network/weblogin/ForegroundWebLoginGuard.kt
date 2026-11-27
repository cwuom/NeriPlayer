package moe.ouom.neriplayer.network.weblogin

import moe.ouom.neriplayer.common.logging.NPLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ForegroundWebLoginGuard {
    const val SKIP_REASON = "foreground_web_login_active"

    private const val TAG = "NERI-WebLoginGuard"
    private val activeCount = AtomicInteger(0)

    val isActive: Boolean
        get() = activeCount.get() > 0

    fun enter(owner: String): AutoCloseable {
        val normalizedOwner = owner.ifBlank { "unknown" }
        val count = activeCount.incrementAndGet()
        NPLogger.d(TAG, "Enter owner=$normalizedOwner activeCount=$count")
        return Token(normalizedOwner)
    }

    class Launcher(private val owner: String) : AutoCloseable {
        private var token: AutoCloseable? = null

        fun launch(start: () -> Unit) {
            if (token != null) return
            token = enter(owner)
            try {
                start()
            } catch (error: RuntimeException) {
                close()
                throw error
            }
        }

        override fun close() {
            token?.close()
            token = null
        }
    }

    private class Token(
        private val owner: String
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            val count = activeCount.decrementAndGet()
            NPLogger.d(TAG, "Exit owner=$owner activeCount=$count")
        }
    }
}
