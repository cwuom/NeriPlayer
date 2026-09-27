package moe.ouom.neriplayer.ui.screen.tab

import android.content.Context
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.settings.background.BackgroundImageStorage
import kotlin.math.absoluteValue

internal class SettingsBackgroundImageDraft(
    private val blurState: MutableFloatState,
    private val alphaState: MutableFloatState,
    private val committedBlur: Float,
    private val committedAlpha: Float
) {
    var blur: Float
        get() = draftBackgroundValue(blurState.floatValue, committedBlur)
        set(value) { blurState.floatValue = value }

    var alpha: Float
        get() = draftBackgroundValue(alphaState.floatValue, committedAlpha)
        set(value) { alphaState.floatValue = value }
}

internal fun shouldRefreshBackgroundImageDraft(pending: Float, committed: Float): Boolean =
    pending.isNaN() || (pending - committed).absoluteValue > 0.001f

internal fun draftBackgroundValue(pending: Float, committed: Float): Float =
    if (pending.isNaN()) committed else pending

private fun reconciledBackgroundDraftValue(pending: Float, committed: Float): Float =
    if (shouldRefreshBackgroundImageDraft(pending, committed)) committed else pending

@Composable
private fun rememberBackgroundDraftValue(imageUri: String?, committed: Float): MutableFloatState {
    val draft = rememberBackgroundDraftState(imageUri)
    val reconciler = rememberBackgroundDraftReconciler()
    ReconcileBackgroundDraft(reconciler, draft, committed)
    return draft
}

@Composable
private fun rememberBackgroundDraftState(imageUri: String?): MutableFloatState =
    key(imageUri) { rememberSavedBackgroundDraft() }

@Composable
private fun rememberSavedBackgroundDraft(): MutableFloatState =
    rememberSaveable { mutableFloatStateOf(Float.NaN) }

internal class BackgroundDraftReconciler {
    private var lastDraft: MutableFloatState? = null
    private var lastCommitted: Float? = null

    fun reconcile(draft: MutableFloatState, committed: Float) {
        if (lastDraft !== draft) {
            lastDraft = draft
            lastCommitted = null
        }
        if (lastCommitted == committed) return
        draft.floatValue = reconciledBackgroundDraftValue(draft.floatValue, committed)
        lastCommitted = committed
    }
}

@Composable
private fun rememberBackgroundDraftReconciler(): BackgroundDraftReconciler =
    remember { BackgroundDraftReconciler() }

@Composable
private fun ReconcileBackgroundDraft(
    reconciler: BackgroundDraftReconciler,
    draft: MutableFloatState,
    committed: Float
) {
    SideEffect(BackgroundDraftReconcileAction(reconciler, draft, committed).effect)
}

private class BackgroundDraftReconcileAction(
    reconciler: BackgroundDraftReconciler,
    draft: MutableFloatState,
    committed: Float
) {
    val effect: () -> Unit = { reconciler.reconcile(draft, committed) }
}

@Composable
internal fun rememberSettingsBackgroundImageDraft(
    imageUri: String?,
    committedBlur: Float,
    committedAlpha: Float
): SettingsBackgroundImageDraft {
    val blurState = rememberBackgroundDraftValue(imageUri, committedBlur)
    val alphaState = rememberBackgroundDraftValue(imageUri, committedAlpha)
    return SettingsBackgroundImageDraft(blurState, alphaState, committedBlur, committedAlpha)
}

internal class BackgroundImageImportOwner(
    private val import: suspend (Uri) -> Uri?,
    private val onImported: (Uri) -> Unit
) {
    suspend fun acceptPickerResult(uri: Uri?) {
        if (uri == null) return
        import(uri)?.let(onImported)
    }
}

internal class BackgroundImagePickerPort(
    val previousUri: String?,
    val onBackgroundImageChange: (Uri?) -> Unit
)

private class BackgroundImagePickerHandler(
    private val scope: CoroutineScope,
    private val owner: BackgroundImageImportOwner
) {
    val callback: (Uri?) -> Unit = ::onResult

    fun onResult(uri: Uri?) {
        scope.launch { owner.acceptPickerResult(uri) }
    }
}

private fun createBackgroundImageImportOwner(
    context: Context,
    port: BackgroundImagePickerPort
): BackgroundImageImportOwner = BackgroundImageImportOwner(
    import = { sourceUri ->
        BackgroundImageStorage.importFromUri(context, sourceUri, port.previousUri)
    },
    onImported = port.onBackgroundImageChange
)

private class BackgroundImagePickerAction(
    private val launcher: ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?>
) {
    val launch: () -> Unit = {
        launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

@Composable
internal fun rememberSettingsBackgroundImagePicker(
    port: BackgroundImagePickerPort
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = createBackgroundImageImportOwner(context, port)
    val handler = BackgroundImagePickerHandler(scope, owner)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = handler.callback
    )
    return BackgroundImagePickerAction(launcher).launch
}

internal data class NowPlayingBackgroundExclusion(
    val disableDynamic: Boolean,
    val disableReactive: Boolean
) {
    fun apply(
        onDynamicChange: (Boolean) -> Unit,
        onReactiveChange: (Boolean) -> Unit
    ) {
        if (disableDynamic) onDynamicChange(false)
        if (disableReactive) onReactiveChange(false)
    }
}

internal fun resolveNowPlayingBackgroundExclusion(
    coverBlurEnabled: Boolean,
    dynamicEnabled: Boolean,
    reactiveEnabled: Boolean
): NowPlayingBackgroundExclusion = NowPlayingBackgroundExclusion(
    disableDynamic = coverBlurEnabled && dynamicEnabled,
    disableReactive = reactiveEnabled && (coverBlurEnabled || !dynamicEnabled)
)

internal class NowPlayingBackgroundExclusionPort(
    val coverBlurEnabled: Boolean,
    val dynamicEnabled: Boolean,
    val reactiveEnabled: Boolean,
    val onDynamicChange: (Boolean) -> Unit,
    val onReactiveChange: (Boolean) -> Unit
)

@Composable
internal fun EnforceNowPlayingBackgroundExclusion(port: NowPlayingBackgroundExclusionPort) {
    LaunchedEffect(port.dynamicEnabled, port.coverBlurEnabled) {
        resolveNowPlayingBackgroundExclusion(
            port.coverBlurEnabled,
            port.dynamicEnabled,
            port.reactiveEnabled
        ).apply(port.onDynamicChange, port.onReactiveChange)
    }
}
