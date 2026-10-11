package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSyncConfigDraftRestorationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `webdav draft restores non secret fields and reloads the password from storage`() {
        val tester = StateRestorationTester(composeRule)
        val loads = mutableListOf<String>()
        lateinit var draft: WebDavConfigDraft
        tester.setContent {
            draft = rememberWebDavConfigDraft(
                loadServerUrl = { loads += "serverUrl"; "https://dav.stored.example" },
                loadUsername = { loads += "username"; "stored-user" },
                loadPassword = { loads += "password"; "stored-password" },
                loadBasePath = { loads += "basePath"; "NeriPlayer" }
            )
        }

        composeRule.runOnIdle {
            draft.serverUrl.value = "https://dav.typed.example"
            draft.username.value = "typed-user"
            draft.password.value = "typed-password"
            draft.basePath.value = "Backups/Phone"
        }

        tester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals("https://dav.typed.example", draft.serverUrl.value)
            assertEquals("typed-user", draft.username.value)
            assertEquals("Backups/Phone", draft.basePath.value)
            assertEquals("stored-password", draft.password.value)
            assertEquals(listOf("serverUrl", "username", "password", "basePath", "password"), loads)
        }
    }

    @Test
    fun `github draft restores repository choices but clears the token`() {
        val tester = StateRestorationTester(composeRule)
        lateinit var draft: GitHubConfigDraft
        tester.setContent { draft = rememberGitHubConfigDraft() }

        composeRule.runOnIdle {
            assertEquals("neriplayer-backup", draft.newRepoName.value)
            draft.token.value = "ghp_typedToken"
            draft.newRepoName.value = "phone-backup"
            draft.useExistingRepo.value = true
            draft.existingRepoName.value = "octocat/neri-backup"
        }

        tester.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals("", draft.token.value)
            assertEquals("phone-backup", draft.newRepoName.value)
            assertTrue(draft.useExistingRepo.value)
            assertEquals("octocat/neri-backup", draft.existingRepoName.value)
        }
    }

    @Test
    fun `password and token never reach the saveable state registry`() {
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        lateinit var webDav: WebDavConfigDraft
        lateinit var gitHub: GitHubConfigDraft
        composeRule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                webDav = rememberWebDavConfigDraft(
                    loadServerUrl = { "" },
                    loadUsername = { "" },
                    loadPassword = { "" },
                    loadBasePath = { "" }
                )
                gitHub = rememberGitHubConfigDraft()
            }
        }

        composeRule.runOnIdle {
            webDav.serverUrl.value = "https://dav.typed.example"
            webDav.username.value = "typed-user"
            webDav.password.value = "typed-password"
            gitHub.token.value = "ghp_typedToken"
            gitHub.existingRepoName.value = "octocat/neri-backup"
        }

        val savedValues = composeRule.runOnIdle {
            registry.performSave().values.flatMap { it.unwrapSavedValues() }
        }
        assertTrue(savedValues.containsAll(listOf("https://dav.typed.example", "typed-user", "octocat/neri-backup")))
        assertFalse("typed-password" in savedValues)
        assertFalse("ghp_typedToken" in savedValues)
    }

    private fun Any?.unwrapSavedValues(): List<Any?> = when (this) {
        is MutableState<*> -> value.unwrapSavedValues()
        is Iterable<*> -> flatMap { it.unwrapSavedValues() }
        else -> listOf(this)
    }
}
