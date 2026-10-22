package moe.ouom.neriplayer.ui.screen.safemode

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.platform.bilibili.auth.BiliCookieRepository
import moe.ouom.neriplayer.platform.netease.auth.NeteaseCookieRepository
import moe.ouom.neriplayer.platform.youtube.auth.YouTubeAuthRepository
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeModeConfigExportDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureName = "safe-mode-config-export-${UUID.randomUUID()}"
    private val fixtureDirectory = File(baseContext.cacheDir, fixtureName)
    private val preferenceNames = CopyOnWriteArraySet<String>()
    private lateinit var context: Context
    private val contentVisible = mutableStateOf(true)
    private var contentInstalled = false
    private val messages = CopyOnWriteArrayList<String>()
    private var requestCode: Int? = null
    private var launchCount = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            check(contract is ActivityResultContracts.CreateDocument)
            this@SafeModeConfigExportDeviceTest.requestCode = requestCode
            launchCount++
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    @Before
    fun requireComposeHost() {
        assumeComposeHostAvailable()
        // 全局 DataStore 委托需要先绑定真实应用路径，测试目录只用于隔离账号存储
        runBlocking {
            baseContext.dataStore.data.first()
            ListenTogetherPreferences(baseContext).snapshot()
        }
        context = object : ContextWrapper(composeRule.activity) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = File(fixtureDirectory, "files").apply { mkdirs() }

            override fun getCacheDir(): File = File(fixtureDirectory, "cache").apply { mkdirs() }

            override fun getNoBackupFilesDir(): File = File(fixtureDirectory, "no-backup").apply { mkdirs() }

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolatedName = "$fixtureName-$name"
                preferenceNames += isolatedName
                return baseContext.getSharedPreferences(isolatedName, mode)
            }

            override fun deleteSharedPreferences(name: String): Boolean =
                baseContext.deleteSharedPreferences("$fixtureName-$name")
        }
        seedCredentials()
    }

    @After
    fun removeFixture() {
        try {
            if (contentInstalled) {
                composeRule.runOnUiThread { contentVisible.value = false }
                composeRule.waitForIdle()
            }
        } finally {
            preferenceNames.forEach { name ->
                assertTrue("cannot flush isolated account storage", baseContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                assertTrue("cannot remove isolated preferences", baseContext.deleteSharedPreferences(name))
            }
            assertTrue("cannot remove isolated account storage", !fixtureDirectory.exists() || fixtureDirectory.deleteRecursively())
        }
    }

    private fun seedCredentials() {
        val github = SecureTokenStorage(context)
        val webDav = WebDavStorage(context)
        // 旧测试留下的同名内存回退不能成为本次种子的写入目标
        assertTrue("GitHub fixture requires isolated persistent storage", github.isPersistent)
        assertTrue("WebDAV fixture requires isolated persistent storage", webDav.isPersistent)
        val netease = NeteaseCookieRepository(context)
        val bili = BiliCookieRepository(context)
        val youtube = YouTubeAuthRepository(context)
        assertTrue(netease.isAuthStoragePersistent && bili.isAuthStoragePersistent && youtube.isAuthStoragePersistent)
        assertTrue(netease.saveCookies(mapOf("MUSIC_U" to NETEASE_COOKIE)))
        bili.saveCookies(mapOf("SESSDATA" to BILI_COOKIE))
        youtube.saveAuth(YouTubeAuthBundle(cookies = mapOf("SAPISID" to YOUTUBE_COOKIE)))
        github.saveToken(GITHUB_TOKEN)
        github.saveRepository("synthetic-owner", "synthetic-config-backup")
        webDav.saveConfiguration("https://safe-mode-fixture.invalid", "synthetic-user", WEBDAV_PASSWORD, "synthetic-backups")
    }

    @Test
    fun recoveryExportWritesSelectedLoginSectionsInsideThePasswordProtectedFile() {
        render()
        button(CoreCommonR.string.safe_mode_export_config).performClick()
        button(CoreCommonR.string.settings_config_include_login_state).performClick()
        button(CoreCommonR.string.settings_config_encrypt_file).performScrollTo().performClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_password))
            .performScrollTo().performTextReplacement(PASSWORD)
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_confirm_password))
            .performScrollTo().performTextReplacement(PASSWORD)
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        button(CoreCommonR.string.settings_backup_action_export).performNativeClick()
        composeRule.runOnIdle { assertEquals(1, launchCount) }
        val destination = File.createTempFile("safe-mode-protected-config-", ".json", context.cacheDir)
        try {
            composeRule.runOnIdle { registry.dispatchResult(requireNotNull(requestCode), Uri.fromFile(destination)) }
            composeRule.waitUntil(timeoutMillis = 15_000) { messages.isNotEmpty() }
            composeRule.waitForIdle()
            assertTrue("recovery export did not write its file: $messages", destination.length() > 0)

            val envelope = Json.parseToJsonElement(destination.readText()).jsonObject
            assertEquals("moe.ouom.neriplayer.config.encrypted", envelope.getValue("kind").jsonPrimitive.content)
            val root = Json.parseToJsonElement(decrypt(destination)).jsonObject
            for (section in ACCOUNT_SECTIONS) assertTrue("missing $section", section in root)
            assertEquals(NETEASE_COOKIE, root.getValue("neteaseAuth").jsonObject.getValue("cookies").jsonObject.getValue("MUSIC_U").jsonPrimitive.content)
            assertEquals(BILI_COOKIE, root.getValue("biliAuth").jsonObject.getValue("cookies").jsonObject.getValue("SESSDATA").jsonPrimitive.content)
            assertEquals(YOUTUBE_COOKIE, root.getValue("youTubeAuth").jsonObject.getValue("cookies").jsonObject.getValue("SAPISID").jsonPrimitive.content)
            assertEquals(GITHUB_TOKEN, root.getValue("gitHubSync").jsonObject.getValue("token").jsonPrimitive.content)
            assertEquals("synthetic-owner", root.getValue("gitHubSync").jsonObject.getValue("repoOwner").jsonPrimitive.content)
            assertEquals("synthetic-config-backup", root.getValue("gitHubSync").jsonObject.getValue("repoName").jsonPrimitive.content)
            assertEquals("https://safe-mode-fixture.invalid", root.getValue("webDavSync").jsonObject.getValue("serverUrl").jsonPrimitive.content)
            assertEquals("synthetic-user", root.getValue("webDavSync").jsonObject.getValue("username").jsonPrimitive.content)
            assertEquals(WEBDAV_PASSWORD, root.getValue("webDavSync").jsonObject.getValue("password").jsonPrimitive.content)
            assertEquals("synthetic-backups", root.getValue("webDavSync").jsonObject.getValue("basePath").jsonPrimitive.content)
            val encrypted = destination.readText()
            for (secret in listOf("neteaseAuth", NETEASE_COOKIE, BILI_COOKIE, YOUTUBE_COOKIE, GITHUB_TOKEN, WEBDAV_PASSWORD)) {
                assertFalse("encrypted backup exposed synthetic auth material", encrypted.contains(secret))
            }
        } finally {
            assertTrue("cannot remove the test backup", !destination.exists() || destination.delete())
        }
    }

    private fun render() {
        composeRule.setContent {
            if (contentVisible.value) CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalContext provides context
            ) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    animateColors = false
                ) {
                    SafeModeExportCard(busy = false, onShowMessage = { message, _ -> messages += message })
                }
            }
        }
        contentInstalled = true
    }

    private fun decrypt(destination: File): String {
        val envelope = Json.parseToJsonElement(destination.readText()).jsonObject
        fun bytes(name: String) = Base64.getDecoder().decode(envelope.getValue(name).jsonPrimitive.content)
        val spec = PBEKeySpec(PASSWORD.toCharArray(), bytes("salt"), 600_000, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, bytes("iv")))
            cipher.updateAAD("moe.ouom.neriplayer.config.encrypted|1|PBKDF2-HMAC-SHA256|600000|AES-256-GCM".toByteArray())
            cipher.doFinal(bytes("ciphertext")).toString(Charsets.UTF_8)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun button(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = context.getString(id)

    private companion object {
        const val PASSWORD = "Synthetic-safe-mode-password"
        const val NETEASE_COOKIE = "synthetic-safe-mode-netease"
        const val BILI_COOKIE = "synthetic-safe-mode-bilibili"
        const val YOUTUBE_COOKIE = "synthetic-safe-mode-youtube"
        const val GITHUB_TOKEN = "synthetic-safe-mode-github-token"
        const val WEBDAV_PASSWORD = "synthetic-safe-mode-webdav-password"
        val ACCOUNT_SECTIONS = listOf("neteaseAuth", "biliAuth", "youTubeAuth", "gitHubSync", "webDavSync")
    }
}
