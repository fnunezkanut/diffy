package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CachedPullRequestRowTest {
    @Test
    fun `encodeEntry then decodeEntry - should round trip every field`() {
        // GIVEN an entry with a closing time, reviewers, and approvers
        val entry = CachedPullRequestMapper.fromSummary(
            workspace = "AcmeCo",
            repoSlug = "Payments",
            summary = cacheTestSummary(
                id = 42L,
                reviewers = listOf(cacheTestReviewer(status = ReviewerStatus.Approved)),
                approverUuids = setOf("{ben}")
            ),
            fetchedAt = CACHE_TEST_START,
            closedOn = CACHE_TEST_START
        )

        // WHEN it is encoded and decoded
        val decoded = CachedPullRequestRow.decodeEntry(encoded = CachedPullRequestRow.encodeEntry(entry = entry))

        // THEN the decoded entry equals the original
        decoded shouldBe entry
    }

    @Test
    fun `decodeEntry - should return null for a blank row`() {
        CachedPullRequestRow.decodeEntry(encoded = "") shouldBe null
    }

    @Test
    fun `decodeEntry - should return null for a corrupt row`() {
        CachedPullRequestRow.decodeEntry(encoded = "{not json") shouldBe null
    }

    @Test
    fun `decodeEntry - should return null for an unknown schema version`() {
        // GIVEN a valid row with its version changed
        val encoded = CachedPullRequestRow.encodeEntry(entry = sampleEntry())
            .replace("\"v\":${CachedPullRequestRow.VERSION}", "\"v\":99")

        // THEN the row is skipped rather than decoded
        CachedPullRequestRow.decodeEntry(encoded = encoded).shouldBeNull()
    }

    @Test
    fun `decodeEntry - should return null for an unknown pull request state`() {
        // GIVEN a valid row whose state is replaced by a value that does not exist
        val encoded = CachedPullRequestRow.encodeEntry(entry = sampleEntry()).replace("\"s\":\"Open\"", "\"s\":\"Archived\"")

        // THEN the row is skipped
        CachedPullRequestRow.decodeEntry(encoded = encoded).shouldBeNull()
    }

    @Test
    fun `decodeEntry - should ignore unknown json keys`() {
        // GIVEN a valid row with an extra field from a newer version
        val entry = sampleEntry()
        val encoded = CachedPullRequestRow.encodeEntry(entry = entry).replaceFirst("{", "{\"future\":\"value\",")

        // WHEN it is decoded
        val decoded = CachedPullRequestRow.decodeEntry(encoded = encoded)

        // THEN the known fields still load
        decoded shouldBe entry
    }

    @Test
    fun `encodeListing then decodeListing - should round trip keys and flags`() {
        // GIVEN a listing snapshot marked invalid and truncated
        val snapshot = ListingSnapshot(
            listingId = PullRequestListingIds.project(workspace = "Workspace", repoSlug = "Repo"),
            keys = listOf(
                PullRequestCacheKey.of(workspace = "workspace", repoSlug = "repo", prNum = 3L),
                PullRequestCacheKey.of(workspace = "workspace", repoSlug = "repo", prNum = 1L)
            ),
            fetchedAt = CACHE_TEST_START,
            isTruncated = true,
            isValid = false
        )

        // WHEN it is encoded and decoded
        val decoded = CachedPullRequestRow.decodeListing(encoded = CachedPullRequestRow.encodeListing(snapshot = snapshot))

        // THEN the snapshot is unchanged
        decoded shouldBe snapshot
    }

    private fun sampleEntry(): CachedPullRequest {
        return CachedPullRequestMapper.fromSummary(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 7L),
            fetchedAt = CACHE_TEST_START,
            closedOn = null
        )
    }
}