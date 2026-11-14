package com.github.fnunezkanut.bbcloud.plugin

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards R19: every production construction of a cache-aware service passes `pullRequestCache =`. Without it the
 * service silently uses the no-op store, and the cache would never be read or written in the IDE.
 */
@Tag("unit")
class ProductionCacheWiringTest {
    private val cacheAwareServices = listOf(
        "DiffyOpenPullRequestService",
        "DiffyAuthoredPullRequestService",
        "DiffyWatchedProjectsPullRequestService",
        "DiffyConfigurationService"
    )

    @Test
    fun `src main - every construction of a cache-aware service passes the pull request cache`() {
        // GIVEN the production sources
        val sources = File("src/main/kotlin").walkTopDown().filter { file -> file.extension == "kt" }.toList()
        check(sources.isNotEmpty()) { "No production sources found from ${File(".").absolutePath}" }

        // WHEN every construction call is read
        val unwired = mutableListOf<String>()
        var constructions = 0
        for (file in sources) {
            val text = file.readText()
            for (service in cacheAwareServices) {
                for (callStart in constructionStarts(text = text, service = service)) {
                    constructions++
                    val call = argumentsOf(text = text, openParenIndex = callStart)
                    if (!call.contains("pullRequestCache =")) {
                        unwired += "${file.name}: $service"
                    }
                }
            }
        }

        // THEN there are constructions to check, and none is unwired
        // Nine constructions today: the tool window builds three services, the configuration service is built once.
        constructions shouldBe 9
        unwired.shouldBeEmpty()
    }

    /** Index of the `(` after each `Service(` that is a construction and not a class declaration. */
    private fun constructionStarts(text: String, service: String): List<Int> {
        val needle = "$service("
        val starts = mutableListOf<Int>()
        var index = text.indexOf(needle)
        while (index >= 0) {
            val before = text.substring(0, index).trimEnd()
            val isDeclaration = before.endsWith("class") || before.endsWith("is") || before.endsWith("import")
            val isImport = text.lastIndexOf('\n', index).let { lineStart ->
                text.substring(lineStart + 1, index).startsWith("import ")
            }
            if (!isDeclaration && !isImport) {
                starts += index + service.length
            }
            index = text.indexOf(needle, startIndex = index + needle.length)
        }
        return starts
    }

    /** The text between the `(` at [openParenIndex] and its matching `)`. */
    private fun argumentsOf(text: String, openParenIndex: Int): String {
        var depth = 0
        for (index in openParenIndex until text.length) {
            when (text[index]) {
                '(' -> {
                    depth++
                }
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(openParenIndex, index + 1)
                    }
                }
            }
        }
        return text.substring(openParenIndex)
    }
}