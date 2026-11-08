package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CachedPullRequestMapperTest {
    @Nested
    inner class RoundTrip {
        @Test
        fun `fromSummary then toSummary - should return an equal summary with reviewers and approvers`() {
            // GIVEN a summary with reviewers in a specific order and an approver
            val summary = cacheTestSummary(
                approverUuids = setOf("{ben}"),
                reviewers = listOf(
                    cacheTestReviewer(uuid = "{ben}", status = ReviewerStatus.Approved),
                    cacheTestReviewer(uuid = "{cara}", status = ReviewerStatus.ChangesRequested)
                ),
                commentCount = 4
            )

            // WHEN the summary is cached and read back
            val entry = CachedPullRequestMapper.fromSummary(
                workspace = "Workspace",
                repoSlug = "Repo",
                summary = summary,
                fetchedAt = CACHE_TEST_START,
                closedOn = null
            )
            val restored = CachedPullRequestMapper.toSummary(entry = entry)

            // THEN every field, including the reviewer order and statuses, survives
            restored shouldBe summary
        }

        @Test
        fun `fromSummary then toSummary - should keep a blank author without an author record`() {
            // GIVEN a summary with no author information at all
            val summary = cacheTestSummary().copy(
                authorDisplayName = "",
                authorAvatarUrl = null,
                authorUuid = null,
                authorAccountId = null
            )

            // WHEN it is cached and read back
            val entry = CachedPullRequestMapper.fromSummary(
                workspace = "workspace",
                repoSlug = "repo",
                summary = summary,
                fetchedAt = CACHE_TEST_START,
                closedOn = null
            )

            // THEN no author record is stored and the blank author comes back blank
            assertSoftly(entry) {
                author shouldBe null
                CachedPullRequestMapper.toSummary(entry = this) shouldBe summary
            }
        }

        @Test
        fun `toReviewingPullRequest - should keep the original-case workspace and repository`() {
            // GIVEN an entry cached from mixed-case names
            val entry = CachedPullRequestMapper.fromSummary(
                workspace = "AcmeCo",
                repoSlug = "Payments-API",
                summary = cacheTestSummary(id = 9L),
                fetchedAt = CACHE_TEST_START,
                closedOn = null
            )

            // WHEN it is turned into a reviewing pull request
            val reviewing = CachedPullRequestMapper.toReviewingPullRequest(entry = entry)

            // THEN the names keep their original case
            assertSoftly(reviewing) {
                workspace shouldBe "AcmeCo"
                repoSlug shouldBe "Payments-API"
                summary.id shouldBe 9L
            }
        }
    }

    @Nested
    inner class ApprovedByUser {
        @Test
        fun `isApprovedBy - should match a uuid with different braces and case`() {
            // GIVEN an approver stored with braces in upper case
            val entry = CachedPullRequestMapper.fromSummary(
                workspace = "workspace",
                repoSlug = "repo",
                summary = cacheTestSummary(approverUuids = setOf("{ABC-123}")),
                fetchedAt = CACHE_TEST_START,
                closedOn = null
            )

            // THEN the same account without braces, in lower case, is approved
            entry.isApprovedBy(userUuid = "abc-123").shouldBeTrue()
            entry.isApprovedBy(userUuid = "{abc-123}").shouldBeTrue()
        }

        @Test
        fun `isApprovedBy - should be false for another user or a missing uuid`() {
            // GIVEN an entry approved by one account
            val entry = CachedPullRequestMapper.fromSummary(
                workspace = "workspace",
                repoSlug = "repo",
                summary = cacheTestSummary(approverUuids = setOf("{ben}")),
                fetchedAt = CACHE_TEST_START,
                closedOn = null
            )

            // THEN other accounts and a null user are not approvers
            entry.isApprovedBy(userUuid = "{anna}").shouldBeFalse()
            entry.isApprovedBy(userUuid = null).shouldBeFalse()
            entry.state shouldBe PullRequestState.Open
        }
    }
}