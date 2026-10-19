package com.gpsupdater.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各語系的字串資源必須和預設語系（英文）有相同的字串鍵，而且每個字串的格式佔位符（%1$s、%2$d…）一致，
 * 否則缺字會顯示成英文，佔位符對不上則會在執行時造成格式化例外。
 */
class StringResourcesParityTest {
    private val resDir: File = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private data class Entry(val name: String, val placeholders: List<String>)

    private val entryRegex = Regex("""<(string|plurals)\s+name="([^"]+)"([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholderRegex = Regex("""%(?:\d+\$)?(?:\.\d+)?[sdf]""")

    private fun parse(file: File): Map<String, Entry> {
        val text = file.readText(Charsets.UTF_8)
        return entryRegex.findAll(text)
            .filter { !it.groupValues[3].contains("translatable=\"false\"") }
            .associate { match ->
                val name = match.groupValues[2]
                // plurals 的每個 <item> 都要有相同的佔位符集合，這裡把整段內容的佔位符去重後比較
                name to Entry(name, placeholderRegex.findAll(match.groupValues[4]).map { it.value }.toSortedSet().toList())
            }
    }

    private val base = parse(File(resDir, "values/strings.xml"))
    private val locales = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }
        .orEmpty().sortedBy { it.name }

    @Test
    fun `there are translations to check`() {
        assertTrue("應該至少有一個翻譯語系", locales.isNotEmpty())
        assertTrue("預設語系應該有字串", base.size > 100)
    }

    @Test
    fun `every locale has exactly the same string keys as the default`() {
        val problems = mutableListOf<String>()
        locales.forEach { dir ->
            val keys = parse(File(dir, "strings.xml")).keys
            (base.keys - keys).sorted().forEach { problems += "${dir.name} 缺少：$it" }
            (keys - base.keys).sorted().forEach { problems += "${dir.name} 多出（預設語系沒有）：$it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `placeholders match the default in every locale`() {
        val problems = mutableListOf<String>()
        locales.forEach { dir ->
            val strings = parse(File(dir, "strings.xml"))
            strings.forEach { (name, entry) ->
                val expected = base[name]?.placeholders ?: return@forEach
                if (entry.placeholders != expected) {
                    problems += "${dir.name} / $name：預設 $expected，翻譯 ${entry.placeholders}"
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
