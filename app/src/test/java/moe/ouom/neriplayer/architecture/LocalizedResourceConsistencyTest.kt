package moe.ouom.neriplayer.architecture

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class LocalizedResourceConsistencyTest {
    private val projectRoot = LocalManagementLineBudget.findProjectRoot(
        File(System.getProperty("user.dir") ?: ".")
    )
    private val resourceRoots = listOf(
        "app/src/main/res",
        "modules/common/src/main/res",
        "modules/playback/runtime/src/main/res",
        "modules/download/runtime/src/main/res"
    ).filter { File(projectRoot, "$it/values-en").isDirectory }

    @Test
    fun `default user facing text stays on the Chinese baseline`() {
        resourceRoots.forEach { path ->
            val defaults = readResources(path, "values")
            val chinese = readResources(path, "values-zh")
            assertEquals("$path 中文资源键", defaults.keys, chinese.keys)
            defaults.forEach { (key, resource) ->
                resource.forms.forEach { (quantity, text) ->
                    val translated = chinese.getValue(key).forms
                    assertEquals(
                        "$path $key $quantity 默认文案",
                        translated[quantity] ?: translated.getValue("other"),
                        text
                    )
                }
            }
        }
    }

    @Test
    fun `translations retain resource keys and compatible format arguments`() {
        resourceRoots.forEach { path ->
            val defaults = readResources(path, "values")
            listOf("values-zh", "values-en").forEach { qualifier ->
                val translated = readResources(path, qualifier)
                assertEquals("$path $qualifier 资源键", defaults.keys, translated.keys)
                defaults.forEach { (key, resource) ->
                    val localized = translated.getValue(key)
                    assertEquals("$path $key formatted", resource.formatted, localized.formatted)
                    if (resource.formatted) {
                        val defaultArguments = formatArguments(resource.forms.getValue("other"))
                        assertEquals(
                            "$path $qualifier $key other 格式参数",
                            defaultArguments,
                            formatArguments(localized.forms.getValue("other"))
                        )
                        val argumentTypes = mutableMapOf<Int, String>()
                        (resource.forms.values + localized.forms.values).forEach { text ->
                            formatArguments(text).forEach { (index, type) ->
                                val previous = argumentTypes.putIfAbsent(index, type)
                                assertTrue(
                                    "$path $qualifier $key 参数 $index 类型冲突",
                                    previous == null || previous == type
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `English app text only retains Chinese in language names`() {
        resourceRoots.forEach { path ->
            readResources(path, "values-en").forEach { (key, resource) ->
                if (key !in BILINGUAL_LANGUAGE_NAMES) {
                    resource.forms.forEach { (quantity, text) ->
                        assertTrue(
                            "$path $key $quantity 英文资源包含未翻译中文: $text",
                            !CHINESE_CHARACTER.containsMatchIn(text)
                        )
                    }
                }
            }
        }
    }

    private fun readResources(path: String, qualifier: String): Map<String, ResourceText> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val result = sortedMapOf<String, ResourceText>()
        File(projectRoot, "$path/$qualifier").listFiles().orEmpty()
            .filter { it.extension == "xml" }
            .sortedBy { it.name }
            .forEach { file ->
                val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as? Element ?: continue
                    if (element.tagName !in setOf("string", "plurals") ||
                        element.getAttribute("translatable") == "false"
                    ) continue
                    val key = "${element.tagName}/${element.getAttribute("name")}"
                    val forms = if (element.tagName == "string") {
                        mapOf("other" to element.textContent)
                    } else {
                        val items = element.getElementsByTagName("item")
                        buildMap {
                            for (itemIndex in 0 until items.length) {
                                val item = items.item(itemIndex) as Element
                                val quantity = item.getAttribute("quantity")
                                check(put(quantity, item.textContent) == null) {
                                    "$file $key 重复 quantity: $quantity"
                                }
                            }
                        }
                    }
                    check("other" in forms) { "$file $key 缺少 other 文案" }
                    check(result.put(key, ResourceText(forms, element.getAttribute("formatted") != "false")) == null) {
                        "$path $qualifier 资源键重复: $key"
                    }
                }
            }
        assertTrue("$path $qualifier 缺少文案", result.isNotEmpty())
        return result
    }

    private fun formatArguments(text: String): Map<Int, String> {
        var nextIndex = 0
        return buildMap {
            FORMAT_ARGUMENT.findAll(text).forEach { match ->
                val type = match.groupValues[2]
                if (type == "%" || type == "n") return@forEach
                val explicitIndex = match.groupValues[1].toIntOrNull()
                val index = explicitIndex ?: ++nextIndex
                val previous = put(index, type)
                check(previous == null || previous == type) { "格式参数 $index 类型冲突: $text" }
            }
        }
    }

    private data class ResourceText(val forms: Map<String, String>, val formatted: Boolean)

    private companion object {
        val FORMAT_ARGUMENT = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([a-zA-Z%])")
        val CHINESE_CHARACTER = Regex("\\p{IsHan}")
        val BILINGUAL_LANGUAGE_NAMES = setOf(
            "string/language_display_chinese",
            "string/language_display_english",
            "string/language_display_system"
        )
    }
}
