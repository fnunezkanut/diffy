package com.github.fnunezkanut.bbcloud.ui.setup

import com.github.fnunezkanut.bbcloud.DiffyBundle
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class SetupFooterTextTest {
    private val disclaimer = DiffyBundle.message("setup.disclaimer")

    @Test
    fun `build - should include the resolved version`() {
        // GIVEN
        val version = "0.1.3"

        // WHEN
        val result = SetupFooterText.build(version)

        // THEN
        result shouldBe "Diffy - Bitbucket PR Review plugin: 0.1.3"
    }

    @Test
    fun `build - should not contain the disclaimer`() {
        // WHEN
        val result = SetupFooterText.build("0.1.3")

        // THEN
        result shouldNotContain disclaimer
    }

    @Test
    fun `build - should be only the label when the version is null`() {
        // WHEN
        val result = SetupFooterText.build(null)

        // THEN
        result shouldBe SetupFooterText.label()
    }

    @Test
    fun `build - should be only the label when the version is blank`() {
        // WHEN
        val result = SetupFooterText.build("   ")

        // THEN
        result shouldBe SetupFooterText.label()
    }

    @Test
    fun `label - should be the plugin name without version or disclaimer`() {
        // WHEN
        val result = SetupFooterText.label()

        // THEN
        assertSoftly {
            result shouldBe "Diffy - Bitbucket PR Review plugin"
            result shouldNotContain disclaimer
        }
    }

    @Test
    fun `build - should start with the label so the screen can split it off`() {
        // WHEN
        val result = SetupFooterText.build("0.1.3")

        // THEN
        assertSoftly {
            result.startsWith(SetupFooterText.label()) shouldBe true
            result.removePrefix(SetupFooterText.label()) shouldBe SetupFooterText.buildVersionSuffix("0.1.3")
        }
    }

    @Test
    fun `disclaimer - should be the bundle disclaimer without a separator`() {
        // WHEN
        val result = SetupFooterText.disclaimer()

        // THEN
        assertSoftly {
            result shouldBe disclaimer
            result shouldNotContain "\u2022"
        }
    }
}