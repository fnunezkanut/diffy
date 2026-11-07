package com.github.fnunezkanut.bbcloud.toolwindow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyToolWindowTabResolverTest {
    @Test
    fun `initialTab - should open the current project PRs tab when configured`() {
        // GIVEN
        val isConfigured = true

        // WHEN
        val result = DiffyToolWindowTabResolver.initialTab(isConfigured = isConfigured)

        // THEN
        result shouldBe DiffyToolWindowTab.ProjectPrs
    }

    @Test
    fun `initialTab - should open the settings tab when not configured`() {
        // GIVEN
        val isConfigured = false

        // WHEN
        val result = DiffyToolWindowTabResolver.initialTab(isConfigured = isConfigured)

        // THEN
        result shouldBe DiffyToolWindowTab.Settings
    }

    @Test
    fun `initialTab - should open the current project PRs tab while the configuration check is unresolved`() {
        // GIVEN
        val isConfigured = null

        // WHEN
        val result = DiffyToolWindowTabResolver.initialTab(isConfigured = isConfigured)

        // THEN
        result shouldBe DiffyToolWindowTab.ProjectPrs
    }
}