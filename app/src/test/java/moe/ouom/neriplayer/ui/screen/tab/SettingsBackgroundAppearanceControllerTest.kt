package moe.ouom.neriplayer.ui.screen.tab

import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.BackgroundDraftReconciler
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.BackgroundImageImportOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsBackgroundImageDraft
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.draftBackgroundValue
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.resolveNowPlayingBackgroundExclusion
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.shouldRefreshBackgroundImageDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SettingsBackgroundAppearanceControllerTest {
    @Test
    fun `draft refresh ignores small slider rounding differences`() {
        assertTrue(shouldRefreshBackgroundImageDraft(Float.NaN, 0.5f))
        assertFalse(shouldRefreshBackgroundImageDraft(0.5004f, 0.5f))
        assertTrue(shouldRefreshBackgroundImageDraft(0.51f, 0.5f))
        assertEquals(0.5f, draftBackgroundValue(Float.NaN, 0.5f))
    }

    @Test
    fun `draft reconciler applies committed external change but preserves rounding`() {
        val draft = mutableFloatStateOf(0.7f)
        val reconciler = BackgroundDraftReconciler()
        reconciler.reconcile(draft, 0.4f)
        assertEquals(0.4f, draft.floatValue)

        draft.floatValue = 0.4004f
        reconciler.reconcile(draft, 0.4f)
        assertEquals(0.4004f, draft.floatValue)

        draft.floatValue = 0.6f
        reconciler.reconcile(draft, 0.4f)
        assertEquals(0.6f, draft.floatValue)
        reconciler.reconcile(draft, 0.8f)
        assertEquals(0.8f, draft.floatValue)

        val nextImageDraft = mutableFloatStateOf(0.3f)
        reconciler.reconcile(nextImageDraft, 0.2f)
        assertEquals(0.2f, nextImageDraft.floatValue)
    }

    @Test
    fun `fresh background draft shows committed values before side effects run`() {
        val model = SettingsBackgroundImageDraft(
            mutableFloatStateOf(Float.NaN),
            mutableFloatStateOf(Float.NaN),
            committedBlur = 12f,
            committedAlpha = 0.7f
        )

        assertEquals(12f, model.blur)
        assertEquals(0.7f, model.alpha)
        model.blur = 15f
        assertEquals(15f, model.blur)
    }

    @Test
    fun `background image import ignores cancellation and failure but publishes a saved image`() = runTest {
        val picked = mock(Uri::class.java)
        val saved = mock(Uri::class.java)
        var imports = 0
        val published = mutableListOf<Uri>()
        var importResult: Uri? = null
        val owner = BackgroundImageImportOwner(
            import = { source ->
                assertEquals(picked, source)
                imports++
                importResult
            },
            onImported = { published += it }
        )

        owner.acceptPickerResult(null)
        owner.acceptPickerResult(picked)
        importResult = saved
        owner.acceptPickerResult(picked)

        assertEquals(2, imports)
        assertEquals(listOf(saved), published)
    }

    @Test
    fun `cover blur disables incompatible visual modes in the original order`() {
        val changes = mutableListOf<String>()
        resolveNowPlayingBackgroundExclusion(
            coverBlurEnabled = true,
            dynamicEnabled = true,
            reactiveEnabled = true
        ).apply(
            onDynamicChange = { changes += "dynamic=$it" },
            onReactiveChange = { changes += "reactive=$it" }
        )

        assertEquals(listOf("dynamic=false", "reactive=false"), changes)
    }

    @Test
    fun `reactive mode is cleared when dynamic background is off`() {
        val decision = resolveNowPlayingBackgroundExclusion(
            coverBlurEnabled = false,
            dynamicEnabled = false,
            reactiveEnabled = true
        )

        assertFalse(decision.disableDynamic)
        assertTrue(decision.disableReactive)
    }

    @Test
    fun `compatible visual modes are left alone`() {
        val decision = resolveNowPlayingBackgroundExclusion(
            coverBlurEnabled = false,
            dynamicEnabled = true,
            reactiveEnabled = true
        )

        assertFalse(decision.disableDynamic)
        assertFalse(decision.disableReactive)
    }
}
