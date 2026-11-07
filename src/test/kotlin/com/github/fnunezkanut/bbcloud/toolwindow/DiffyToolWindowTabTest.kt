package com.github.fnunezkanut.bbcloud.toolwindow

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyToolWindowTabTest {
    @Test
    fun `entries - should list the five tabs in header order`() {
        // GIVEN
        val expected = listOf(
            DiffyToolWindowTab.ProjectPrs,
            DiffyToolWindowTab.WorkspacePrs,
            DiffyToolWindowTab.WorkspaceWatchedProjects,
            DiffyToolWindowTab.RecentProjects,
            DiffyToolWindowTab.Settings
        )

        // WHEN
        val actual = DiffyToolWindowTab.entries

        // THEN
        actual shouldBe expected
    }
}