package com.github.jschbe.simplempdplayer.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.*
import org.junit.Test

class LocalizationResourcesTest {
    private val resources = File("src/main/res")
    private val tags = AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.map { it.tag }
    private fun parse(file: File): List<Element> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }
    private fun catalog(tag: String) = parse(File(resources, "${if (tag == "en") "values" else "values-$tag"}/strings.xml"))
        .also { elements -> assertEquals(elements.size, elements.map { it.getAttribute("name") }.distinct().size) }
        .associateBy { it.getAttribute("name") }
    private fun signature(text: String) = Regex("%([1-9][0-9]*\\$)?[-0-9.]*[a-zA-Z%]")
        .findAll(text).map { it.value }.sorted().toList()
    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    @Test fun allCatalogsHaveMatchingTypesArgumentsAndRequiredPluralBranches() {
        val english = catalog("en")
        tags.forEach { tag ->
            val localized = catalog(tag)
            assertEquals(tag, english.keys, localized.keys)
            english.forEach { (name, source) ->
                val target = localized.getValue(name)
                assertEquals("$tag/$name", source.tagName, target.tagName)
                val variants = if (target.tagName == "plurals") children(target) else listOf(target)
                val template = if (source.tagName == "plurals") children(source).first().textContent else source.textContent
                variants.forEach { assertEquals("$tag/$name", signature(template), signature(it.textContent)) }
            }
            val quantities = children(localized.getValue("timer_duration_minutes")).map { it.getAttribute("quantity") }.toSet()
            assertTrue(tag, quantities.containsAll(if (tag in listOf("ru", "uk")) setOf("one", "few", "many", "other") else setOf("one", "other")))
            assertEquals("SimpleMPDPlayer", localized.getValue("app_name").textContent)
            assertTrue(localized.getValue("timer_minutes_error").textContent.contains("1440"))
            assertTrue(localized.getValue("timer_minutes_error").textContent.contains("1"))
        }
    }

    @Test fun localeDeclarationAndLanguageModelHaveExactlySevenLanguages() {
        val androidName = "android:name"
        assertEquals(tags, parse(File(resources, "xml/locales_config.xml")).map { it.getAttribute(androidName) })
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("resourceConfigurations += listOf(" + tags.joinToString(", ") { "\"$it\"" } + ")"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(""))
        tags.forEach { assertEquals(it, AppLanguage.fromTags(it).tag) }
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTags("de-CH,en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("ja-JP"))
    }

    @Test fun canonicalCsvMatchesEveryEnglishForm() {
        val expected = catalog("en").flatMap { (name, element) ->
            if (element.tagName == "plurals") children(element).map { "$name[${it.getAttribute("quantity")}]" to it.textContent }
            else listOf(name to element.textContent)
        }.associate { (name, text) -> name to text.replace("\\\"", "\"").replace("\\'", "'").removeSurrounding("\"") }
        val lines = File("../docs/strings-en.csv").readLines()
        assertEquals("key,english,context", lines.first())
        val rows = lines.drop(1).map { line ->
            line.split(Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"))
                .map { it.removeSurrounding("\"").replace("\"\"", "\"") }
                .also { assertEquals(3, it.size); assertTrue(it[2].isNotBlank()) }
        }
        assertEquals(rows.size, rows.map { it[0] }.distinct().size)
        assertEquals(expected, rows.associate { it[0] to it[1] })
    }
}
