package com.github.fnunezkanut.bbcloud.ui.main

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class JiraTicketIdsTest {
    @Test
    fun `parse - should find ids in title and branch without duplicates`() {
        // GIVEN
        val title = "ABC-1 and XYZ-22: fix"
        val branch = "feature/abc-1-and-qwe-3"

        // WHEN
        val result = JiraTicketIds.parse(title = title, branch = branch)

        // THEN
        result shouldBe listOf("ABC-1", "XYZ-22", "QWE-3")
    }

    @Test
    fun `parse - should ignore lowercase in title and non-jira prefixes`() {
        // GIVEN
        val title = "abc-1 utf-8"
        val branch = "release/utf-8-sha-256"

        // WHEN
        val result = JiraTicketIds.parse(title = title, branch = branch)

        // THEN
        result shouldBe emptyList()
    }

    @Test
    fun `parse - should handle null and blank input`() {
        // WHEN
        val result = JiraTicketIds.parse(title = null, branch = " ")

        // THEN
        result shouldBe emptyList()
    }

    @Test
    fun `browseUrl - should build the atlassian link`() {
        // WHEN
        val url = JiraTicketLinks.browseUrl(workspace = "acme", ticketId = "ABC-1")

        // THEN
        url shouldBe "https://acme.atlassian.net/browse/ABC-1"
    }
}