package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.DiffyBundle
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class SetupFooterTextTest {
    private val disclaimer = DiffyBundle.message("setup.disclaimer")
    private val separator = "\u2022"

    @Test
    fun `build - should include the resolved version`() {
        // GIVEN
        val version = "0.1.3"

        // WHEN
        val result = SetupFooterText.build(version)

        // THEN
        assertSoftly {
            result shouldContain "Diffy - Bitbucket PR Review plugin: $version"
            result shouldContain "$separator $disclaimer"
        }
    }

    @Test
    fun `build - should omit the version suffix when it is unknown`() {
        // GIVEN
        val version = null

        // WHEN
        val result = SetupFooterText.build(version)

        // THEN
        assertSoftly {
            // The ": <version>" suffix is dropped; the disclaimer keeps its own colon.
            result shouldNotContain "Diffy - Bitbucket PR Review plugin:"
            result shouldNotContain "Unknown"
            result shouldContain "Diffy - Bitbucket PR Review plugin $separator $disclaimer"
        }
    }

    @Test
    fun `build - should omit the version suffix when it is blank`() {
        // GIVEN
        val version = "   "

        // WHEN
        val result = SetupFooterText.build(version)

        // THEN
        result shouldContain "Diffy - Bitbucket PR Review plugin $separator $disclaimer"
    }

    @Test
    fun `label - should be the plugin name without version or disclaimer`() {
        // WHEN
        val result = SetupFooterText.label()

        // THEN
        assertSoftly {
            result shouldBe "Diffy - Bitbucket PR Review plugin"
            result shouldNotContain disclaimer
            result shouldNotContain separator
        }
    }

    @Test
    fun `build - should start with the label so the screen can split it off`() {
        // GIVEN
        val version = "0.1.3"

        // WHEN
        val result = SetupFooterText.build(version)

        // THEN
        // The screen renders label() as a link and the remainder as text, so build() must be label + suffix.
        assertSoftly {
            result.startsWith(SetupFooterText.label()) shouldBe true
            result.removePrefix(SetupFooterText.label()) shouldBe SetupFooterText.buildSuffix(version)
        }
    }

    @Test
    fun `buildSuffix - should include the version and disclaimer`() {
        // GIVEN
        val version = "0.1.3"

        // WHEN
        val result = SetupFooterText.buildSuffix(version)

        // THEN
        assertSoftly {
            result shouldContain ": $version"
            result shouldContain "$separator $disclaimer"
            result shouldNotContain "Diffy - Bitbucket PR Review plugin"
        }
    }

    @Test
    fun `buildSuffix - should omit the version when it is null`() {
        // WHEN
        val result = SetupFooterText.buildSuffix(null)

        // THEN
        assertSoftly {
            result shouldContain "$separator $disclaimer"
            result shouldNotContain "Unknown"
        }
    }
}