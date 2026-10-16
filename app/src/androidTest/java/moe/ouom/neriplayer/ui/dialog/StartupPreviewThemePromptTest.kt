package moe.ouom.neriplayer.ui.dialog

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupPreviewThemePromptTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "preview-theme-test-${UUID.randomUUID()}")
    private val storeJobs = mutableListOf<Job>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private var fixture by mutableStateOf<PromptFixture?>(null)
    private var contentMounted by mutableStateOf(true)

    @Before
    fun installContent() {
        composeRule.setContent {
            if (contentMounted) fixture?.let { current ->
                key(current) { PromptContent(current) }
            }
        }
    }

    @After
    fun cleanUp() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            contentMounted = false
            fixture = null
        }
        composeRule.waitForIdle()
        runBlocking {
            gates.forEach { it.cancel() }
            storeJobs.forEach { it.cancelAndJoin() }
        }
        assertTrue(directory.deleteRecursively())
    }

    @Test
    fun unknownReadAndUnavailableOrPausedHostKeepThePromptPending() {
        val store = newStore()
        val readGate = gate()
        val readDelivered = gate()
        val delayed = object : DataStore<Preferences> {
            override val data = flow {
                readGate.await()
                emitAll(store.data.onEach { readDelivered.complete(Unit) })
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                store.updateData(transform)
        }
        val current = PromptFixture(store, PreviewThemePromptRepository(delayed)).apply {
            canShowDialog = false
            isResumed = false
        }
        mount(current)
        composeRule.onNodeWithTag("prompt_pending").assertTextEquals("pending:true")
        assertNoDialog()

        readGate.complete(Unit)
        composeRule.waitUntil(5_000) { readDelivered.isCompleted }
        composeRule.waitForIdle()
        composeRule.runOnIdle { current.isResumed = true }
        assertNoDialog()
        composeRule.runOnIdle {
            current.isResumed = false
            current.canShowDialog = true
        }
        assertNoDialog()
        composeRule.runOnIdle { current.isResumed = true }

        waitForDialog()
        composeRule.onNodeWithTag("prompt_pending").assertTextEquals("pending:true")
    }

    @Test
    fun acceptingChangesTheLiveThemeWithoutRemountingItsContent() {
        val store = newStore()
        mount(PromptFixture(store))
        waitForDialog()
        val originalInstance = instanceText()

        clickAction(R.string.preview_theme_prompt_enable)

        waitForTheme("clean")
        waitForPromptCompleted()
        assertNoDialog()
        assertEquals(originalInstance, instanceText())
    }

    @Test
    fun declineAndBackStayAnsweredAfterThePromptIsMountedAgain() {
        listOf(false, true).forEach { dismissWithBack ->
            val current = PromptFixture(newStore())
            mount(current)
            waitForDialog()
            if (dismissWithBack) pressBack() else clickAction(R.string.preview_theme_prompt_decline)
            waitForPromptCompleted()
            composeRule.onNodeWithTag("theme_style").assertTextEquals("material")
            composeRule.runOnIdle { current.promptMounted = false }
            composeRule.waitForIdle()
            composeRule.runOnIdle { current.promptMounted = true }

            waitForPromptCompleted()
            assertNoDialog()
            composeRule.onNodeWithTag("theme_style").assertTextEquals("material")
        }
    }

    @Test
    fun failedSaveKeepsTheDialogOpenAndAllowsRetryingTheChoice() {
        val store = newStore()
        val writes = ControlledWritesStore(store)
        val writeGate = gate()
        writes.writeGate = writeGate
        writes.failure = IOException("preview choice write failed")
        mount(PromptFixture(store, PreviewThemePromptRepository(writes)))
        waitForDialog()

        clickAction(R.string.preview_theme_prompt_enable)
        composeRule.waitUntil(5_000) { writes.writeStarted.isCompleted }
        composeRule.onNodeWithText(text(R.string.preview_theme_prompt_enable)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.preview_theme_prompt_decline)).assertIsNotEnabled()
        composeRule.onNodeWithTag("prompt_pending").assertTextEquals("pending:true")
        composeRule.onNodeWithTag("theme_style").assertTextEquals("material")
        writeGate.complete(Unit)
        waitForText(text(R.string.preview_theme_prompt_save_failed))
        composeRule.onNodeWithText(text(R.string.preview_theme_prompt_enable)).assertIsEnabled()
        composeRule.onNodeWithTag("prompt_pending").assertTextEquals("pending:true")
        composeRule.runOnIdle {
            writes.failure = null
            writes.writeGate = null
        }

        clickAction(R.string.preview_theme_prompt_enable)

        waitForTheme("clean")
        waitForPromptCompleted()
        assertNoDialog()
    }

    @Test
    fun bothChoicesRemainReachableInAShortDialogWithLargeText() {
        mount(PromptFixture(newStore()).apply { fontScale = 1.6f })
        waitForDialog()
        constrainDialogWindow(widthDp = 360, heightDp = 320)

        revealAction(R.string.preview_theme_prompt_enable)
        revealAction(R.string.preview_theme_prompt_decline)
        clickAction(R.string.preview_theme_prompt_decline)

        waitForPromptCompleted()
        assertNoDialog()
    }

    @Test
    fun alreadyEnabledCleanLetsTheStartupQueueProceedWithoutAsking() {
        val store = newStore()
        runBlocking { store.edit { it[SettingsKeys.UI_STYLE] = "clean" } }
        mount(PromptFixture(store))

        waitForPromptCompleted()
        waitForTheme("clean")
        assertNoDialog()
    }

    @Composable
    private fun PromptContent(current: PromptFixture) {
        val styles = remember(current.store) {
            current.store.data.map { UiStyle.fromStorage(it[SettingsKeys.UI_STYLE]) }
        }
        val style by styles.collectAsState(initial = UiStyle.Material)
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, current.fontScale)) {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false
            ) {
                val instance = remember { UUID.randomUUID().toString() }
                val pending = if (current.promptMounted) {
                    startupPreviewThemePrompt(current.canShowDialog, current.repository, current.isResumed)
                } else {
                    false
                }
                Column {
                    Text(NeriDesign.tokens.style.storageValue, Modifier.testTag("theme_style"))
                    Text("pending:$pending", Modifier.testTag("prompt_pending"))
                    Text(instance, Modifier.testTag("content_instance"))
                }
            }
        }
    }

    private fun mount(current: PromptFixture) {
        composeRule.runOnIdle { fixture = current }
    }

    private fun newStore(): DataStore<Preferences> {
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "settings-${storeJobs.size}.preferences_pb")
        val job = SupervisorJob().also(storeJobs::add)
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file }
        )
    }

    private fun gate() = CompletableDeferred<Unit>().also(gates::add)

    private fun text(id: Int): String = context.getString(id)

    private fun waitForText(value: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDialog() = waitForText(text(R.string.preview_theme_prompt_title))

    private fun waitForTheme(style: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("theme_style").fetchSemanticsNode()
                .config[SemanticsProperties.Text].single().text == style
        }
    }

    private fun waitForPromptCompleted() {
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("prompt_pending").fetchSemanticsNode()
                .config[SemanticsProperties.Text].single().text == "pending:false"
        }
    }

    private fun assertNoDialog() {
        composeRule.onNodeWithText(text(R.string.preview_theme_prompt_title)).assertDoesNotExist()
    }

    private fun instanceText(): String = composeRule.onNodeWithTag("content_instance")
        .fetchSemanticsNode().config[SemanticsProperties.Text].single().text

    private fun revealAction(id: Int) {
        val node = composeRule.onNodeWithText(text(id))
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled()
    }

    private fun clickAction(id: Int) {
        revealAction(id)
        composeRule.onNodeWithText(text(id)).performClick()
    }

    private fun constrainDialogWindow(widthDp: Int, heightDp: Int) {
        onView(isRoot()).inRoot(isDialog()).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isRoot()
            override fun getDescription(): String = "constrain the preview prompt window"
            override fun perform(uiController: UiController, view: View) {
                val window = checkNotNull(findDialogWindow(view))
                val density = view.resources.displayMetrics.density
                val heightPx = (heightDp * density).roundToInt()
                window.setLayout((widthDp * density).roundToInt(), heightPx)
                uiController.loopMainThreadUntilIdle()
                assertTrue("dialog must use the short viewport", view.height <= heightPx + 1)
            }
        })
        composeRule.waitForIdle()
    }

    private fun findDialogWindow(view: View): Window? {
        if (view is DialogWindowProvider) return view.window
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findDialogWindow(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private class PromptFixture(
        val store: DataStore<Preferences>,
        val repository: PreviewThemePromptRepository = PreviewThemePromptRepository(store)
    ) {
        var canShowDialog by mutableStateOf(true)
        var isResumed by mutableStateOf(true)
        var promptMounted by mutableStateOf(true)
        var fontScale by mutableStateOf(1f)
    }

    private class ControlledWritesStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val writeStarted = CompletableDeferred<Unit>()
        var writeGate: CompletableDeferred<Unit>? = null
        var failure: IOException? = null
        override val data = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeStarted.complete(Unit)
            writeGate?.await()
            failure?.let { throw it }
            return delegate.updateData(transform)
        }
    }
}
