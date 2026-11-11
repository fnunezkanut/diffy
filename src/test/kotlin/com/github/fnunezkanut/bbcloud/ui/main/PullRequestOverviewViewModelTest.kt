package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestDetails
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Tag("unit")
class PullRequestOverviewViewModelTest {
    @Test
    fun `showReviewers - should render a present description`() {
        // GIVEN
        val viewModel = createViewModel()
        val scope = CoroutineScope(SupervisorJob())
        val details = pullRequestDetails(description = "## Description")

        try {
            // WHEN
            viewModel.showReviewers(details = details, coroutineScope = scope)

            // THEN
            assertSoftly(viewModel.state.value.description) {
                markdown shouldBe "## Description"
                rawMarkdown shouldBe "## Description"
                emptyMessage.shouldBeNull()
                isEditable shouldBe true
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `description editing - should open and cancel without changing raw Markdown`() {
        // GIVEN
        val viewModel = createViewModel()
        val scope = CoroutineScope(SupervisorJob())
        viewModel.showReviewers(details = pullRequestDetails(description = "Original"), coroutineScope = scope)

        try {
            // WHEN
            viewModel.onEditDescription()

            // THEN
            viewModel.state.value.description.isEditing shouldBe true
            viewModel.descriptionInput().initialText shouldBe "Original"

            // WHEN
            viewModel.onCancelDescriptionEdit()

            // THEN
            viewModel.state.value.description.isEditing shouldBe false
            viewModel.state.value.description.rawMarkdown shouldBe "Original"
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `showReviewers - should hide description editing for a closed pull request`() {
        // GIVEN
        val viewModel = createViewModel()
        val scope = CoroutineScope(SupervisorJob())
        val details = pullRequestDetails(description = "Closed", state = PullRequestState.Merged)

        try {
            // WHEN
            viewModel.showReviewers(details = details, coroutineScope = scope)

            // THEN
            viewModel.state.value.description.isEditable shouldBe false
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `showReviewers and reset - should render empty state and clear description`() {
        // GIVEN
        val viewModel = createViewModel()
        val scope = CoroutineScope(SupervisorJob())
        val details = pullRequestDetails(description = null)

        try {
            // WHEN
            viewModel.showReviewers(details = details, coroutineScope = scope)

            // THEN
            assertSoftly(viewModel.state.value.description) {
                markdown.shouldBeNull()
                rawMarkdown shouldBe ""
                emptyMessage shouldBe "No description provided."
                isEditable shouldBe true
            }

            // WHEN
            viewModel.reset()

            // THEN
            assertSoftly(viewModel.state.value.description) {
                markdown.shouldBeNull()
                rawMarkdown shouldBe ""
                emptyMessage shouldBe "No description provided."
                isEditable shouldBe false
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `showReviewers - should render ticket links from title and branch`() {
        // GIVEN
        val viewModel = createViewModel()
        val scope = CoroutineScope(SupervisorJob())
        val details = pullRequestDetails(description = null, title = "ABC-123 fix", sourceBranch = "feature/def-45-thing")

        try {
            // WHEN
            viewModel.showReviewers(details = details, coroutineScope = scope)

            // THEN
            viewModel.state.value.tickets.rows shouldBe listOf(
                TicketRowState(id = "ABC-123", url = "https://acme.atlassian.net/browse/ABC-123"),
                TicketRowState(id = "DEF-45", url = "https://acme.atlassian.net/browse/DEF-45")
            )

            // WHEN
            viewModel.onToggleTickets()

            // THEN
            viewModel.state.value.tickets.isExpanded shouldBe false

            // WHEN
            viewModel.reset()

            // THEN
            viewModel.state.value.tickets.rows shouldBe emptyList()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `showReviewers - should hide tickets without a match or workspace`() {
        // GIVEN
        val scope = CoroutineScope(SupervisorJob())
        val noMatch = createViewModel()
        val noWorkspace = createViewModel(workspace = "")

        try {
            // WHEN
            noMatch.showReviewers(details = pullRequestDetails(description = null), coroutineScope = scope)
            noWorkspace.showReviewers(details = pullRequestDetails(description = null, title = "ABC-1"), coroutineScope = scope)

            // THEN
            noMatch.state.value.tickets.rows shouldBe emptyList()
            noWorkspace.state.value.tickets.rows shouldBe emptyList()
        } finally {
            scope.cancel()
        }
    }

    @Nested
    inner class ExplainWithAi {
        @Test
        fun `showReviewers - should show the button for an open pull request when Air is available`() {
            // GIVEN
            val viewModel = createViewModel(isAirAvailable = { true })
            val scope = CoroutineScope(SupervisorJob())

            try {
                // WHEN
                viewModel.showReviewers(details = pullRequestDetails(description = "d"), coroutineScope = scope)

                // THEN
                viewModel.state.value.description.isExplainWithAiVisible shouldBe true
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `showReviewers - should hide the button for merged and declined pull requests`() {
            // GIVEN
            val viewModel = createViewModel(isAirAvailable = { true })
            val scope = CoroutineScope(SupervisorJob())

            try {
                // WHEN / THEN
                for (closed in listOf(PullRequestState.Merged, PullRequestState.Declined)) {
                    viewModel.showReviewers(details = pullRequestDetails(description = "d", state = closed), coroutineScope = scope)
                    viewModel.state.value.description.isExplainWithAiVisible shouldBe false
                }
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `showReviewers - should hide the button when Air is not available`() {
            // GIVEN
            val viewModel = createViewModel(isAirAvailable = { false })
            val scope = CoroutineScope(SupervisorJob())

            try {
                // WHEN
                viewModel.showReviewers(details = pullRequestDetails(description = "d"), coroutineScope = scope)

                // THEN
                viewModel.state.value.description.isExplainWithAiVisible shouldBe false
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `button - should be hidden before showReviewers and after reset`() {
            // GIVEN
            val viewModel = createViewModel(isAirAvailable = { true })
            val scope = CoroutineScope(SupervisorJob())

            try {
                // THEN
                viewModel.state.value.description.isExplainWithAiVisible shouldBe false

                // WHEN
                viewModel.showReviewers(details = pullRequestDetails(description = "d"), coroutineScope = scope)
                viewModel.reset()

                // THEN
                viewModel.state.value.description.isExplainWithAiVisible shouldBe false
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `onExplainWithAi - should pass the summary fields and the raw description`() {
            // GIVEN
            val requests = mutableListOf<ExplainPullRequestRequest>()
            val viewModel = createViewModel(isAirAvailable = { true }, onExplainWithAi = { request -> requests += request })
            val scope = CoroutineScope(SupervisorJob())

            try {
                viewModel.showReviewers(details = pullRequestDetails(description = "## Raw"), coroutineScope = scope)

                // WHEN
                viewModel.onExplainWithAi()

                // THEN
                requests.single() shouldBe ExplainPullRequestRequest(
                    pullRequestId = 273,
                    title = "Test pull request",
                    webUrl = "https://bitbucket.org/workspace/repository/pull-requests/273",
                    sourceBranch = "feature",
                    destinationBranch = "main",
                    description = "## Raw"
                )
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `onExplainWithAi - should not call the callback before the details load`() {
            // GIVEN
            var calls = 0
            val viewModel = createViewModel(onExplainWithAi = { calls++ })

            // WHEN
            viewModel.onExplainWithAi()

            // THEN
            calls shouldBe 0
        }

        @Test
        fun `onExplainWithAi - should ignore a repeated click within two seconds`() {
            // GIVEN
            var calls = 0
            var now = Instant.parse("2026-11-10T10:00:00Z")
            val viewModel = createViewModel(isAirAvailable = { true }, onExplainWithAi = { calls++ }, clock = { now })
            val scope = CoroutineScope(SupervisorJob())

            try {
                viewModel.showReviewers(details = pullRequestDetails(description = "d"), coroutineScope = scope)

                // WHEN
                viewModel.onExplainWithAi()
                now = now.plus(1.seconds)
                viewModel.onExplainWithAi()

                // THEN
                calls shouldBe 1

                // WHEN
                now = now.plus(2.seconds)
                viewModel.onExplainWithAi()

                // THEN
                calls shouldBe 2
            } finally {
                scope.cancel()
            }
        }
    }

    private fun createViewModel(
        workspace: String = "acme",
        isAirAvailable: () -> Boolean = { false },
        onExplainWithAi: (ExplainPullRequestRequest) -> Unit = {},
        clock: () -> Instant = { Instant.parse("2026-11-10T10:00:00Z") }
    ): PullRequestOverviewViewModel {
        return PullRequestOverviewViewModel(
            reviewerEditor = mockk(relaxed = true),
            taskEditor = mockk(relaxed = true),
            descriptionEditor = mockk(relaxed = true),
            workspaceSlug = { workspace },
            openSearchPopup = {},
            isAirAvailable = isAirAvailable,
            onExplainWithAi = onExplainWithAi,
            clock = clock
        )
    }

    private fun pullRequestDetails(
        description: String?,
        state: PullRequestState = PullRequestState.Open,
        title: String = "Test pull request",
        sourceBranch: String = "feature"
    ): PullRequestDetails {
        return PullRequestDetails(
            summary = PullRequestSummary(
                id = 273,
                title = title,
                authorDisplayName = "Author",
                authorAvatarUrl = null,
                createdOn = Instant.parse("2026-09-29T19:28:03.123456Z"),
                updatedOn = Instant.parse("2026-09-29T19:28:03.123456Z"),
                sourceBranch = sourceBranch,
                destinationBranch = "main",
                commentCount = 0,
                webUrl = "https://bitbucket.org/workspace/repository/pull-requests/273",
                state = state,
                isDraft = false
            ),
            isApprovedByCurrentUser = false,
            description = description
        )
    }
}