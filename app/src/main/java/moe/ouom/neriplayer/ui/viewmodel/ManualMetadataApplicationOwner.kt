package moe.ouom.neriplayer.ui.viewmodel

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyStage

data class ManualMetadataApplicationState(
    val isRunning: Boolean = false,
    val stage: SongMetadataApplyStage? = null,
    val completed: Boolean = false,
    val failed: Boolean = false
) {
    val canCancel: Boolean get() = isRunning && stage != SongMetadataApplyStage.APPLYING
}

internal class ManualMetadataApplicationOwner(
    private val onStateChanged: (ManualMetadataApplicationState) -> Unit = {}
) {
    private val mutableState = MutableStateFlow(ManualMetadataApplicationState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun start(
        onComplete: (Boolean) -> Unit = {},
        launch: ((SongMetadataApplyStage) -> Unit, (Boolean) -> Unit) -> Job?
    ): Boolean {
        if (mutableState.value.isRunning) return false
        val request = ++generation
        publish(ManualMetadataApplicationState(isRunning = true, stage = SongMetadataApplyStage.FETCHING_DETAILS))
        val launched = launch(
            { stage ->
                if (request == generation && mutableState.value.isRunning) {
                    publish(mutableState.value.copy(stage = stage))
                }
            },
            { success ->
                if (request == generation && mutableState.value.isRunning) {
                    job = null
                    publish(mutableState.value.copy(isRunning = false, completed = success, failed = !success))
                    onComplete(success)
                }
            }
        )
        if (request == generation && mutableState.value.isRunning) job = launched
        return true
    }

    fun cancel(): Boolean {
        if (!mutableState.value.canCancel) return false
        ++generation
        val cancelled = job
        job = null
        publish(ManualMetadataApplicationState())
        cancelled?.cancel()
        return true
    }

    fun reset() {
        if (!mutableState.value.isRunning) publish(ManualMetadataApplicationState())
    }

    private fun publish(value: ManualMetadataApplicationState) {
        mutableState.value = value
        onStateChanged(value)
    }
}
