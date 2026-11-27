package moe.ouom.neriplayer.ui.component.overlay

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NeriDropdownMenuUsageTest {

    @Test
    fun `screens use the style-aware dropdown instead of the material one`() {
        val sourceRoot = File("src/main/java")
        assertTrue("source root not found from ${File(".").absolutePath}", sourceRoot.isDirectory)
        // 直接用 Material 菜单会绕过 Clean 的毛玻璃菜单, 只允许封装它的 overlay 包引用
        val forbidden = Regex("^import androidx\\.compose\\.material3\\.DropdownMenu(Item)?$", RegexOption.MULTILINE)
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/ui/component/overlay/") }
            .filter { forbidden.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toList()
        assertTrue("Material DropdownMenu imported directly in: $offenders", offenders.isEmpty())
    }
}
