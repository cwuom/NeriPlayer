package moe.ouom.neriplayer.ui.screen.debug

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.component.playback.MetadataSearchInput
import moe.ouom.neriplayer.ui.component.playback.PlaybackSheetTextField
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListenTogetherTextFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var hostView: View

    @Test
    fun cleanLightLabelsKeepTheirFillAndFieldBehavior() = verifyFields(UiStyle.Clean, false)

    @Test
    fun cleanDarkLabelsKeepTheirFillAndFieldBehavior() = verifyFields(UiStyle.Clean, true)

    @Test
    fun materialLightLabelsKeepTheirOutlineAndFieldBehavior() = verifyFields(UiStyle.Material, false)

    @Test
    fun materialDarkLabelsKeepTheirOutlineAndFieldBehavior() = verifyFields(UiStyle.Material, true)

    @Test
    fun metadataSearchInCleanLightKeepsHintFillAndSearchActions() = verifyMetadataSearch(dark = false)

    @Test
    fun metadataSearchInCleanDarkKeepsHintFillAndSearchActions() = verifyMetadataSearch(dark = true)

    private fun verifyMetadataSearch(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val labelText = context.getString(CoreCommonR.string.search_keywords)
        val state = mutableStateOf(ManualSearchState())
        val searchActions = mutableListOf<Pair<String, String>>()
        var clearFocus: () -> Unit = {}
        var expectedFill = 0
        composeRule.setContent {
            NeriTheme(false, dark, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                val view = LocalView.current
                val focusManager = LocalFocusManager.current
                val focusRequester = remember { FocusRequester() }
                val colors = MaterialTheme.colorScheme
                val surface = colors.surfaceContainerLow
                val fill = colors.onSurface.copy(alpha = 0.06f).compositeOver(surface).toArgb()
                SideEffect {
                    hostView = view
                    expectedFill = fill
                    clearFocus = { focusManager.clearFocus(force = true) }
                }
                Surface(Modifier.fillMaxSize(), color = surface) {
                    Column(Modifier.padding(24.dp)) {
                        MetadataSearchInput(
                            state = state.value,
                            enabled = true,
                            canSearch = state.value.keyword.isNotBlank(),
                            focusRequester = focusRequester,
                            onKeywordChange = { state.value = state.value.copy(keyword = it) },
                            onSearch = { searchActions += "button" to state.value.keyword },
                            onSearchSubmitted = { searchActions += "ime" to state.value.keyword }
                        )
                    }
                }
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        val trailingSearch = composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_search))
        fun assertFill(stage: String) {
            val label = composeRule.onNodeWithText(labelText, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val bounds = field.fetchSemanticsNode().boundsInRoot
            withFieldPixels { bitmap ->
                val (besideLabel, insideField) = bitmap.labelPixels(label)
                val body = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                assertTrue("Metadata search label has a fill gap: dark=$dark stage=$stage",
                    colorDistance(besideLabel, insideField) < 0.025f)
                assertTrue("Metadata search fill changed: dark=$dark stage=$stage",
                    listOf(besideLabel, insideField, body).all { colorDistance(it, expectedFill) < 0.025f })
            }
        }

        field.assertIsDisplayed().assertIsNotFocused()
        trailingSearch.assertIsNotEnabled()
        assertFill("empty")

        field.performClick().assertIsFocused()
        assertFill("focused")

        field.performTextReplacement("Blue Hour")
        field.assertTextContains("Blue Hour")
        assertFill("focused with text")
        field.assert(hasImeAction(ImeAction.Search))
        field.performImeAction()
        trailingSearch.assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("ime" to "Blue Hour", "button" to "Blue Hour"), searchActions)
            clearFocus()
        }

        field.assertIsNotFocused().assertTextContains("Blue Hour")
        assertFill("unfocused with text")
    }

    @Test
    fun sharedInputsKeepAddressHintsAndMultilineInvitesWhileJoiningDisablesEditing() {
        val address = mutableStateOf("")
        val invite = mutableStateOf("Room invite\nJoin secret")
        val joining = mutableStateOf(false)
        composeRule.setContent {
            NeriTheme(false, false, false, "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PlaybackSheetTextField(
                        value = address.value,
                        onValueChange = { address.value = it },
                        label = "Address",
                        placeholder = "192.168.1.2:39001",
                        modifier = Modifier.testTag("address")
                    )
                    PlaybackSheetTextField(
                        value = invite.value,
                        onValueChange = { invite.value = it },
                        label = "Invite",
                        enabled = !joining.value,
                        singleLine = false,
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.testTag("invite")
                    )
                }
            }
        }

        val addressField = composeRule.onNodeWithTag("address")
        addressField.performClick().assertIsFocused()
        composeRule.onNodeWithText("192.168.1.2:39001", useUnmergedTree = true).assertIsDisplayed()
        addressField.performTextReplacement("192.168.1.3:39001")
        composeRule.onNodeWithTag("invite").assertIsEnabled()
            .performTextReplacement("Updated room\nUpdated secret")
        composeRule.runOnIdle {
            assertEquals("192.168.1.3:39001", address.value)
            assertEquals("Updated room\nUpdated secret", invite.value)
            joining.value = true
        }
        composeRule.onNodeWithTag("invite").assertIsNotEnabled()
            .assertTextContains("Updated room\nUpdated secret")
    }

    private fun verifyFields(style: UiStyle, dark: Boolean) {
        val nickname = mutableStateOf("Alice")
        composeRule.setContent {
            NeriTheme(false, dark, false, "6750A4", uiStyle = style, animateColors = false) {
                val view = LocalView.current
                SideEffect { hostView = view }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PlaybackSheetTextField(
                            value = nickname.value,
                            onValueChange = { nickname.value = it },
                            label = "当前昵称",
                            modifier = Modifier.testTag("nickname")
                        )
                        PlaybackSheetTextField(
                            value = "ROOM42",
                            onValueChange = { error("A read-only room id must not accept edits") },
                            label = "房间号",
                            modifier = Modifier.testTag("room-id"),
                            readOnly = true
                        )
                    }
                }
            }
        }

        val nicknameField = composeRule.onNodeWithTag("nickname")
        val roomField = composeRule.onNodeWithTag("room-id")
        nicknameField.assertIsDisplayed().assertTextContains("Alice")
        roomField.assertIsDisplayed().assertTextContains("ROOM42")
        assertFalse("Room id must remain read-only", SemanticsActions.SetText in roomField.fetchSemanticsNode().config)
        assertFieldAppearance(style, dark, "unfocused")

        nicknameField.performClick().assertIsFocused().performTextReplacement("EditedNickname")
        composeRule.runOnIdle { assertEquals("EditedNickname", nickname.value) }
        assertFieldAppearance(style, dark, "nickname-focused")

        roomField.performClick().assertIsFocused().assertTextContains("ROOM42")
        assertFalse("Focused room id must remain read-only", SemanticsActions.SetText in roomField.fetchSemanticsNode().config)
        assertFieldAppearance(style, dark, "room-focused")
    }

    private fun assertFieldAppearance(style: UiStyle, dark: Boolean, state: String) {
        val fields = listOf("nickname" to "当前昵称", "room-id" to "房间号").map { (tag, label) ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot to
                composeRule.onNodeWithText(label, useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        withFieldPixels { bitmap ->
            val reportDirectory = File("build/reports/listen-together-fields").apply { mkdirs() }
            File(reportDirectory, "${style.name}-${if (dark) "dark" else "light"}-$state.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fields.forEach { (field, label) ->
                val (besideLabel, insideField) = bitmap.labelPixels(label)
                assertTrue(
                    "Label must keep a continuous background: $style dark=$dark state=$state",
                    colorDistance(besideLabel, insideField) < 0.025f
                )
                val body = bitmap.getPixel((field.right - 60f).toInt(), field.center.y.toInt())
                val background = bitmap.getPixel(8, 8)
                if (style == UiStyle.Clean) {
                    assertTrue("Clean fields must retain their fill", colorDistance(body, background) > 0.025f)
                } else {
                    assertTrue("Material fields must retain a transparent body", colorDistance(body, background) < 0.015f)
                }
            }
        }
    }

    private fun withFieldPixels(check: (Bitmap) -> Unit) {
        composeRule.runOnIdle {
            val bitmap = Bitmap.createBitmap(hostView.width, hostView.height, Bitmap.Config.ARGB_8888)
            try {
                hostView.draw(Canvas(bitmap))
                check(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun Bitmap.labelPixels(label: Rect): Pair<Int, Int> {
        val y = (label.bottom - 2f).toInt()
        return getPixel((label.right + 2f).toInt(), y) to getPixel((label.right + 14f).toInt(), y)
    }

    private fun colorDistance(first: Int, second: Int): Float = max(
        max(abs(AndroidColor.red(first) - AndroidColor.red(second)),
            abs(AndroidColor.green(first) - AndroidColor.green(second))),
        abs(AndroidColor.blue(first) - AndroidColor.blue(second))
    ) / 255f
}
