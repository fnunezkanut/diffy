package com.github.fnunezkanut.bbcloud.cache

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PullRequestListingIdsTest {
    @Test
    fun `project - should build a lowercase id from the workspace and repository`() {
        PullRequestListingIds.project(workspace = "AcmeCo", repoSlug = "Payments") shouldBe "project:acmeco/payments"
    }

    @Test
    fun `workspaceAuthored - should build a lowercase id from the workspace`() {
        PullRequestListingIds.workspaceAuthored(workspace = " AcmeCo ") shouldBe "workspace-authored:acmeco"
    }

    @Test
    fun `workspaceReviewing - should build a lowercase id from the workspace and repository`() {
        PullRequestListingIds.workspaceReviewing(workspace = "AcmeCo", repoSlug = "Payments") shouldBe
            "workspace-reviewing:acmeco/payments"
    }
}