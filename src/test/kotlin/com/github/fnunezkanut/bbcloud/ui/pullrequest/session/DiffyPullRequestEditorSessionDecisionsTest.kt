package com.github.fnunezkanut.bbcloud.ui.pullrequest.session

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("unit")
class DiffyPullRequestEditorSessionDecisionsTest {
    @Nested
    inner class BackgroundPollSkip {
        @Test
        fun `shouldSkipBackgroundPoll - should skip when the cache was refreshed within the window`() {
            DiffyPullRequestEditorSession.shouldSkipBackgroundPoll(age = 5.seconds, window = 15.seconds) shouldBe true
        }

        @Test
        fun `shouldSkipBackgroundPoll - should not skip at or after the window boundary`() {
            DiffyPullRequestEditorSession.shouldSkipBackgroundPoll(age = 15.seconds, window = 15.seconds) shouldBe false
            DiffyPullRequestEditorSession.shouldSkipBackgroundPoll(age = 16.seconds, window = 15.seconds) shouldBe false
        }

        @Test
        fun `shouldSkipBackgroundPoll - should never skip an unknown or negative age`() {
            DiffyPullRequestEditorSession.shouldSkipBackgroundPoll(age = null, window = 15.seconds) shouldBe false
            DiffyPullRequestEditorSession.shouldSkipBackgroundPoll(age = (-1).milliseconds, window = 15.seconds) shouldBe false
        }
    }

    @Nested
    inner class MergeChecksRefresh {
        @Test
        fun `shouldRefreshMergeChecks - should refresh while a check is pending`() {
            DiffyPullRequestEditorSession.shouldRefreshMergeChecks(
                hasPendingCheck = true,
                hasBuildInProgress = false,
                unchangedBuildRefreshes = 0
            ) shouldBe true
        }

        @Test
        fun `shouldRefreshMergeChecks - should refresh while a build is in progress`() {
            DiffyPullRequestEditorSession.shouldRefreshMergeChecks(
                hasPendingCheck = false,
                hasBuildInProgress = true,
                unchangedBuildRefreshes = 0
            ) shouldBe true
        }

        @Test
        fun `shouldRefreshMergeChecks - should not refresh when nothing can still change`() {
            DiffyPullRequestEditorSession.shouldRefreshMergeChecks(
                hasPendingCheck = false,
                hasBuildInProgress = false,
                unchangedBuildRefreshes = 0
            ) shouldBe false
        }

        @Test
        fun `shouldRefreshMergeChecks - should stop asking for a build after five reads that found nothing new`() {
            DiffyPullRequestEditorSession.shouldRefreshMergeChecks(
                hasPendingCheck = false,
                hasBuildInProgress = true,
                unchangedBuildRefreshes = 4
            ) shouldBe true
            DiffyPullRequestEditorSession.shouldRefreshMergeChecks(
                hasPendingCheck = false,
                hasBuildInProgress = true,
                unchangedBuildRefreshes = 5
            ) shouldBe false
        }

        @Test
        fun `shouldRefreshMergeChecks - should keep refreshing for a pending check however many reads found nothing new`() {
            DiffyPullRequestEditorSession.shouldRefreshMergeChecks(
                hasPendingCheck = true,
                hasBuildInProgress = true,
                unchangedBuildRefreshes = 50
            ) shouldBe true
        }
    }

    @Nested
    inner class RecentDetailsReuse {
        @Test
        fun `canReuseRecentDetails - should reuse for an initial load of the same pull request within the window`() {
            DiffyPullRequestEditorSession.canReuseRecentDetails(
                refresh = DiffyPullRequestEditorSession.DetailsRefresh.Initial,
                recentPullRequestId = 7L,
                pullRequestId = 7L,
                elapsed = 1.seconds,
                window = 2.seconds
            ) shouldBe true
        }

        @Test
        fun `canReuseRecentDetails - should never reuse after an action, a reviewer change, or a poll`() {
            listOf(
                DiffyPullRequestEditorSession.DetailsRefresh.AfterAction,
                DiffyPullRequestEditorSession.DetailsRefresh.ReviewersOnly,
                DiffyPullRequestEditorSession.DetailsRefresh.BackgroundPoll
            ).forEach { refresh ->
                DiffyPullRequestEditorSession.canReuseRecentDetails(
                    refresh = refresh,
                    recentPullRequestId = 7L,
                    pullRequestId = 7L,
                    elapsed = 0.seconds,
                    window = 2.seconds
                ) shouldBe false
            }
        }

        @Test
        fun `canReuseRecentDetails - should not reuse another pull request, an old read, or an unknown age`() {
            DiffyPullRequestEditorSession.canReuseRecentDetails(
                refresh = DiffyPullRequestEditorSession.DetailsRefresh.Initial,
                recentPullRequestId = 8L,
                pullRequestId = 7L,
                elapsed = 0.seconds,
                window = 2.seconds
            ) shouldBe false
            DiffyPullRequestEditorSession.canReuseRecentDetails(
                refresh = DiffyPullRequestEditorSession.DetailsRefresh.Initial,
                recentPullRequestId = 7L,
                pullRequestId = 7L,
                elapsed = 3.seconds,
                window = 2.seconds
            ) shouldBe false
            DiffyPullRequestEditorSession.canReuseRecentDetails(
                refresh = DiffyPullRequestEditorSession.DetailsRefresh.Initial,
                recentPullRequestId = 7L,
                pullRequestId = 7L,
                elapsed = null,
                window = 2.seconds
            ) shouldBe false
        }
    }
}