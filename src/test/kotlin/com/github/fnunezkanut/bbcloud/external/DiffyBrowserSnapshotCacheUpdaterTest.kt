package com.github.fnunezkanut.bbcloud.external

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.cache.CACHE_TEST_START
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState
import com.github.fnunezkanut.bbcloud.cache.MutableTestClock
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestListingIds
import com.github.fnunezkanut.bbcloud.cache.cacheTestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class DiffyBrowserSnapshotCacheUpdaterTest {
    private val newer = "2026-11-02T10:00:00+00:00"
    private val state = DiffyPullRequestCacheState.State()
    private val clock = MutableTestClock()
    private val cache = DiffyPullRequestCache(store = { state }, clock = { clock.read() })
    private var workspace: String? = "workspace"
    private val updater = DiffyBrowserSnapshotCacheUpdater(cache = cache, configuredWorkspace = { workspace })
    private val listingId = PullRequestListingIds.workspaceReviewing(workspace = "workspace", repoSlug = "repo")

    private fun key(id: Long): PullRequestCacheKey {
        return PullRequestCacheKey.of(workspace = "workspace", repoSlug = "repo", prNum = id)
    }

    private fun cacheOpenPullRequest(id: Long, commentCount: Int = 0) {
        cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = id, commentCount = commentCount))
        cache.recordListing(listingId = listingId, keys = listOf(key(id = id)), isTruncated = false)
    }

    @Test
    fun `apply - should patch a cached pull request and invalidate its listings`() {
        // GIVEN a cached pull request in a fresh listing
        cacheOpenPullRequest(id = 1L)
        cache.freshListing(listingId = listingId).shouldNotBeNull()

        // WHEN a newer snapshot counts three comments
        val result = updater.apply(snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 1L, updatedOn = newer, commentCount = 3))))

        // THEN the entry changed, kept its fetch time, and the listing is no longer fresh
        val entry = cache.get(key = key(id = 1L))
        entry.shouldNotBeNull()
        assertSoftly {
            result shouldBe CacheUpdateResult(updated = 1)
            entry.commentCount shouldBe 3
            entry.lastFetchedAt shouldBe CACHE_TEST_START
            cache.freshListing(listingId = listingId).shouldBeNull()
        }
    }

    @Test
    fun `apply - should insert an open pull request that is not cached`() {
        // GIVEN an empty cache
        // WHEN a snapshot brings a complete open pull request
        val result = updater.apply(snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 7L, commentCount = 2))))

        // THEN it is stored as an open entry
        val entry = cache.get(key = key(id = 7L))
        entry.shouldNotBeNull()
        assertSoftly {
            result shouldBe CacheUpdateResult(inserted = 1)
            entry.state shouldBe PullRequestState.Open
            entry.commentCount shouldBe 2
            entry.title shouldBe "Add the cache"
        }
    }

    @Test
    fun `apply - should skip an incomplete or closed pull request that is not cached`() {
        // GIVEN pull requests without a branch and with a merged state
        val snapshot = browserSnapshot(
            pullRequests = listOf(
                browserPullRequest(id = 1L, sourceBranch = null),
                browserPullRequest(id = 2L, state = "MERGED")
            )
        )

        // WHEN
        val result = updater.apply(snapshot = snapshot)

        // THEN nothing is stored
        assertSoftly {
            result shouldBe CacheUpdateResult(skippedIncomplete = 2)
            cache.get(key = key(id = 1L)).shouldBeNull()
            cache.get(key = key(id = 2L)).shouldBeNull()
        }
    }

    @Test
    fun `apply - should remove a pull request that became merged from the open listings`() {
        // GIVEN a cached open pull request in a reviewing listing
        cacheOpenPullRequest(id = 1L)

        // WHEN the snapshot reports it as merged
        val result = updater.apply(snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 1L, updatedOn = newer, state = "MERGED"))))

        // THEN the entry is merged and the open listing no longer shows it
        assertSoftly {
            result shouldBe CacheUpdateResult(updated = 1, becameClosed = 1)
            cache.get(key = key(id = 1L))?.state shouldBe PullRequestState.Merged
            cache.listing(listingId = listingId)?.entries shouldBe emptyList()
        }
    }

    @Test
    fun `apply - should count a mixed snapshot`() {
        // GIVEN one pull request to update, one that is up to date, one to insert and one from another workspace
        cacheOpenPullRequest(id = 1L)
        cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 2L))
        val snapshot = browserSnapshot(
            pullRequests = listOf(
                browserPullRequest(id = 1L, updatedOn = newer, title = "Changed"),
                browserPullRequest(id = 2L),
                browserPullRequest(id = 3L),
                browserPullRequest(id = 4L, workspace = "elsewhere")
            )
        )

        // WHEN
        val result = updater.apply(snapshot = snapshot)

        // THEN
        result shouldBe CacheUpdateResult(updated = 1, inserted = 1, unchanged = 1, otherWorkspace = 1)
    }

    @Test
    fun `apply - should compare the workspace ignoring case and apply nothing without a configured workspace`() {
        // GIVEN a cached pull request
        cacheOpenPullRequest(id = 1L)
        val snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 1L, workspace = "WorkSpace", updatedOn = newer, commentCount = 5)))

        // WHEN the configured workspace differs in case, and then is missing
        workspace = "WORKSPACE"
        val matched = updater.apply(snapshot = snapshot)
        workspace = null
        val unconfigured = updater.apply(
            snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 1L, updatedOn = "2026-11-03T10:00:00+00:00", commentCount = 9)))
        )

        // THEN the first applied and the second touched nothing
        assertSoftly {
            matched.updated shouldBe 1
            unconfigured shouldBe CacheUpdateResult(otherWorkspace = 1)
            cache.get(key = key(id = 1L))?.commentCount shouldBe 5
        }
    }

    @Test
    fun `apply - should invalidate the listings of an open pull request that vanished from a complete snapshot`() {
        // GIVEN a pull request seen in the first snapshot
        cacheOpenPullRequest(id = 1L)
        updater.apply(snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 1L))))
        cache.freshListing(listingId = listingId).shouldNotBeNull()

        // WHEN the next complete snapshot no longer lists it
        val result = updater.apply(snapshot = browserSnapshot(pullRequests = emptyList()))

        // THEN its listings are invalidated and the entry is left as it was
        assertSoftly {
            result shouldBe CacheUpdateResult(disappeared = 1)
            cache.freshListing(listingId = listingId).shouldBeNull()
            cache.get(key = key(id = 1L))?.state shouldBe PullRequestState.Open
        }
    }

    @Test
    fun `apply - should not treat a pull request as vanished when the snapshot is truncated`() {
        // GIVEN a pull request seen in the first snapshot
        cacheOpenPullRequest(id = 1L)
        updater.apply(snapshot = browserSnapshot(pullRequests = listOf(browserPullRequest(id = 1L))))

        // WHEN a truncated snapshot lacks it
        val truncated = updater.apply(snapshot = browserSnapshot(pullRequests = emptyList(), truncatedPullRequests = true))
        val stillFresh = cache.freshListing(listingId = listingId)

        // AND a later complete snapshot still lacks it
        val complete = updater.apply(snapshot = browserSnapshot(pullRequests = emptyList()))

        // THEN only the complete one reports it, because the truncated one kept the earlier keys
        assertSoftly {
            truncated.disappeared shouldBe 0
            stillFresh.shouldNotBeNull()
            complete.disappeared shouldBe 1
        }
    }
}