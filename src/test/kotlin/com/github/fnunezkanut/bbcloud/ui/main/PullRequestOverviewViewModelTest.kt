package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.CustomCheckPhase
import com.github.fnunezkanut.bbcloud.bitbucket.GitMergeabilityReason
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckPullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksFailure
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestDetails
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck
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

    @Nested
    inner class ChecksTests {
        private val webUrl = "https://bitbucket.org/workspace/repository/pull-requests/273"

        @Test
        fun `showChecks - should order the rows Eligibility, Standard, Custom, Other`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(
                unrecognized(),
                custom(name = "Scan"),
                standard(kind = "resolved_tasks"),
                permission(status = MergeCheckStatus.Failed, isBlocking = true)
            )

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

            // THEN
            viewModel.state.value.checks.rows.map { row -> row.group } shouldBe
                listOf(CheckRowGroup.Eligibility, CheckRowGroup.Standard, CheckRowGroup.Custom, CheckRowGroup.Other)
        }

        @Test
        fun `showChecks - should hide passing eligibility checks and count only required ones`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(
                permission(status = MergeCheckStatus.Passed, isBlocking = false),
                PullRequestMergeCheck.StateCheck(
                    state = MergeCheckPullRequestState.Open,
                    status = MergeCheckStatus.Passed,
                    isRequired = true,
                    isBlocking = false
                ),
                git(reason = GitMergeabilityReason.Clean, status = MergeCheckStatus.Passed, isBlocking = false),
                standard(kind = "minimum_approvals", status = MergeCheckStatus.Failed, isBlocking = true, requiredValue = 2, observedValue = 1)
            )

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.Loaded)

            // THEN
            assertSoftly(viewModel.state.value.checks) {
                rows.size shouldBe 1
                title shouldBe "<b>0 of 1</b> checks passed"
                statusIcon shouldBe SectionStatusIcon.Error
                rows.single().secondaryText shouldBe "1 / 2"
            }
        }

        @Test
        fun `showChecks - should show the empty message when only passing eligibility checks exist`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(permission(status = MergeCheckStatus.Passed, isBlocking = false))

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

            // THEN
            assertSoftly(viewModel.state.value.checks) {
                message shouldBe "No merge checks configured"
                rows shouldBe emptyList()
            }
        }

        @Test
        fun `showChecks - should mark an unenforced standard check as advisory and count zero of zero`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(standard(kind = "resolved_tasks", status = MergeCheckStatus.Failed, isRequired = false))

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

            // THEN
            assertSoftly(viewModel.state.value.checks) {
                title shouldBe "<b>0 of 0</b> checks passed"
                statusTooltip shouldBe "No check blocks merging"
                rows.single().isAdvisory shouldBe true
                rows.single().iconKind shouldBe CheckRowIcon.Warning
                rows.single().tooltip shouldBe "Failed but not enforced"
            }
        }

        @Test
        fun `showChecks - should label a pending on-merge check as evaluated when merging with a neutral icon`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(custom(name = "Release gate", phase = CustomCheckPhase.OnMerge, status = MergeCheckStatus.Pending, message = "ignored"))

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

            // THEN
            assertSoftly(viewModel.state.value.checks.rows.single()) {
                labelHtml shouldBe "Release gate"
                secondaryText shouldBe "Evaluated when merging"
                tooltip shouldBe "Evaluated when merging"
                iconKind shouldBe CheckRowIcon.Neutral
            }
        }

        @Test
        fun `showChecks - should escape the message of a custom check`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(custom(name = "Scan", status = MergeCheckStatus.Failed, message = "2 <high> findings"))

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

            // THEN
            viewModel.state.value.checks.rows.single().secondaryText shouldBe "2 &lt;high&gt; findings"
        }

        @Test
        fun `showChecks - should render the previous rows as stale when the read failed`() {
            // GIVEN
            val viewModel = createViewModel()
            val previous = MergeChecksResult.Loaded(listOf(standard(kind = "resolved_tasks", status = MergeCheckStatus.Passed)))

            // WHEN
            viewModel.showChecks(
                result = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited),
                previous = previous,
                gitStatus = GitRowStatus.NotLoaded
            )

            // THEN
            assertSoftly(viewModel.state.value.checks) {
                isStale shouldBe true
                isRetryVisible shouldBe true
                rows.size shouldBe 1
                title shouldBe "<b>1 of 1</b> checks passed"
                message shouldBe "Bitbucket is rate limiting merge check requests. Diffy will retry shortly."
            }
        }

        @Test
        fun `showChecks - should show a failure message and Retry without a previous result`() {
            // GIVEN
            val viewModel = createViewModel()
            val messages = mapOf(
                MergeChecksFailure.CustomChecksUnavailable to "Bitbucket could not evaluate the custom merge checks. A merge would fail too.",
                MergeChecksFailure.RateLimited to "Bitbucket is rate limiting merge check requests. Diffy will retry shortly.",
                MergeChecksFailure.Other to "Could not load merge checks"
            )

            // WHEN
            val sections = messages.keys.associateWith { failure ->
                viewModel.showChecks(result = MergeChecksResult.Unavailable(failure), previous = null, gitStatus = GitRowStatus.NotLoaded)
                viewModel.state.value.checks
            }

            // THEN
            assertSoftly {
                messages.forEach { (failure, message) ->
                    sections.getValue(failure).message shouldBe message
                    sections.getValue(failure).isRetryVisible shouldBe true
                    sections.getValue(failure).isStale shouldBe false
                    sections.getValue(failure).rows shouldBe emptyList()
                }
            }
        }

        @Test
        fun `showChecks - should not offer Retry after a successful read`() {
            // GIVEN
            val viewModel = createViewModel()

            // WHEN
            viewModel.showChecks(
                result = MergeChecksResult.Loaded(listOf(standard(kind = "resolved_tasks"))),
                previous = null,
                gitStatus = GitRowStatus.NotLoaded
            )

            // THEN
            assertSoftly(viewModel.state.value.checks) {
                isRetryVisible shouldBe false
                isStale shouldBe false
            }
        }

        @Test
        fun `showChecks - should show a muted checking row while the Git request is loading`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(standard(kind = "resolved_tasks"))

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.Loading)

            // THEN
            assertSoftly(viewModel.state.value.checks) {
                gitStatus shouldBe GitRowStatus.Loading
                rows.map { row -> row.labelHtml } shouldBe listOf("Checking for conflicts", "<b>All</b> tasks resolved")
                rows.first().isMuted shouldBe true
            }
        }

        @Test
        fun `showChecks - should show a neutral muted row for a Git check Bitbucket could not evaluate`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(git(reason = null, status = MergeCheckStatus.Unknown, isBlocking = false))

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.Loaded)

            // THEN
            assertSoftly(viewModel.state.value.checks.rows.single()) {
                labelHtml shouldBe "Could not check for conflicts"
                iconKind shouldBe CheckRowIcon.Neutral
                isMuted shouldBe true
                isBlocking shouldBe false
            }
        }

        @Test
        fun `showChecks - should link failing checks to their page sections and queue rows to Bitbucket`() {
            // GIVEN
            val viewModel = createViewModel()
            val scope = CoroutineScope(SupervisorJob())
            viewModel.showReviewers(details = pullRequestDetails(description = null), coroutineScope = scope)
            val checks = listOf(
                standard(kind = "minimum_approvals", status = MergeCheckStatus.Failed),
                standard(kind = "minimum_default_reviewer_approvals", status = MergeCheckStatus.Failed),
                standard(kind = "resolved_tasks", status = MergeCheckStatus.Failed),
                standard(kind = "no_failed_builds", status = MergeCheckStatus.Failed),
                standard(kind = "minimum_successful_builds", status = MergeCheckStatus.Pending),
                standard(kind = "no_in_progress_builds", status = MergeCheckStatus.Failed),
                standard(kind = "no_changes_requested", status = MergeCheckStatus.Failed),
                standard(kind = "resolved_tasks", status = MergeCheckStatus.Passed),
                PullRequestMergeCheck.QueueCheck(
                    isQueued = false,
                    queueName = "q",
                    queueState = "ACTIVE",
                    status = MergeCheckStatus.Failed,
                    isRequired = true,
                    isBlocking = true
                )
            )

            try {
                // WHEN
                viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

                // THEN
                val actions = viewModel.state.value.checks.rows.map { row -> row.actions }
                actions shouldBe listOf(
                    listOf(CheckRowAction.OpenInBitbucket(webUrl)),
                    listOf(CheckRowAction.ScrollToSection(CheckSection.Reviewers)),
                    listOf(CheckRowAction.ScrollToSection(CheckSection.Reviewers)),
                    listOf(CheckRowAction.ScrollToSection(CheckSection.Tasks)),
                    listOf(CheckRowAction.ScrollToSection(CheckSection.Builds)),
                    listOf(CheckRowAction.ScrollToSection(CheckSection.Builds)),
                    listOf(CheckRowAction.ScrollToSection(CheckSection.Builds)),
                    emptyList(),
                    emptyList()
                )
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `showChecks - should offer the rebase only for conflicts with a local checkout`() {
            // GIVEN
            val withCheckout = createViewModel(isRebaseAvailable = { true })
            val withoutCheckout = createViewModel(isRebaseAvailable = { false })
            val scope = CoroutineScope(SupervisorJob())
            val conflicts = MergeChecksResult.Loaded(listOf(git(reason = GitMergeabilityReason.Conflicts)))
            val impossible = MergeChecksResult.Loaded(listOf(git(reason = GitMergeabilityReason.MergeImpossible)))
            listOf(withCheckout, withoutCheckout).forEach { viewModel ->
                viewModel.showReviewers(details = pullRequestDetails(description = null), coroutineScope = scope)
            }

            try {
                // WHEN
                withCheckout.showChecks(result = conflicts, previous = null, gitStatus = GitRowStatus.Loaded)
                withoutCheckout.showChecks(result = conflicts, previous = null, gitStatus = GitRowStatus.Loaded)
                val impossibleViewModel = createViewModel(isRebaseAvailable = { true })
                impossibleViewModel.showReviewers(details = pullRequestDetails(description = null), coroutineScope = scope)
                impossibleViewModel.showChecks(result = impossible, previous = null, gitStatus = GitRowStatus.Loaded)

                // THEN
                assertSoftly {
                    withCheckout.state.value.checks.rows.single().actions shouldBe
                        listOf(CheckRowAction.RebaseWithAi, CheckRowAction.OpenConflictsInBrowser(webUrl))
                    withoutCheckout.state.value.checks.rows.single().actions shouldBe listOf(CheckRowAction.OpenConflictsInBrowser(webUrl))
                    impossibleViewModel.state.value.checks.rows.single().actions shouldBe listOf(CheckRowAction.OpenConflictsInBrowser(webUrl))
                }
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `showChecks - should render a queued pull request as queued in its queue and merge in progress`() {
            // GIVEN
            val viewModel = createViewModel()
            val checks = listOf(
                PullRequestMergeCheck.StateCheck(
                    state = MergeCheckPullRequestState.Open,
                    status = MergeCheckStatus.Failed,
                    isRequired = true,
                    isBlocking = true
                ),
                PullRequestMergeCheck.QueueCheck(
                    isQueued = true,
                    queueName = "main queue",
                    queueState = "ACTIVE",
                    status = MergeCheckStatus.Passed,
                    isRequired = true,
                    isBlocking = false
                )
            )

            // WHEN
            viewModel.showChecks(result = MergeChecksResult.Loaded(checks), previous = null, gitStatus = GitRowStatus.NotLoaded)

            // THEN
            val rows = viewModel.state.value.checks.rows
            assertSoftly {
                rows.map { row -> row.labelHtml } shouldBe listOf("Merge in progress (queued)", "Queued in main queue")
                rows.map { row -> row.iconKind } shouldBe listOf(CheckRowIcon.Blocking, CheckRowIcon.Passed)
            }
        }

        @Test
        fun `showChecks - should show the draining and suspended states of a merge queue in the row text`() {
            // GIVEN
            val viewModel = createViewModel()

            fun queue(state: String): PullRequestMergeCheck.QueueCheck {
                return PullRequestMergeCheck.QueueCheck(
                    isQueued = false,
                    queueName = "q",
                    queueState = state,
                    status = MergeCheckStatus.Failed,
                    isRequired = true,
                    isBlocking = true
                )
            }

            // WHEN
            viewModel.showChecks(
                result = MergeChecksResult.Loaded(listOf(queue("DRAINING"), queue("SUSPENDED"), queue("ACTIVE"))),
                previous = null,
                gitStatus = GitRowStatus.NotLoaded
            )

            // THEN
            viewModel.state.value.checks.rows.map { row -> row.labelHtml } shouldBe listOf(
                "Merge queue is draining and not accepting pull requests",
                "Merge queue is suspended",
                "A merge queue is required"
            )
        }

        @Test
        fun `setNextChecksAttempt - should tell when Diffy retries a rate limited read and clear it after a success`() {
            // GIVEN
            val viewModel = createViewModel()
            viewModel.showChecks(
                result = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited),
                previous = null,
                gitStatus = GitRowStatus.NotLoaded
            )

            // WHEN
            viewModel.setNextChecksAttempt(30.seconds)

            // THEN
            viewModel.state.value.checks.retryTooltip shouldBe "Diffy tries again by itself in about 30 s"

            // WHEN
            viewModel.setNextChecksAttempt(null)

            // THEN
            viewModel.state.value.checks.retryTooltip.shouldBeNull()
        }

        @Test
        fun `onCheckActionClicked - should start the rebase`() {
            // GIVEN
            var rebases = 0
            val viewModel = createViewModel(onRebaseWithAi = { rebases++ })

            // WHEN
            viewModel.onCheckActionClicked(CheckRowAction.RebaseWithAi)

            // THEN
            rebases shouldBe 1
        }

        @Test
        fun `onCheckActionClicked - should expand the section and request a scroll that is consumed once`() {
            // GIVEN
            val viewModel = createViewModel()
            viewModel.onToggleReviewers()
            viewModel.state.value.reviewers.isExpanded shouldBe false

            // WHEN
            viewModel.onCheckActionClicked(CheckRowAction.ScrollToSection(CheckSection.Reviewers))

            // THEN
            assertSoftly(viewModel.state.value) {
                reviewers.isExpanded shouldBe true
                scrollTarget shouldBe CheckSection.Reviewers
            }

            // WHEN
            viewModel.onScrollTargetConsumed()

            // THEN
            viewModel.state.value.scrollTarget.shouldBeNull()
        }

        @Test
        fun `onRetryChecks - should request a new read of the checks`() {
            // GIVEN
            var retries = 0
            val viewModel = createViewModel(onRetryChecksRequested = { retries++ })

            // WHEN
            viewModel.onRetryChecks()

            // THEN
            retries shouldBe 1
        }

        private fun standard(
            kind: String,
            status: MergeCheckStatus = MergeCheckStatus.Passed,
            isBlocking: Boolean = false,
            isRequired: Boolean = true,
            requiredValue: Int? = null,
            observedValue: Int? = null
        ): PullRequestMergeCheck.StandardCheck {
            return PullRequestMergeCheck.StandardCheck(
                kind = kind,
                requiredValue = requiredValue,
                observedValue = observedValue,
                status = status,
                isRequired = isRequired,
                isBlocking = isBlocking
            )
        }

        private fun custom(
            name: String,
            phase: CustomCheckPhase = CustomCheckPhase.PreMerge,
            status: MergeCheckStatus = MergeCheckStatus.Passed,
            message: String? = null
        ): PullRequestMergeCheck.CustomCheck {
            return PullRequestMergeCheck.CustomCheck(
                phase = phase,
                id = null,
                name = name,
                message = message,
                status = status,
                isRequired = true,
                isBlocking = false
            )
        }

        private fun unrecognized(): PullRequestMergeCheck.UnrecognizedCheck {
            return PullRequestMergeCheck.UnrecognizedCheck(
                type = "future_check",
                status = MergeCheckStatus.Failed,
                isRequired = true,
                isBlocking = true
            )
        }

        private fun permission(status: MergeCheckStatus, isBlocking: Boolean): PullRequestMergeCheck.PermissionCheck {
            return PullRequestMergeCheck.PermissionCheck(status = status, isRequired = true, isBlocking = isBlocking)
        }

        private fun git(
            reason: GitMergeabilityReason?,
            status: MergeCheckStatus = MergeCheckStatus.Failed,
            isBlocking: Boolean = true
        ): PullRequestMergeCheck.GitCheck {
            return PullRequestMergeCheck.GitCheck(
                reason = reason,
                conflictsUrl = null,
                status = status,
                isRequired = true,
                isBlocking = isBlocking
            )
        }
    }

    private fun createViewModel(
        workspace: String = "acme",
        isAirAvailable: () -> Boolean = { false },
        onExplainWithAi: (ExplainPullRequestRequest) -> Unit = {},
        isRebaseAvailable: () -> Boolean = { false },
        onRebaseWithAi: () -> Unit = {},
        onRetryChecksRequested: () -> Unit = {},
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
            isRebaseAvailable = isRebaseAvailable,
            onRebaseWithAi = onRebaseWithAi,
            onRetryChecksRequested = onRetryChecksRequested,
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