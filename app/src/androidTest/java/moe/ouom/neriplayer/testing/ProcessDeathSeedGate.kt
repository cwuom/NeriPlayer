package moe.ouom.neriplayer.testing

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal fun awaitProcessDeathAtSeedCheckpoint() {
    val token = InstrumentationRegistry.getArguments().getString("processDeathGateToken") ?: return
    require(Regex("[A-Za-z0-9_-]{1,64}").matches(token)) {
        "processDeathGateToken must contain 1..64 ASCII letters, digits, underscores or hyphens"
    }
    // 使用真实 target cache，避免 fixture 的 ContextWrapper 改写 host marker 路径
    val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    val ready = File(directory, "pr396-live-death-$token.ready.json")
    val temporary = File(directory, "pr396-live-death-$token.ready.json.tmp")
    check(!ready.exists() && temporary.createNewFile()) { "process death gate token already exists: $token" }
    val marker = JSONObject().put("token", token).put("pid", Process.myPid()).toString()
    FileOutputStream(temporary).use { output ->
        output.write(marker.toByteArray(Charsets.UTF_8))
        output.flush()
        output.fd.sync()
    }
    check(temporary.renameTo(ready)) { "cannot publish process death gate marker: $token" }
    // 不释放 seed 栈或执行 teardown，host 必须在检查点处终止当前进程
    CountDownLatch(1).await(60, TimeUnit.SECONDS)
    throw AssertionError("host did not terminate seed process within 60 seconds: token=$token pid=${Process.myPid()}")
}
