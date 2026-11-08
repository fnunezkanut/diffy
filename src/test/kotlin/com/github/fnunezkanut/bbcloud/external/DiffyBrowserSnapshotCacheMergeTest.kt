package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import com.github.fnunezkanut.bbcloud.cache.CACHE_TEST_START
import com.github.fnunezkanut.bbcloud.cache.CachedPullRequest
import com.github.fnunezkanut.bbcloud.cache.CachedPullRequestMapper
import com.github.fnunezkanut.bbcloud.cache.cacheTestReviewer
import com.github.fnunezkanut.bbcloud.cache.cacheTestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@Tag("unit")
class DiffyBrowserSnapshotCacheMergeTest {
    private val fetchedAt = Instant.parse("2026-11-01T11:00:00Z")
    private val newer = "2026-11-02T10:00:00+00:00"

    private fun cached(
        approverUuids: Set<String> = emptySet(),
        reviewers: List<com.github.fnunezkanut.bbcloud.bitbucket.PullRequestReviewer> = emptyList(),
        commentCount: Int = 0
    ): CachedPullRequest {
        return CachedPullRequestMapper.fromSummary(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(
                id = 1L,
                approverUuids = approverUuids,
                reviewers = reviewers,
                commentCount = commentCount
            ),
            fetchedAt = fetchedAt,
            closedOn = null
        )
    }

    @Nested
    inner class NewerSnapshot {
        @Test
        fun `merge - should apply a changed comment count`() {
            // GIVEN a snapshot that is newer and counts more comments
            val incoming = browserPullRequest(updatedOn = newer, commentCount = 7)

            // WHEN
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = cached(commentCount = 2), incoming = incoming)

            // THEN
            merged.shouldNotBeNull()
            assertSoftly(merged) {
                commentCount shouldBe 7
                updatedOn shouldBe Instant.parse("2026-11-02T10:00:00Z")
            }
        }

        @Test
        fun `merge - should apply a new title and a draft toggle`() {
            // GIVEN
            val incoming = browserPullRequest(updatedOn = newer, title = "Renamed", draft = true)

            // WHEN
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = cached(), incoming = incoming)

            // THEN
            merged.shouldNotBeNull()
            assertSoftly(merged) {
                title shouldBe "Renamed"
                isDraft shouldBe true
            }
        }

        @Test
        fun `merge - should set the state and closedOn when merged or declined, and clear closedOn on reopen`() {
            // GIVEN
            val existing = cached()

            // WHEN the pull request is merged, then declined, then reopened
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = browserPullRequest(updatedOn = newer, state = "MERGED"))
            val declined = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = browserPullRequest(updatedOn = newer, state = "declined"))
            val reopened = merged?.let { entry ->
                DiffyBrowserSnapshotCacheMerge.merge(
                    existing = entry,
                    incoming = browserPullRequest(updatedOn = "2026-11-03T10:00:00+00:00", state = "OPEN")
                )
            }

            // THEN
            assertSoftly {
                merged?.state shouldBe PullRequestState.Merged
                merged?.closedOn shouldBe Instant.parse("2026-11-02T10:00:00Z")
                declined?.state shouldBe PullRequestState.Declined
                reopened?.state shouldBe PullRequestState.Open
                reopened?.closedOn.shouldBeNull()
            }
        }

        @Test
        fun `merge - should keep the state for an unknown or superseded state`() {
            // GIVEN
            val existing = cached()

            // WHEN
            val superseded = DiffyBrowserSnapshotCacheMerge.merge(
                existing = existing,
                incoming = browserPullRequest(updatedOn = newer, state = "SUPERSEDED", title = "x")
            )
            val unknown = DiffyBrowserSnapshotCacheMerge.merge(
                existing = existing,
                incoming = browserPullRequest(updatedOn = newer, state = "WEIRD", title = "x")
            )

            // THEN
            assertSoftly {
                superseded?.state shouldBe PullRequestState.Open
                unknown?.state shouldBe PullRequestState.Open
                superseded?.closedOn.shouldBeNull()
            }
        }

        @Test
        fun `merge - should add an approver and match uuids with or without braces`() {
            // GIVEN a cached reviewer without approval and a snapshot that approves with a brace-less uuid
            val existing = cached(reviewers = listOf(cacheTestReviewer(uuid = "{ben}")))
            val incoming = browserPullRequest(updatedOn = newer, reviewers = listOf(browserReviewer(uuid = "BEN", approved = true)))

            // WHEN
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = incoming)

            // THEN
            merged.shouldNotBeNull()
            assertSoftly(merged) {
                approverUuids shouldBe setOf("BEN")
                reviewers.single().approved shouldBe true
                reviewers.single().needsWork shouldBe false
                reviewers.single().avatarUrl shouldBe "https://avatars.example/ben.png"
            }
        }

        @Test
        fun `merge - should mark needsWork for changes requested and leave other reviewers alone`() {
            // GIVEN two cached reviewers and a snapshot that mentions only one
            val existing = cached(
                reviewers = listOf(
                    cacheTestReviewer(uuid = "{ben}"),
                    cacheTestReviewer(uuid = "{cleo}", status = ReviewerStatus.Approved)
                ),
                approverUuids = setOf("{cleo}")
            )
            val incoming = browserPullRequest(
                updatedOn = newer,
                reviewers = listOf(browserReviewer(uuid = "{ben}", approved = false, state = "changes_requested"))
            )

            // WHEN
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = incoming)

            // THEN
            merged.shouldNotBeNull()
            assertSoftly(merged) {
                reviewers.first { reviewer -> reviewer.uuid == "{ben}" }.needsWork shouldBe true
                reviewers.first { reviewer -> reviewer.uuid == "{cleo}" }.approved shouldBe true
            }
        }

        @Test
        fun `merge - should keep the approvers when the snapshot carries no participants`() {
            // GIVEN cached approvers and a snapshot without any participant
            val existing = cached(approverUuids = setOf("{cleo}"))
            val incoming = browserPullRequest(updatedOn = newer, title = "Renamed")

            // WHEN
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = incoming)

            // THEN
            merged?.approverUuids shouldBe setOf("{cleo}")
        }

        @Test
        fun `merge - should never change the fetch time, author, branches or web url`() {
            // GIVEN a snapshot that changes everything it may change, with other values for the fields it must not touch
            val existing = cached()
            val incoming = browserPullRequest(
                updatedOn = newer,
                title = "Renamed",
                commentCount = 9,
                draft = true,
                authorDisplayName = "Someone else",
                sourceBranch = "other",
                destinationBranch = "other"
            ).copy(url = "https://bitbucket.org/elsewhere", createdOn = "2020-01-01T00:00:00+00:00")

            // WHEN
            val merged = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = incoming)

            // THEN
            merged.shouldNotBeNull()
            assertSoftly(merged) {
                lastFetchedAt shouldBe existing.lastFetchedAt
                author shouldBe existing.author
                sourceBranch shouldBe existing.sourceBranch
                destinationBranch shouldBe existing.destinationBranch
                webUrl shouldBe existing.webUrl
                createdOn shouldBe existing.createdOn
            }
        }
    }

    @Nested
    inner class OlderOrEqualSnapshot {
        @Test
        fun `merge - should return null for an older snapshot`() {
            // GIVEN a snapshot older than the entry
            val existing = cached().copy(updatedOn = CACHE_TEST_START + 5.hours)
            val incoming = browserPullRequest(updatedOn = "2026-11-01T10:00:00+00:00", commentCount = 99, title = "Old")

            // WHEN / THEN
            DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = incoming).shouldBeNull()
        }

        @Test
        fun `merge - should return null for identical data`() {
            // GIVEN a snapshot equal to the entry
            val incoming = browserPullRequest(commentCount = 0)

            // WHEN / THEN
            DiffyBrowserSnapshotCacheMerge.merge(existing = cached(), incoming = incoming).shouldBeNull()
        }

        @Test
        fun `merge - should only raise the comment count when updatedOn is equal`() {
            // GIVEN an entry with 3 comments and equal timestamps
            val existing = cached(commentCount = 3)

            // WHEN the snapshot counts more, fewer, and also changes the title
            val higher = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = browserPullRequest(commentCount = 5, title = "Changed"))
            val lower = DiffyBrowserSnapshotCacheMerge.merge(existing = existing, incoming = browserPullRequest(commentCount = 1))

            // THEN only the higher count is taken and the title stays
            assertSoftly {
                higher?.commentCount shouldBe 5
                higher?.title shouldBe existing.title
                lower.shouldBeNull()
            }
        }

        @Test
        fun `merge - should return null when updatedOn is missing or not a timestamp`() {
            assertSoftly {
                DiffyBrowserSnapshotCacheMerge.merge(existing = cached(), incoming = browserPullRequest(updatedOn = null, commentCount = 4)).shouldBeNull()
                DiffyBrowserSnapshotCacheMerge.merge(existing = cached(), incoming = browserPullRequest(updatedOn = "soon", commentCount = 4)).shouldBeNull()
            }
        }
    }

    @Nested
    inner class NewSummary {
        @Test
        fun `toSummary - should build an open summary with reviewers and approvers`() {
            // GIVEN a complete open pull request
            val incoming = browserPullRequest(
                id = 12L,
                commentCount = 4,
                reviewers = listOf(browserReviewer(uuid = "{ben}", approved = true)),
                otherParticipants = listOf(browserReviewer(uuid = "{ranger}", approved = true, displayName = "Ranger"))
            )

            // WHEN
            val summary = DiffyBrowserSnapshotCacheMerge.toSummary(incoming = incoming)

            // THEN
            summary.shouldNotBeNull()
            assertSoftly(summary) {
                id shouldBe 12L
                state shouldBe PullRequestState.Open
                commentCount shouldBe 4
                authorDisplayName shouldBe "Anna"
                authorAvatarUrl.shouldBeNull()
                approverUuids shouldBe setOf("{ben}", "{ranger}")
                reviewers.single().status shouldBe ReviewerStatus.Approved
            }
        }

        @Test
        fun `toSummary - should return null when a required field is missing or the state is not open`() {
            assertSoftly {
                DiffyBrowserSnapshotCacheMerge.toSummary(incoming = browserPullRequest(createdOn = null)).shouldBeNull()
                DiffyBrowserSnapshotCacheMerge.toSummary(incoming = browserPullRequest(updatedOn = "soon")).shouldBeNull()
                DiffyBrowserSnapshotCacheMerge.toSummary(incoming = browserPullRequest(sourceBranch = null)).shouldBeNull()
                DiffyBrowserSnapshotCacheMerge.toSummary(incoming = browserPullRequest(destinationBranch = " ")).shouldBeNull()
                DiffyBrowserSnapshotCacheMerge.toSummary(incoming = browserPullRequest(authorDisplayName = null)).shouldBeNull()
                DiffyBrowserSnapshotCacheMerge.toSummary(incoming = browserPullRequest(state = "MERGED")).shouldBeNull()
            }
        }
    }
}