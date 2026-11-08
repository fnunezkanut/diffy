package com.github.fnunezkanut.bbcloud.toolwindow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyToolWindowTabTest {
    @Test
    fun `entries - should list the six tabs in header order`() {
        // GIVEN
        val expected = listOf(
            DiffyToolWindowTab.ProjectPrs,
            DiffyToolWindowTab.WorkspacePrs,
            DiffyToolWindowTab.WorkspaceWatchedProjects,
            DiffyToolWindowTab.RecentProjects,
            DiffyToolWindowTab.Notifications,
            DiffyToolWindowTab.Settings
        )

        // WHEN
        val actual = DiffyToolWindowTab.entries

        // THEN
        actual shouldBe expected
    }
}