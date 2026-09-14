package moe.ouom.neriplayer.architecture

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalManagementLineBudgetPolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `counts 1999 and 2000 lines with or without a final newline`() {
        val cases = listOf(
            LineFixture("lf-final-1999.kt", 1_999, "\n", true, true),
            LineFixture("lf-final-2000.kt", 2_000, "\n", true, false),
            LineFixture("lf-no-final-1999.kt", 1_999, "\n", false, true),
            LineFixture("lf-no-final-2000.kt", 2_000, "\n", false, false),
            LineFixture("crlf-final-1999.kt", 1_999, "\r\n", true, true),
            LineFixture("crlf-final-2000.kt", 2_000, "\r\n", true, false)
        )

        cases.forEach { fixture ->
            val file = temporaryFolder.newFile(fixture.name)
            writeLines(file, fixture.lineCount, fixture.separator, fixture.hasFinalNewline)

            val actualLineCount = LocalManagementLineBudget.countPhysicalLines(file)
            assertEquals(fixture.lineCount, actualLineCount)
            assertEquals(fixture.isWithinBudget, LocalManagementLineBudget.isWithinBudget(actualLineCount))
        }
    }

    @Test
    fun `empty files and comment-only files use physical line counts`() {
        val empty = temporaryFolder.newFile("empty.kt")
        assertEquals(0, LocalManagementLineBudget.countPhysicalLines(empty))
        assertTrue(LocalManagementLineBudget.isWithinBudget(0))

        val comments = temporaryFolder.newFile("comments.kt")
        val commentLines = List(2_000) { index ->
            if (index % 2 == 0) "" else "// 注释"
        }
        comments.writeText(commentLines.joinToString("\n"), StandardCharsets.UTF_8)

        assertEquals(2_000, LocalManagementLineBudget.countPhysicalLines(comments))
        assertFalse(LocalManagementLineBudget.isWithinBudget(2_000))
    }

    @Test
    fun `reports a missing required facade instead of skipping it`() {
        val root = temporaryFolder.newFolder("missing-required")
        val report = LocalManagementLineBudget.verify(
            projectRoot = root,
            scope = LocalManagementFileScope(
                recursiveDirectories = emptyList(),
                requiredFiles = listOf("main/RequiredFacade.kt"),
                uiFilePrefixes = emptyList()
            )
        )

        assertTrue(report.inspectedFiles.isEmpty())
        assertTrue(report.violations.single().contains("缺少必须保留的文件：main/RequiredFacade.kt"))
    }

    @Test
    fun `discovers oversized files in a new nested protected directory`() {
        val root = temporaryFolder.newFolder("nested-source")
        val oversized = File(root, "source/new/ExtractedOwner.kt")
        oversized.parentFile?.mkdirs()
        writeLines(oversized, 2_000, "\n", false)

        val report = LocalManagementLineBudget.verify(
            projectRoot = root,
            scope = LocalManagementFileScope(
                recursiveDirectories = listOf("source"),
                requiredFiles = emptyList(),
                uiFilePrefixes = emptyList()
            )
        )

        assertEquals(listOf("source/new/ExtractedOwner.kt"), report.inspectedFiles)
        assertTrue(
            report.violations.single().contains(
                "source/new/ExtractedOwner.kt: 2000 个物理行，必须少于 2000"
            )
        )
    }

    private fun writeLines(
        file: File,
        lineCount: Int,
        separator: String,
        hasFinalNewline: Boolean
    ) {
        val body = List(lineCount) { "value" }.joinToString(separator)
        file.writeText(
            if (hasFinalNewline && lineCount > 0) body + separator else body,
            StandardCharsets.UTF_8
        )
    }

    private data class LineFixture(
        val name: String,
        val lineCount: Int,
        val separator: String,
        val hasFinalNewline: Boolean,
        val isWithinBudget: Boolean
    )
}
