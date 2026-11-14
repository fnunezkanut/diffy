package com.github.fnunezkanut.bbcloud.toolwindow

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyToolWindowTabTitleTest {
    private fun format(title: String = "Pull Requests", count: Int?): String {
        return DiffyToolWindowTabTitle.format(title = title, count = count, counterColorHex = "#808080", gapPx = 8)
    }

    @Test
    fun `format - returns the plain title for a null count`() {
        format(count = null) shouldBe "Pull Requests"
    }

    @Test
    fun `format - returns the plain title for zero and negative counts`() {
        listOf(format(count = 0), format(count = -1)) shouldBe listOf("Pull Requests", "Pull Requests")
    }

    @Test
    fun `format - builds html with title, gap, color, and count`() {
        // GIVEN / WHEN
        val result = format(count = 12)

        // THEN
        assertSoftly {
            result shouldStartWith "<html>"
            result shouldContain "Pull Requests"
            result shouldContain "width='8'"
            result shouldContain "#808080"
            result shouldContain ">12</font>"
        }
    }

    @Test
    fun `format - caps the count at 99+`() {
        assertSoftly {
            format(count = 99) shouldContain ">99</font>"
            format(count = 100) shouldContain ">99+</font>"
        }
    }

    @Test
    fun `format - escapes markup in the title`() {
        format(title = "A & B <c>", count = 1) shouldContain "A &amp; B &lt;c&gt;"
    }

    @Test
    fun `format - contains no newline characters`() {
        format(count = 3) shouldNotContain "\n"
    }
}