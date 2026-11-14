package com.github.fnunezkanut.bbcloud.toolwindow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyToolWindowTabResolverTest {
    @Test
    fun `initialTab - should open the Pull Requests tab when it was selected last`() {
        // GIVEN
        val lastSelectedTab = DiffyToolWindowTab.PullRequests

        // WHEN
        val result = DiffyToolWindowTabResolver.initialTab(lastSelectedTab = lastSelectedTab)

        // THEN
        result shouldBe DiffyToolWindowTab.PullRequests
    }

    @Test
    fun `initialTab - should open the last selected tab`() {
        // GIVEN
        val lastSelectedTab = DiffyToolWindowTab.Notifications

        // WHEN
        val result = DiffyToolWindowTabResolver.initialTab(lastSelectedTab = lastSelectedTab)

        // THEN
        result shouldBe DiffyToolWindowTab.Notifications
    }

    @Test
    fun `initialTab - should open the Pull Requests tab when the Settings tab was selected last`() {
        // GIVEN
        val lastSelectedTab = DiffyToolWindowTab.Settings

        // WHEN
        val result = DiffyToolWindowTabResolver.initialTab(lastSelectedTab = lastSelectedTab)

        // THEN
        result shouldBe DiffyToolWindowTab.PullRequests
    }
}