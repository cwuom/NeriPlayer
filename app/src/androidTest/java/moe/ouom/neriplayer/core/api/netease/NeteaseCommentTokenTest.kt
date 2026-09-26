package moe.ouom.neriplayer.core.api.netease

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseCommentTokenTest {
    @Test
    fun officialPageProvidesCommentVerificationWithoutAnAccountWrite(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runCommentApiSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = NeteaseYdDeviceTokenProvider(context).getCommentToken()
        assertTrue("Official comment verification token was unavailable", token.isNotBlank())
    }
}
