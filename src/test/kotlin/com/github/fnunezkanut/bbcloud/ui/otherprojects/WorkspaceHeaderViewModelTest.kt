package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsCard
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class WorkspaceHeaderViewModelTest {
    @Test
    fun `showCurrentProject - shows Create new PR only for a found project`() {
        // GIVEN
        val viewModel = WorkspaceHeaderViewModel(onRefreshRequested = {})
        val found = CurrentProjectLookup.Found(workspace = "acme", repoSlug = "repo", repositoryWebUrl = "https://x", avatarUrl = null)
        val unavailable = CurrentProjectLookup.Unavailable(status = ProjectRepositoryStatus.NoLocalProjectPath)

        // WHEN / THEN
        viewModel.showCurrentProject(lookup = found)
        val whenFound = viewModel.state.value.isCreatePullRequestVisible
        viewModel.showCurrentProject(lookup = unavailable)
        val whenUnavailable = viewModel.state.value.isCreatePullRequestVisible
        viewModel.showCurrentProject(lookup = found)
        viewModel.showCurrentProject(lookup = null)
        val whenChecking = viewModel.state.value.isCreatePullRequestVisible
        listOf(whenFound, whenUnavailable, whenChecking) shouldBe listOf(true, false, false)
    }

    @Test
    fun `onCreatePullRequestClicked - invokes the callback`() {
        // GIVEN
        var clicks = 0
        val viewModel = WorkspaceHeaderViewModel(onRefreshRequested = {}, onCreatePullRequestRequested = { clicks++ })

        // WHEN
        viewModel.onCreatePullRequestClicked()

        // THEN
        clicks shouldBe 1
    }

    @Nested
    inner class TabOpenCount {
        @Test
        fun `tabOpenCount - hides the counter on Hidden and Error`() {
            // GIVEN / WHEN
            val hidden = tabOpenCount(openCount = 5, card = PullRequestsCard.Hidden, previous = 3)
            val error = tabOpenCount(openCount = 5, card = PullRequestsCard.Error, previous = 3)

            // THEN
            listOf(hidden, error) shouldBe listOf(null, null)
        }

        @Test
        fun `tabOpenCount - keeps the previous count while loading`() {
            // GIVEN / WHEN
            val withPrevious = tabOpenCount(openCount = 0, card = PullRequestsCard.Loading, previous = 4)
            val withoutPrevious = tabOpenCount(openCount = 0, card = PullRequestsCard.Loading, previous = null)

            // THEN
            listOf(withPrevious, withoutPrevious) shouldBe listOf(4, null)
        }

        @Test
        fun `tabOpenCount - returns the open count for the listing cards`() {
            // GIVEN
            val cards = listOf(
                PullRequestsCard.Listing,
                PullRequestsCard.Empty,
                PullRequestsCard.FilteredEmpty,
                PullRequestsCard.CurrentProjectUnavailable
            )

            // WHEN
            val counts = cards.map { card -> tabOpenCount(openCount = 7, card = card, previous = 1) }

            // THEN
            counts shouldBe listOf(7, 7, 7, 7)
        }
    }
}