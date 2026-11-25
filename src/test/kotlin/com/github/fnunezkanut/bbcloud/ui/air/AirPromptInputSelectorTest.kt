package com.github.fnunezkanut.bbcloud.ui.air

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class AirPromptInputSelectorTest {
    private fun candidate(
        input: String,
        belongsToProject: Boolean = true,
        isEmpty: Boolean = true,
        isInChatToolWindow: Boolean = true
    ): AirPromptInputCandidate<String> {
        return AirPromptInputCandidate(
            input = input,
            belongsToProject = belongsToProject,
            isEmpty = isEmpty,
            isInChatToolWindow = isInChatToolWindow
        )
    }

    @Nested
    inner class PreferEmpty {
        @Test
        fun `select - should return null when only an input of another project exists`() {
            // GIVEN
            val candidates = listOf(candidate(input = "other", belongsToProject = false))

            // WHEN
            val result = AirPromptInputSelector.select(candidates = candidates, preferEmpty = true)

            // THEN
            result shouldBe null
        }

        @Test
        fun `select - should return non-empty input of this project over empty input of another project`() {
            // GIVEN
            val candidates = listOf(
                candidate(input = "other-empty", belongsToProject = false, isEmpty = true),
                candidate(input = "mine-draft", isEmpty = false)
            )

            // WHEN
            val result = AirPromptInputSelector.select(candidates = candidates, preferEmpty = true)

            // THEN
            result shouldBe "mine-draft"
        }

        @Test
        fun `select - should return the empty input when this project has empty and non-empty inputs`() {
            // GIVEN
            val candidates = listOf(
                candidate(input = "mine-draft", isEmpty = false),
                candidate(input = "mine-empty", isEmpty = true)
            )

            // WHEN
            val result = AirPromptInputSelector.select(candidates = candidates, preferEmpty = true)

            // THEN
            result shouldBe "mine-empty"
        }

        @Test
        fun `select - should return the tool window input when palette and tool window inputs are both empty`() {
            // GIVEN
            val candidates = listOf(
                candidate(input = "palette", isInChatToolWindow = false),
                candidate(input = "tool-window", isInChatToolWindow = true)
            )

            // WHEN
            val result = AirPromptInputSelector.select(candidates = candidates, preferEmpty = true)

            // THEN
            result shouldBe "tool-window"
        }
    }

    @Nested
    inner class DoNotPreferEmpty {
        @Test
        fun `select - should return the first tool window input in order when several inputs of this project exist`() {
            // GIVEN
            val candidates = listOf(
                candidate(input = "palette", isInChatToolWindow = false),
                candidate(input = "tool-window-first", isEmpty = false),
                candidate(input = "tool-window-second", isEmpty = true)
            )

            // WHEN
            val result = AirPromptInputSelector.select(candidates = candidates, preferEmpty = false)

            // THEN
            result shouldBe "tool-window-first"
        }

        @Test
        fun `select - should ignore inputs of other projects`() {
            // GIVEN
            val candidates = listOf(
                candidate(input = "other", belongsToProject = false),
                candidate(input = "mine")
            )

            // WHEN
            val result = AirPromptInputSelector.select(candidates = candidates, preferEmpty = false)

            // THEN
            result shouldBe "mine"
        }
    }

    @Test
    fun `select - should return null for an empty candidate list`() {
        // GIVEN
        val candidates = emptyList<AirPromptInputCandidate<String>>()

        // WHEN
        val withPreferEmpty = AirPromptInputSelector.select(candidates = candidates, preferEmpty = true)
        val withoutPreferEmpty = AirPromptInputSelector.select(candidates = candidates, preferEmpty = false)

        // THEN
        assertSoftly {
            withPreferEmpty shouldBe null
            withoutPreferEmpty shouldBe null
        }
    }
}