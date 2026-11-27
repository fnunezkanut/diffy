package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.CustomCheckPhase
import com.github.fnunezkanut.bbcloud.bitbucket.GitMergeabilityReason
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckPullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MergeCheckLabelsTest {
    @Nested
    inner class LabelForTests {
        @Test
        fun `labelFor - should use the configured minimum approvals`() {
            // GIVEN
            val check = standard(kind = "minimum_approvals", requiredValue = 2)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.kind.minimumApprovals", count = 2)
        }

        @Test
        fun `labelFor - should default the minimum successful builds to one`() {
            // GIVEN
            val check = standard(kind = "minimum_successful_builds", requiredValue = null)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.kind.minimumSuccessfulBuilds", count = 1)
        }

        @Test
        fun `labelFor - should describe zero maximum commits behind as no commits behind`() {
            // GIVEN
            val check = standard(kind = "maximum_commits_behind", requiredValue = 0)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.kind.noCommitsBehind")
        }

        @Test
        fun `labelFor - should include a positive maximum commits behind`() {
            // GIVEN
            val check = standard(kind = "maximum_commits_behind", requiredValue = 5)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.kind.maximumCommitsBehind", count = 5)
        }

        @Test
        fun `labelFor - should use the name of a custom check`() {
            // GIVEN
            val check = custom(name = "Security scan")

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Text("Security scan")
        }

        @Test
        fun `labelFor - should fall back to a generic message for an unnamed custom check`() {
            // GIVEN
            val check = custom(name = null)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.kind.custom")
        }

        @Test
        fun `labelFor - should humanize an unknown kind`() {
            // GIVEN
            val check = standard(kind = "require_all_comments_resolved")

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Text("Require all comments resolved")
        }

        @Test
        fun `labelFor - should humanize an unrecognized check type`() {
            // GIVEN
            val check = PullRequestMergeCheck.UnrecognizedCheck(
                type = "future_check",
                status = MergeCheckStatus.Failed,
                isRequired = true,
                isBlocking = true
            )

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Text("Future check")
        }

        @Test
        fun `labelFor - should describe each pull request state`() {
            // GIVEN
            val keysByState = mapOf(
                MergeCheckPullRequestState.Draft to "main.branch.checks.state.draft",
                MergeCheckPullRequestState.Merged to "main.branch.checks.state.merged",
                MergeCheckPullRequestState.Declined to "main.branch.checks.state.declined",
                MergeCheckPullRequestState.Superseded to "main.branch.checks.state.superseded",
                MergeCheckPullRequestState.Open to "main.branch.checks.state.queued",
                MergeCheckPullRequestState.Unknown to "main.branch.checks.state.unknown"
            )

            // WHEN
            val labels = keysByState.keys.associateWith { state ->
                MergeCheckLabels.labelFor(
                    PullRequestMergeCheck.StateCheck(state = state, status = MergeCheckStatus.Failed, isRequired = true, isBlocking = true)
                )
            }

            // THEN
            assertSoftly {
                keysByState.forEach { (state, key) -> labels[state] shouldBe MergeCheckLabel.Message(key = key) }
            }
        }

        @Test
        fun `labelFor - should describe the permission check`() {
            // GIVEN
            val check = PullRequestMergeCheck.PermissionCheck(status = MergeCheckStatus.Failed, isRequired = true, isBlocking = true)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.permission")
        }

        @Test
        fun `labelFor - should describe each git mergeability reason`() {
            // GIVEN
            val keysByReason = mapOf(
                GitMergeabilityReason.Conflicts to "main.branch.checks.git.conflicts",
                GitMergeabilityReason.MergeImpossible to "main.branch.checks.git.mergeImpossible",
                GitMergeabilityReason.Clean to "main.branch.checks.git.clean",
                null to "main.branch.checks.git.unknown"
            )

            // WHEN
            val labels = keysByReason.keys.associateWith { reason ->
                MergeCheckLabels.labelFor(git(reason = reason))
            }

            // THEN
            assertSoftly {
                keysByReason.forEach { (reason, key) -> labels[reason] shouldBe MergeCheckLabel.Message(key = key) }
            }
        }

        @Test
        fun `labelFor - should describe a queued pull request with the queue name`() {
            // GIVEN
            val check = queue(isQueued = true, queueName = "main queue", queueState = "ACTIVE")

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Named(key = "main.branch.checks.queue.queuedIn", name = "main queue")
        }

        @Test
        fun `labelFor - should describe a queued pull request without a queue name`() {
            // GIVEN
            val check = queue(isQueued = true, queueName = null, queueState = null)

            // WHEN
            val label = MergeCheckLabels.labelFor(check)

            // THEN
            label shouldBe MergeCheckLabel.Message(key = "main.branch.checks.queue.queued")
        }

        @Test
        fun `labelFor - should describe a draining, a suspended and a required merge queue`() {
            // GIVEN
            val draining = queue(isQueued = false, queueName = "q", queueState = "DRAINING")
            val suspended = queue(isQueued = false, queueName = "q", queueState = "SUSPENDED")
            val required = queue(isQueued = false, queueName = "q", queueState = "ACTIVE")

            // WHEN
            val labels = listOf(draining, suspended, required).map { check -> MergeCheckLabels.labelFor(check) }

            // THEN
            labels shouldBe listOf(
                MergeCheckLabel.Message(key = "main.branch.checks.queue.draining"),
                MergeCheckLabel.Message(key = "main.branch.checks.queue.suspended"),
                MergeCheckLabel.Message(key = "main.branch.checks.queue.required")
            )
        }
    }

    @Nested
    inner class ProgressTextTests {
        @Test
        fun `progressText - should show observed over required for approvals and builds`() {
            // GIVEN
            val approvals = standard(kind = "minimum_approvals", requiredValue = 2, observedValue = 1)
            val defaultReviewers = standard(kind = "minimum_default_reviewer_approvals", requiredValue = 1, observedValue = 0)
            val builds = standard(kind = "minimum_successful_builds", requiredValue = 3, observedValue = 3)

            // WHEN
            val texts = listOf(approvals, defaultReviewers, builds).map { check -> MergeCheckLabels.progressText(check) }

            // THEN
            texts shouldBe listOf("1 / 2", "0 / 1", "3 / 3")
        }

        @Test
        fun `progressText - should describe the commits behind against the maximum`() {
            // GIVEN
            val behind = standard(kind = "maximum_commits_behind", requiredValue = 2, observedValue = 5)
            val onlyOne = standard(kind = "maximum_commits_behind", requiredValue = 0, observedValue = 1)

            // WHEN
            val texts = listOf(behind, onlyOne).map { check -> MergeCheckLabels.progressText(check) }

            // THEN
            texts shouldBe listOf("5 commits behind (max 2)", "1 commit behind (max 0)")
        }

        @Test
        fun `progressText - should be absent without an observed value or for other kinds`() {
            // GIVEN
            val noObservation = standard(kind = "minimum_approvals", requiredValue = 2, observedValue = null)
            val otherKind = standard(kind = "resolved_tasks", requiredValue = null, observedValue = 2)

            // WHEN
            val texts = listOf(noObservation, otherKind, custom(name = "Scan")).map { check -> MergeCheckLabels.progressText(check) }

            // THEN
            assertSoftly {
                texts.forEach { text -> text.shouldBeNull() }
            }
        }
    }

    @Test
    fun `humanize - should capitalize the first word and separate words with spaces`() {
        // WHEN
        val text = MergeCheckLabels.humanize("NO_failed_builds")

        // THEN
        text shouldBe "No failed builds"
    }

    @Test
    fun `toPlainText - should strip markup from bundle labels and keep custom text as written`() {
        // GIVEN
        val message = MergeCheckLabel.Message(key = "main.branch.checks.kind.minimumApprovals", count = 2)
        val text = MergeCheckLabel.Text("Scan <b>1</b>")

        // WHEN
        val plainMessage = MergeCheckLabels.toPlainText(message)
        val plainText = MergeCheckLabels.toPlainText(text)

        // THEN
        assertSoftly {
            plainMessage shouldBe "2+ approvals"
            plainText shouldBe "Scan <b>1</b>"
        }
    }

    @Test
    fun `toHtml - should escape a queue name`() {
        // GIVEN
        val label = MergeCheckLabel.Named(key = "main.branch.checks.queue.queuedIn", name = "a<b")

        // WHEN
        val html = MergeCheckLabels.toHtml(label)

        // THEN
        html shouldBe "Queued in a&lt;b"
    }

    @Nested
    inner class SummaryTests {
        @Test
        fun `MergeCheckSummary of - should count passed required checks and detect blocking ones`() {
            // GIVEN
            val checks = listOf(
                standard(kind = "minimum_approvals", status = MergeCheckStatus.Failed),
                standard(kind = "resolved_tasks", status = MergeCheckStatus.Passed),
                standard(kind = "no_failed_builds", status = MergeCheckStatus.Failed, isBlocking = true),
                standard(kind = "no_changes_requested", status = MergeCheckStatus.Passed)
            )

            // WHEN
            val summary = MergeCheckSummary.of(checks)

            // THEN
            summary shouldBe MergeCheckSummary(passed = 2, total = 4, isBlocking = true)
        }

        @Test
        fun `MergeCheckSummary of - should leave advisory and skipped checks out of the count`() {
            // GIVEN
            val checks = listOf(
                standard(kind = "minimum_approvals", status = MergeCheckStatus.Passed),
                standard(kind = "resolved_tasks", status = MergeCheckStatus.Failed, isRequired = false),
                standard(kind = "no_failed_builds", status = MergeCheckStatus.Skipped)
            )

            // WHEN
            val summary = MergeCheckSummary.of(checks)

            // THEN
            summary shouldBe MergeCheckSummary(passed = 1, total = 1, isBlocking = false)
        }

        @Test
        fun `MergeCheckSummary of - should leave eligibility checks out of the count but let them block`() {
            // GIVEN
            val checks = listOf(
                standard(kind = "minimum_approvals", status = MergeCheckStatus.Passed),
                PullRequestMergeCheck.StateCheck(
                    state = MergeCheckPullRequestState.Open,
                    status = MergeCheckStatus.Passed,
                    isRequired = true,
                    isBlocking = false
                ),
                PullRequestMergeCheck.PermissionCheck(status = MergeCheckStatus.Passed, isRequired = true, isBlocking = false),
                git(reason = GitMergeabilityReason.Conflicts, isBlocking = true),
                queue(isQueued = false, queueName = "q", queueState = "ACTIVE")
            )

            // WHEN
            val summary = MergeCheckSummary.of(checks)

            // THEN
            summary shouldBe MergeCheckSummary(passed = 1, total = 1, isBlocking = true)
        }

        @Test
        fun `MergeCheckSummary of - should count zero of zero when no standard check is enforced`() {
            // GIVEN
            val checks = listOf(
                standard(kind = "minimum_approvals", status = MergeCheckStatus.Failed, isRequired = false),
                standard(kind = "resolved_tasks", status = MergeCheckStatus.Passed, isRequired = false)
            )

            // WHEN
            val summary = MergeCheckSummary.of(checks)

            // THEN
            summary shouldBe MergeCheckSummary(passed = 0, total = 0, isBlocking = false)
        }
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

    private fun custom(name: String?): PullRequestMergeCheck.CustomCheck {
        return PullRequestMergeCheck.CustomCheck(
            phase = CustomCheckPhase.PreMerge,
            id = null,
            name = name,
            message = null,
            status = MergeCheckStatus.Passed,
            isRequired = true,
            isBlocking = false
        )
    }

    private fun git(reason: GitMergeabilityReason?, isBlocking: Boolean = false): PullRequestMergeCheck.GitCheck {
        return PullRequestMergeCheck.GitCheck(
            reason = reason,
            conflictsUrl = null,
            status = MergeCheckStatus.Failed,
            isRequired = true,
            isBlocking = isBlocking
        )
    }

    private fun queue(isQueued: Boolean, queueName: String?, queueState: String?): PullRequestMergeCheck.QueueCheck {
        return PullRequestMergeCheck.QueueCheck(
            isQueued = isQueued,
            queueName = queueName,
            queueState = queueState,
            status = MergeCheckStatus.Passed,
            isRequired = true,
            isBlocking = false
        )
    }
}