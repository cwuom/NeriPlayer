package moe.ouom.neriplayer.core.player.policy.usb

import kotlinx.coroutines.Job

internal data class UsbAudioSinkReconfigurationToken(
    val generation: Long,
    val reason: String
)

internal data class UsbAudioSinkReconfigurationSnapshot(
    val pending: Boolean,
    val reason: String?
)

internal data class UsbAudioSinkReconfigurationStart(
    val token: UsbAudioSinkReconfigurationToken,
    val supersededJob: Job?
)

internal class UsbAudioSinkReconfigurationCoordinator {
    private data class ActiveRequest(
        val token: UsbAudioSinkReconfigurationToken,
        val job: Job?
    )

    private var nextGeneration = 0L
    private var activeRequest: ActiveRequest? = null

    @Synchronized
    fun begin(reason: String): UsbAudioSinkReconfigurationStart {
        val token = UsbAudioSinkReconfigurationToken(
            generation = ++nextGeneration,
            reason = reason
        )
        val supersededJob = activeRequest?.job
        activeRequest = ActiveRequest(token = token, job = null)
        return UsbAudioSinkReconfigurationStart(
            token = token,
            supersededJob = supersededJob
        )
    }

    @Synchronized
    fun install(token: UsbAudioSinkReconfigurationToken, job: Job): Boolean {
        val current = activeRequest ?: return false
        if (current.token != token || current.job != null) return false
        activeRequest = current.copy(job = job)
        return true
    }

    @Synchronized
    fun isLatest(token: UsbAudioSinkReconfigurationToken): Boolean {
        return activeRequest?.token == token
    }

    @Synchronized
    fun complete(token: UsbAudioSinkReconfigurationToken, job: Job): Boolean {
        val current = activeRequest ?: return false
        if (current.token != token || current.job !== job) return false
        activeRequest = null
        return true
    }

    @Synchronized
    fun abandonIfUninstalled(token: UsbAudioSinkReconfigurationToken): Boolean {
        val current = activeRequest ?: return false
        if (current.token != token || current.job != null) return false
        activeRequest = null
        return true
    }

    @Synchronized
    fun invalidate(): Job? {
        val activeJob = activeRequest?.job
        activeRequest = null
        return activeJob
    }

    @Synchronized
    fun snapshot(): UsbAudioSinkReconfigurationSnapshot {
        val current = activeRequest
        return UsbAudioSinkReconfigurationSnapshot(
            pending = current != null,
            reason = current?.token?.reason
        )
    }
}
