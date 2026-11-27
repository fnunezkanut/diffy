package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.CustomCheckPhase
import com.github.fnunezkanut.bbcloud.bitbucket.GitMergeabilityReason
import com.github.fnunezkanut.bbcloud.bitbucket.MergeCheckStatus
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksFailure
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MergeChecksTrackerTest {
    private val approvals = PullRequestMergeCheck.StandardCheck(
        kind = "minimum_approvals",
        requiredValue = 1,
        observedValue = 1,
        status = MergeCheckStatus.Passed,
        isRequired = true,
        isBlocking = false
    )
    private val conflicts = PullRequestMergeCheck.GitCheck(
        reason = GitMergeabilityReason.Conflicts,
        conflictsUrl = null,
        status = MergeCheckStatus.Failed,
        isRequired = true,
        isBlocking = true
    )
    private val clean = PullRequestMergeCheck.GitCheck(
        reason = GitMergeabilityReason.Clean,
        conflictsUrl = null,
        status = MergeCheckStatus.Passed,
        isRequired = true,
        isBlocking = false
    )
    private val tracker = MergeChecksTracker()

    @Nested
    inner class GitCheck {
        @Test
        fun `onGit - should add a conflicts check that blocks Merge with the conflicts label`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // WHEN
            val snapshot = tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(conflicts))).shouldNotBeNull()

            // THEN
            assertSoftly {
                snapshot.result shouldBe MergeChecksResult.Loaded(listOf(approvals, conflicts))
                snapshot.gitStatus shouldBe GitRowStatus.Loaded
                resolve(snapshot) shouldBe MergeChecksState.Blocked(reasons = listOf("Has conflicts"))
            }
        }

        @Test
        fun `onGit - should ignore a result for a pull request that is no longer shown`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // WHEN
            val snapshot = tracker.onGit(pullRequestId = 99, result = MergeChecksResult.Loaded(listOf(conflicts)))

            // THEN
            assertSoftly {
                snapshot.shouldBeNull()
                tracker.gitStatus() shouldBe GitRowStatus.NotLoaded
            }
        }

        @Test
        fun `onGit - should not change the Merge state when the request fails`() {
            // GIVEN
            val before = tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))
            tracker.onGitLoading(7)

            // WHEN
            val after = tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Unavailable(MergeChecksFailure.Other)).shouldNotBeNull()

            // THEN
            assertSoftly {
                after.gitStatus shouldBe GitRowStatus.Failed
                after.result shouldBe before.result
                resolve(after) shouldBe resolve(before)
                resolve(after) shouldBe MergeChecksState.NotBlocked
            }
        }

        @Test
        fun `onGit - should keep the Git check of an earlier request when a newer one fails`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))
            tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(conflicts)))
            tracker.onGitLoading(7)

            // WHEN
            val snapshot = tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited)).shouldNotBeNull()

            // THEN
            snapshot.result shouldBe MergeChecksResult.Loaded(listOf(approvals, conflicts))
        }

        @Test
        fun `onGit - should replace the Git check when a newer request reports a clean merge`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))
            tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(conflicts)))

            // WHEN
            val snapshot = tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(clean))).shouldNotBeNull()

            // THEN
            assertSoftly {
                snapshot.result shouldBe MergeChecksResult.Loaded(listOf(approvals, clean))
                resolve(snapshot) shouldBe MergeChecksState.NotBlocked
            }
        }

        @Test
        fun `onGitLoading - should report the loading status and ignore another pull request`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // WHEN
            val loading = tracker.onGitLoading(7)
            val other = tracker.onGitLoading(99)

            // THEN
            assertSoftly {
                loading.shouldNotBeNull().gitStatus shouldBe GitRowStatus.Loading
                other.shouldBeNull()
            }
        }

        @Test
        fun `onOverview - should keep the Git check of the same pull request across an overview reload`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))
            tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(conflicts)))

            // WHEN
            val snapshot = tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // THEN
            snapshot.result shouldBe MergeChecksResult.Loaded(listOf(approvals, conflicts))
        }

        @Test
        fun `onOverview - should drop the Git check of another pull request`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))
            tracker.onGit(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(conflicts)))

            // WHEN
            val snapshot = tracker.onOverview(pullRequestId = 8, result = MergeChecksResult.Loaded(listOf(approvals)))

            // THEN
            assertSoftly {
                snapshot.result shouldBe MergeChecksResult.Loaded(listOf(approvals))
                snapshot.gitStatus shouldBe GitRowStatus.NotLoaded
            }
        }
    }

    @Nested
    inner class Refresh {
        @Test
        fun `onRefresh - should return nothing when the checks equal the shown ones`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // WHEN
            val snapshot = tracker.onRefresh(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // THEN
            snapshot.shouldBeNull()
        }

        @Test
        fun `onRefresh - should return the new checks when they differ`() {
            // GIVEN
            val pending = approvals.copy(status = MergeCheckStatus.Pending)
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(pending)))

            // WHEN
            val snapshot = tracker.onRefresh(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // THEN
            snapshot.shouldNotBeNull().result shouldBe MergeChecksResult.Loaded(listOf(approvals))
        }

        @Test
        fun `onRefresh - should ignore a result for another pull request`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // WHEN
            val snapshot = tracker.onRefresh(pullRequestId = 8, result = MergeChecksResult.Loaded(emptyList()))

            // THEN
            snapshot.shouldBeNull()
        }

        @Test
        fun `onRefresh - should fall back on the last loaded checks when the read is rate limited`() {
            // GIVEN
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(approvals)))

            // WHEN
            val snapshot = tracker.onRefresh(pullRequestId = 7, result = MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited)).shouldNotBeNull()

            // THEN
            assertSoftly {
                snapshot.result shouldBe MergeChecksResult.Unavailable(MergeChecksFailure.RateLimited)
                snapshot.previous shouldBe MergeChecksResult.Loaded(listOf(approvals))
                resolve(snapshot) shouldBe MergeChecksState.NotBlocked
            }
        }
    }

    @Nested
    inner class PendingChecks {
        @Test
        fun `hasPendingCheck - should be true for a pending pre-merge check`() {
            // GIVEN
            val pending = custom(phase = CustomCheckPhase.PreMerge, status = MergeCheckStatus.Pending)
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(pending)))

            // THEN
            tracker.hasPendingCheck() shouldBe true
        }

        @Test
        fun `hasPendingCheck - should ignore a pending on-merge check, which stays pending until the merge`() {
            // GIVEN
            val pending = custom(phase = CustomCheckPhase.OnMerge, status = MergeCheckStatus.Pending)
            tracker.onOverview(pullRequestId = 7, result = MergeChecksResult.Loaded(listOf(pending, approvals)))

            // THEN
            tracker.hasPendingCheck() shouldBe false
        }

        @Test
        fun `hasPendingCheck - should be false before any checks loaded`() {
            tracker.hasPendingCheck() shouldBe false
        }
    }

    private fun resolve(snapshot: MergeChecksSnapshot): MergeChecksState {
        return MergeChecksStateResolver.resolve(result = snapshot.result, previous = snapshot.previous, destinationBranch = "main")
    }

    private fun custom(phase: CustomCheckPhase, status: MergeCheckStatus): PullRequestMergeCheck.CustomCheck {
        return PullRequestMergeCheck.CustomCheck(
            phase = phase,
            id = null,
            name = "Scan",
            message = null,
            status = status,
            isRequired = true,
            isBlocking = false
        )
    }
}