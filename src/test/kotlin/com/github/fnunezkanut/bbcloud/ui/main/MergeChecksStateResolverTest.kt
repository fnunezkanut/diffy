package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.GitMergeabilityReason
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksFailure
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MergeChecksStateResolverTest {
    @Test
    fun `resolve - should be Unknown while the checks are not loaded`() {
        // WHEN
        val state = MergeChecksStateResolver.resolve(result = null, previous = null, destinationBranch = "main")

        // THEN
        state shouldBe MergeChecksState.Unknown
    }

    @Nested
    inner class LoadedTests {
        @Test
        fun `resolve - should not block when no check blocks`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(listOf(standard(isBlocking = false, status = MergeCheckStatus.Failed), permission(isBlocking = false)))

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.NotBlocked
        }

        @Test
        fun `resolve - should not block when there are no checks`() {
            // WHEN
            val state = MergeChecksStateResolver.resolve(result = MergeChecksResult.Loaded(emptyList()), previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.NotBlocked
        }

        @Test
        fun `resolve - should deny permission with the destination branch`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(listOf(permission(isBlocking = true)))

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "release")

            // THEN
            state shouldBe MergeChecksState.PermissionDenied(branch = "release")
        }

        @Test
        fun `resolve - should require a queue when a queue check blocks entry and the pull request is not queued`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(listOf(queue(isRequired = true, isQueued = false, isBlocking = true)))

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.QueueRequired
        }

        @Test
        fun `resolve - should list a queued pull request as blocked instead of requiring a queue`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(listOf(queue(isRequired = true, isQueued = true, isBlocking = true)))

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.Blocked(reasons = listOf("Queued in main queue"))
        }

        @Test
        fun `resolve - should not require a queue when the queue check does not block`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(listOf(queue(isRequired = true, isQueued = false, isBlocking = false)))

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.NotBlocked
        }

        @Test
        fun `resolve - should block with the plain-text labels of the blocking checks in response order`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(
                listOf(
                    standard(isBlocking = true, status = MergeCheckStatus.Failed),
                    standard(isBlocking = false, status = MergeCheckStatus.Failed),
                    git(isBlocking = true)
                )
            )

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.Blocked(reasons = listOf("2+ approvals", "Has conflicts"))
        }

        @Test
        fun `resolve - should rank permission above a required queue and both above other blocking checks`() {
            // GIVEN
            val queueAndOthers = MergeChecksResult.Loaded(
                listOf(standard(isBlocking = true, status = MergeCheckStatus.Failed), queue(isRequired = true, isQueued = false, isBlocking = true))
            )
            val everything = MergeChecksResult.Loaded(
                listOf(
                    standard(isBlocking = true, status = MergeCheckStatus.Failed),
                    queue(isRequired = true, isQueued = false, isBlocking = true),
                    permission(isBlocking = true)
                )
            )

            // WHEN
            val withQueue = MergeChecksStateResolver.resolve(result = queueAndOthers, previous = null, destinationBranch = "main")
            val withPermission = MergeChecksStateResolver.resolve(result = everything, previous = null, destinationBranch = "main")

            // THEN
            withQueue shouldBe MergeChecksState.QueueRequired
            withPermission shouldBe MergeChecksState.PermissionDenied(branch = "main")
        }

        @Test
        fun `resolve - should block on an unrecognized check that blocks`() {
            // GIVEN
            val result = MergeChecksResult.Loaded(
                listOf(PullRequestMergeCheck.UnrecognizedCheck(type = "future_check", status = MergeCheckStatus.Failed, isRequired = true, isBlocking = true))
            )

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = null, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.Blocked(reasons = listOf("Future check"))
        }
    }

    @Nested
    inner class UnavailableTests {
        private val previous = MergeChecksResult.Loaded(listOf(standard(isBlocking = true, status = MergeCheckStatus.Failed)))

        @Test
        fun `resolve - should always disable Merge when custom checks are unavailable`() {
            // GIVEN
            val result = MergeChecksResult.Unavailable(MergeChecksFailure.CustomChecksUnavailable)

            // WHEN
            val state = MergeChecksStateResolver.resolve(result = result, previous = previous, destinationBranch = "main")

            // THEN
            state shouldBe MergeChecksState.Unavailable(MergeChecksFailure.CustomChecksUnavailable)
        }

        @Test
        fun `resolve - should keep the previous result when rate limited or failing`() {
            // GIVEN
            val rateLimited = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited)
            val other = MergeChecksResult.Unavailable(MergeChecksFailure.Other)

            // WHEN
            val fromRateLimit = MergeChecksStateResolver.resolve(result = rateLimited, previous = previous, destinationBranch = "main")
            val fromOther = MergeChecksStateResolver.resolve(result = other, previous = previous, destinationBranch = "main")

            // THEN
            fromRateLimit shouldBe MergeChecksState.Blocked(reasons = listOf("2+ approvals"))
            fromOther shouldBe MergeChecksState.Blocked(reasons = listOf("2+ approvals"))
        }

        @Test
        fun `resolve - should keep an unblocked previous result enabled`() {
            // GIVEN
            val unblocked = MergeChecksResult.Loaded(listOf(standard(isBlocking = false, status = MergeCheckStatus.Passed)))

            // WHEN
            val state = MergeChecksStateResolver.resolve(
                result = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited),
                previous = unblocked,
                destinationBranch = "main"
            )

            // THEN
            state shouldBe MergeChecksState.NotBlocked
        }

        @Test
        fun `resolve - should report the failure when there is no previous result`() {
            // WHEN
            val rateLimited = MergeChecksStateResolver.resolve(
                result = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited),
                previous = null,
                destinationBranch = "main"
            )
            val other = MergeChecksStateResolver.resolve(
                result = MergeChecksResult.Unavailable(MergeChecksFailure.Other),
                previous = null,
                destinationBranch = "main"
            )

            // THEN
            rateLimited shouldBe MergeChecksState.Unavailable(MergeChecksFailure.RateLimited)
            other shouldBe MergeChecksState.Unavailable(MergeChecksFailure.Other)
        }
    }

    private fun standard(isBlocking: Boolean, status: MergeCheckStatus): PullRequestMergeCheck.StandardCheck {
        return PullRequestMergeCheck.StandardCheck(
            kind = "minimum_approvals",
            requiredValue = 2,
            observedValue = 1,
            status = status,
            isRequired = true,
            isBlocking = isBlocking
        )
    }

    private fun permission(isBlocking: Boolean): PullRequestMergeCheck.PermissionCheck {
        return PullRequestMergeCheck.PermissionCheck(
            status = if (isBlocking) MergeCheckStatus.Failed else MergeCheckStatus.Passed,
            isRequired = true,
            isBlocking = isBlocking
        )
    }

    private fun git(isBlocking: Boolean): PullRequestMergeCheck.GitCheck {
        return PullRequestMergeCheck.GitCheck(
            reason = GitMergeabilityReason.Conflicts,
            conflictsUrl = null,
            status = MergeCheckStatus.Failed,
            isRequired = true,
            isBlocking = isBlocking
        )
    }

    private fun queue(isRequired: Boolean, isQueued: Boolean, isBlocking: Boolean): PullRequestMergeCheck.QueueCheck {
        return PullRequestMergeCheck.QueueCheck(
            isQueued = isQueued,
            queueName = "main queue",
            queueState = "ACTIVE",
            status = if (isBlocking) MergeCheckStatus.Failed else MergeCheckStatus.Passed,
            isRequired = isRequired,
            isBlocking = isBlocking
        )
    }
}