package com.github.fnunezkanut.bbcloud.git

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class GitNameOnlyOutputTest {
    @Test
    fun `parse - should split NUL separated paths`() {
        // GIVEN
        val output = "src/A.kt\u0000src/B.kt\u0000"

        // WHEN
        val paths = GitNameOnlyOutput.parse(output = output)

        // THEN
        paths shouldBe setOf("src/A.kt", "src/B.kt")
    }

    @Test
    fun `parse - should ignore a trailing NUL`() {
        GitNameOnlyOutput.parse(output = "a.txt\u0000") shouldBe setOf("a.txt")
    }

    @Test
    fun `parse - should ignore the echoed command line`() {
        // GIVEN
        val output = "$ git diff --name-only -z a b\nsrc/A.kt\u0000"

        // WHEN
        val paths = GitNameOnlyOutput.parse(output = output)

        // THEN
        paths shouldBe setOf("src/A.kt")
    }

    @Test
    fun `parse - should return an empty set for empty output`() {
        GitNameOnlyOutput.parse(output = "").shouldBeEmpty()
    }

    @Test
    fun `parse - should keep spaces inside paths`() {
        GitNameOnlyOutput.parse(output = "my dir/a file.kt\u0000") shouldBe setOf("my dir/a file.kt")
    }
}