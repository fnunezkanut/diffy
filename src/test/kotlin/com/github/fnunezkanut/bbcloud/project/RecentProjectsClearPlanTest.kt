package com.github.fnunezkanut.bbcloud.project

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class RecentProjectsClearPlanTest {
    private fun group(name: String, vararg paths: String): RecentProjectGroupSnapshot {
        return RecentProjectGroupSnapshot(name = name, projectPaths = paths.toList(), isExpanded = true)
    }

    @Test
    fun `planRecentProjectsClear - should remove every path and every group when nothing is open`() {
        // GIVEN
        val snapshot = RecentProjectsSnapshot(
            recentPaths = listOf("/a", "/b"),
            groups = listOf(group("G", "/b", "/c"))
        )

        // WHEN
        val plan = planRecentProjectsClear(snapshot = snapshot, openPathKeys = emptySet())

        // THEN
        assertSoftly {
            plan.paths shouldContainExactlyInAnyOrder listOf("/a", "/b", "/c")
            plan.emptyGroupNames shouldBe listOf("G")
        }
    }

    @Test
    fun `planRecentProjectsClear - should keep open projects`() {
        // GIVEN
        val snapshot = RecentProjectsSnapshot(recentPaths = listOf("/a", "/b"), groups = emptyList())

        // WHEN
        val plan = planRecentProjectsClear(snapshot = snapshot, openPathKeys = setOf("/a"))

        // THEN
        plan.paths shouldBe listOf("/b")
    }

    @Test
    fun `planRecentProjectsClear - should keep a group that still holds an open project`() {
        // GIVEN
        val snapshot = RecentProjectsSnapshot(
            recentPaths = listOf("/a", "/b"),
            groups = listOf(group("Open", "/a", "/b"), group("Closed", "/c"))
        )

        // WHEN
        val plan = planRecentProjectsClear(snapshot = snapshot, openPathKeys = setOf("/a"))

        // THEN
        assertSoftly {
            plan.paths shouldContainExactlyInAnyOrder listOf("/b", "/c")
            plan.emptyGroupNames shouldBe listOf("Closed")
        }
    }

    @Test
    fun `planRecentProjectsClear - should include group members that are not in the recent paths`() {
        // GIVEN
        val snapshot = RecentProjectsSnapshot(recentPaths = emptyList(), groups = listOf(group("G", "/only-in-group")))

        // WHEN
        val plan = planRecentProjectsClear(snapshot = snapshot, openPathKeys = emptySet())

        // THEN
        plan.paths shouldBe listOf("/only-in-group")
    }

    @Test
    fun `planRecentProjectsClear - should match open paths by normalized folder key`() {
        // GIVEN
        val snapshot = RecentProjectsSnapshot(recentPaths = listOf("/work/a/", "/work/b/../c"), groups = emptyList())

        // WHEN
        val plan = planRecentProjectsClear(snapshot = snapshot, openPathKeys = setOf("/work/a", "/work/c"))

        // THEN
        plan.paths shouldBe emptyList()
    }
}