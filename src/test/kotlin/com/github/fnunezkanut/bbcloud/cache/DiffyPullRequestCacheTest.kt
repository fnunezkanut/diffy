package com.github.fnunezkanut.bbcloud.cache

import com.github.fnunezkanut.bbcloud.bitbucket.CurrentUser
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestDetails
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Tag("unit")
class DiffyPullRequestCacheTest {
    private class Harness(
        val limits: PullRequestCacheLimits = PullRequestCacheLimits(),
        val state: DiffyPullRequestCacheState.State = DiffyPullRequestCacheState.State()
    ) {
        val clock = MutableTestClock()
        val cache = DiffyPullRequestCache(store = { state }, clock = { clock.read() }, limits = limits)
    }

    @Test
    fun `upsert then get - should store the entry with its original-case names and lowercase key`() {
        // GIVEN a pull request cached under mixed-case names
        val harness = Harness()

        // WHEN it is read back by the lowercase key
        harness.cache.upsert(workspace = "AcmeCo", repoSlug = "Payments", summary = cacheTestSummary(id = 5L))
        val entry = harness.cache.get(PullRequestCacheKey.of(workspace = "acmeco", repoSlug = "payments", prNum = 5L))

        // THEN the names keep their case and the fetch time is now
        entry.shouldNotBeNull()
        assertSoftly(entry) {
            workspace shouldBe "AcmeCo"
            repoSlug shouldBe "Payments"
            key.workspace shouldBe "acmeco"
            key.repoSlug shouldBe "payments"
            lastFetchedAt shouldBe CACHE_TEST_START
        }
    }

    @Test
    fun `upsertDetails - should reset the fetch time while patch should not`() {
        // GIVEN an entry cached ten minutes ago
        val harness = Harness()
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 1L))
        harness.clock.advance(10.minutes)

        // WHEN a details response refreshes it
        harness.cache.upsertDetails(
            workspace = "workspace",
            repoSlug = "repo",
            details = PullRequestDetails(summary = cacheTestSummary(id = 1L), isApprovedByCurrentUser = false)
        )
        harness.clock.advance(5.minutes)
        harness.cache.patch(key = key(id = 1L)) { entry -> entry.copy(commentCount = 9) }

        // THEN the fetch time moved at the details read and stayed put at the optimistic patch
        harness.cache.lastFetchedAt(key(id = 1L)) shouldBe CACHE_TEST_START.plusMinutes(10)
        harness.cache.ageOf(key(id = 1L)) shouldBe 5.minutes
        harness.cache.get(key(id = 1L))?.commentCount shouldBe 9
    }

    @Test
    fun `upsert - should keep closedOn sticky while closed and clear it when reopened`() {
        // GIVEN a pull request that merges an hour later
        val harness = Harness()
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 2L))
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 2L, state = PullRequestState.Merged, updatedOn = CACHE_TEST_START.plusMinutes(60))
        )

        // WHEN a later late comment bumps updated_on while it is still merged
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 2L, state = PullRequestState.Merged, updatedOn = CACHE_TEST_START.plusMinutes(120))
        )

        // THEN the first closing time is kept
        harness.cache.get(key(id = 2L))?.closedOn shouldBe CACHE_TEST_START.plusMinutes(60)

        // AND WHEN the pull request is reopened
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 2L))

        // THEN the closing time is cleared
        harness.cache.get(key(id = 2L)).shouldNotBeNull().closedOn.shouldBeNull()
    }

    @Test
    fun `prune - should keep a closed entry 29 days after closing and drop it at 31 days`() {
        // GIVEN a pull request merged at the start
        val harness = Harness()
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 3L, state = PullRequestState.Merged, updatedOn = CACHE_TEST_START)
        )

        // WHEN 29 days pass
        harness.clock.advance(29.days)
        harness.cache.prune()

        // THEN it is still cached
        harness.cache.get(key(id = 3L)).shouldNotBeNull()

        // WHEN two more days pass
        harness.clock.advance(2.days)
        harness.cache.prune()

        // THEN it is dropped
        harness.cache.get(key(id = 3L)).shouldBeNull()
    }

    @Test
    fun `prune - should drop an open entry that Bitbucket has not reported for 31 days`() {
        // GIVEN an open pull request
        val harness = Harness()
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 4L))

        // WHEN 31 days pass without a new fetch
        harness.clock.advance(31.days)
        harness.cache.prune()

        // THEN the entry is dropped
        harness.cache.get(key(id = 4L)).shouldBeNull()
    }

    @Test
    fun `upsertAll - should evict the oldest entries past the cap`() {
        // GIVEN a cap of two entries and three pull requests fetched one minute apart
        val harness = Harness(limits = PullRequestCacheLimits(maxEntries = 2))
        for (id in 1L..3L) {
            harness.clock.advance(1.minutes)
            harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = id))
        }

        // THEN the oldest one is gone
        harness.cache.get(key(id = 1L)).shouldBeNull()
        harness.cache.get(key(id = 2L)).shouldNotBeNull()
        harness.cache.get(key(id = 3L)).shouldNotBeNull()
    }

    @Test
    fun `prune - should keep a closed entry still shown by a recent snapshot and drop it after the snapshot moves on`() {
        // GIVEN a merged pull request shown by the project listing 30 days after it merged
        val harness = Harness()
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 6L, state = PullRequestState.Merged, updatedOn = CACHE_TEST_START)
        )
        harness.clock.advance(30.days)
        harness.cache.recordListing(
            listingId = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo"),
            keys = listOf(key(id = 6L)),
            isTruncated = false
        )

        // WHEN a day later the entry is past retention but the snapshot still pins it
        harness.clock.advance(1.days)
        harness.cache.prune()

        // THEN it stays
        harness.cache.get(key(id = 6L)).shouldNotBeNull()

        // WHEN the listing is recorded again without it
        harness.cache.recordListing(
            listingId = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo"),
            keys = emptyList(),
            isTruncated = false
        )

        // THEN it is dropped
        harness.cache.get(key(id = 6L)).shouldBeNull()
    }

    @Test
    fun `upsert - should evict an unpinned entry before an older pinned one when the cap is reached`() {
        // GIVEN a cap of two, with the oldest entry shown by a listing
        val harness = Harness(limits = PullRequestCacheLimits(maxEntries = 2))
        val listingId = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 21L))
        harness.cache.recordListing(listingId = listingId, keys = listOf(key(id = 21L)), isTruncated = false)

        // WHEN two newer entries arrive, one minute apart
        harness.clock.advance(1.minutes)
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 22L))
        harness.clock.advance(1.minutes)
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 23L))

        // THEN the unpinned entry is evicted and the listed one stays
        harness.cache.get(key(id = 21L)).shouldNotBeNull()
        harness.cache.get(key(id = 22L)).shouldBeNull()
        harness.cache.get(key(id = 23L)).shouldNotBeNull()
    }

    @Test
    fun `prune - should invalidate a snapshot whose entry was dropped`() {
        // GIVEN an open pull request in a listing
        val harness = Harness()
        val listingId = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 7L))
        harness.cache.recordListing(listingId = listingId, keys = listOf(key(id = 7L)), isTruncated = false)

        // WHEN the open entry goes unfetched for 31 days while the snapshot is older than the retention window
        harness.clock.advance(31.days)
        harness.cache.prune()

        // THEN the snapshot is gone too
        harness.cache.listing(listingId).shouldBeNull()
    }

    @Test
    fun `bindUser - should clear only after two calls in a row report the same new owner`() {
        // GIVEN a cache owned by one account with an entry
        val harness = Harness()
        harness.cache.bindUser(userUuid = "{owner}")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 8L))

        // WHEN a different account is seen once
        harness.cache.bindUser(userUuid = "{other}")

        // THEN the entry survives
        harness.cache.get(key(id = 8L)).shouldNotBeNull()

        // WHEN the same account, written with other case and braces, is seen again
        harness.cache.bindUser(userUuid = "{OTHER}")

        // THEN the cache is cleared and the new owner is stored
        harness.cache.get(key(id = 8L)).shouldBeNull()
        harness.state.ownerUserUuid shouldBe "{OTHER}"
    }

    @Test
    fun `bindUser - should not clear when the owner matches or a matching call resets the pending change`() {
        // GIVEN a cache owned by one account with an entry
        val harness = Harness()
        harness.cache.bindUser(userUuid = "{owner}")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 9L))

        // WHEN a different account is seen, then the owner again, then the different account once more
        harness.cache.bindUser(userUuid = "{other}")
        harness.cache.bindUser(userUuid = "{Owner}")
        harness.cache.bindUser(userUuid = "{other}")

        // THEN nothing is cleared, because the pending change was reset in between
        harness.cache.get(key(id = 9L)).shouldNotBeNull()
        harness.state.ownerUserUuid shouldBe "{owner}"
    }

    @Test
    fun `upsert - should keep reviewers and approvers from a listing that omits participants`() {
        // GIVEN an entry with one reviewer who approved, fetched with participants
        val harness = Harness()
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(
                id = 10L,
                reviewers = listOf(cacheTestReviewer(uuid = "{ben}", status = ReviewerStatus.Approved)),
                approverUuids = setOf("{ben}")
            )
        )

        // WHEN a listing without participants returns the same update time
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 10L))

        // THEN the approval survives
        assertSoftly(harness.cache.get(key(id = 10L))) {
            this.shouldNotBeNull()
            reviewers.map { reviewer -> reviewer.uuid } shouldContainExactly listOf("{ben}")
            approverUuids shouldBe setOf("{ben}")
        }

        // WHEN the pull request changed since then
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 10L, updatedOn = CACHE_TEST_START.plusMinutes(1))
        )

        // THEN the empty participant list replaces the old one
        harness.cache.get(key(id = 10L)).shouldNotBeNull().reviewers.shouldBeEmpty()
    }

    @Test
    fun `freshListing - should serve a listing younger than 60 seconds and not one 61 seconds old`() {
        // GIVEN a listing recorded now
        val harness = Harness()
        val listingId = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 11L))
        harness.cache.recordListing(listingId = listingId, keys = listOf(key(id = 11L)), isTruncated = false)

        // WHEN 59 seconds pass
        harness.clock.advance(59.seconds)

        // THEN it is fresh
        harness.cache.freshListing(listingId).shouldNotBeNull()

        // WHEN 61 seconds pass
        harness.clock.advance(2.seconds)

        // THEN it is not fresh, but the stale fallback still reads it
        harness.cache.freshListing(listingId).shouldBeNull()
        harness.cache.listing(listingId).shouldNotBeNull()
    }

    @Test
    fun `recordListing - should replace the previous snapshot so a pull request that left the listing disappears`() {
        // GIVEN a listing with two pull requests
        val harness = Harness()
        val listingId = PullRequestListingIds.workspaceReviewing(workspace = "workspace", repoSlug = "repo")
        harness.cache.upsertAll(
            workspace = "workspace",
            repoSlug = "repo",
            summaries = listOf(cacheTestSummary(id = 12L), cacheTestSummary(id = 13L))
        )
        harness.cache.recordListing(listingId = listingId, keys = listOf(key(id = 12L), key(id = 13L)), isTruncated = false)

        // WHEN the next listing only has the second one
        harness.cache.recordListing(listingId = listingId, keys = listOf(key(id = 13L)), isTruncated = false)

        // THEN only the second one is listed
        harness.cache.listing(listingId)?.entries?.map { entry -> entry.key.prNum } shouldContainExactly listOf(13L)
    }

    @Test
    fun `removeFromOpenListings - should remove the pull request from reviewing snapshots but keep the entry`() {
        // GIVEN a pull request in a reviewing listing
        val harness = Harness()
        val listingId = PullRequestListingIds.workspaceReviewing(workspace = "workspace", repoSlug = "repo")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 14L))
        harness.cache.recordListing(listingId = listingId, keys = listOf(key(id = 14L)), isTruncated = false)

        // WHEN it is removed from open listings
        harness.cache.removeFromOpenListings(key = key(id = 14L))

        // THEN the listing is empty and the entry is still cached
        harness.cache.listing(listingId)?.entries?.shouldBeEmpty()
        harness.cache.get(key(id = 14L)).shouldNotBeNull()
    }

    @Test
    fun `invalidateListingsContaining - should mark only listings that show the pull request as not fresh`() {
        // GIVEN two listings, one showing the pull request
        val harness = Harness()
        val projectListing = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo")
        val otherListing = PullRequestListingIds.project(workspace = "workspace", repoSlug = "other")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 15L))
        harness.cache.recordListing(listingId = projectListing, keys = listOf(key(id = 15L)), isTruncated = false)
        harness.cache.recordListing(listingId = otherListing, keys = emptyList(), isTruncated = false)

        // WHEN the pull request changes
        harness.cache.invalidateListingsContaining(key = key(id = 15L))

        // THEN only the listing showing it is stale
        harness.cache.freshListing(projectListing).shouldBeNull()
        harness.cache.freshListing(otherListing).shouldNotBeNull()
    }

    @Test
    fun `dropListingsForRepo - should drop the project and reviewing snapshots of one repository only`() {
        // GIVEN project and reviewing listings for a repository
        val harness = Harness()
        val projectListing = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo")
        val reviewingListing = PullRequestListingIds.workspaceReviewing(workspace = "workspace", repoSlug = "repo")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 16L))
        harness.cache.recordListing(listingId = projectListing, keys = listOf(key(id = 16L)), isTruncated = false)
        harness.cache.recordListing(listingId = reviewingListing, keys = listOf(key(id = 16L)), isTruncated = false)

        // WHEN the repository is dropped
        harness.cache.dropListingsForRepo(workspace = "workspace", repoSlug = "repo")

        // THEN both listings are gone and the entry stays
        harness.cache.listing(projectListing).shouldBeNull()
        harness.cache.listing(reviewingListing).shouldBeNull()
        harness.cache.get(key(id = 16L)).shouldNotBeNull()
    }

    @Test
    fun `persistence - should load entries and listings into a new cache over the same state`() {
        // GIVEN a cache that wrote an entry and a listing
        val harness = Harness()
        harness.cache.upsert(workspace = "AcmeCo", repoSlug = "Payments", summary = cacheTestSummary(id = 17L))
        harness.cache.recordListing(
            listingId = PullRequestListingIds.project(workspace = "AcmeCo", repoSlug = "Payments"),
            keys = listOf(PullRequestCacheKey.of(workspace = "AcmeCo", repoSlug = "Payments", prNum = 17L)),
            isTruncated = true
        )

        // WHEN a new cache reads the same persisted state, as after a restart
        val restarted = DiffyPullRequestCache(store = { harness.state }, clock = { harness.clock.read() })

        // THEN the entry and the listing are there
        restarted.get(PullRequestCacheKey.of(workspace = "AcmeCo", repoSlug = "Payments", prNum = 17L)).shouldNotBeNull()
        assertSoftly(restarted.listing(PullRequestListingIds.project(workspace = "AcmeCo", repoSlug = "Payments"))) {
            this.shouldNotBeNull()
            isTruncated shouldBe true
            entries.map { entry -> entry.key.prNum } shouldContainExactly listOf(17L)
        }
    }

    @Test
    fun `persistence - should skip corrupt rows without failing the load`() {
        // GIVEN a stored state with a garbage row next to a good one
        val harness = Harness()
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 18L))
        harness.state.entries.add("garbage")

        // WHEN a new cache loads it
        val restarted = DiffyPullRequestCache(store = { harness.state }, clock = { harness.clock.read() })

        // THEN the good entry loads and the garbage is ignored
        restarted.get(key(id = 18L)).shouldNotBeNull()
        restarted.entriesForRepo(workspace = "workspace", repoSlug = "repo", includeClosed = true).size shouldBe 1
    }

    @Test
    fun `patch - should set a merged state without a closing time until the next fetch sets it`() {
        // GIVEN an open pull request
        val harness = Harness()
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 19L))

        // WHEN the plugin merges it, which patches the entry
        harness.clock.advance(3.minutes)
        harness.cache.patch(key = key(id = 19L)) { entry -> entry.copy(state = PullRequestState.Merged) }

        // THEN the entry is merged with no closing time
        assertSoftly(harness.cache.get(key(id = 19L))) {
            this.shouldNotBeNull()
            state shouldBe PullRequestState.Merged
            closedOn.shouldBeNull()
        }

        // WHEN the next fetch reports it merged at a later time
        harness.cache.upsert(
            workspace = "workspace",
            repoSlug = "repo",
            summary = cacheTestSummary(id = 19L, state = PullRequestState.Merged, updatedOn = CACHE_TEST_START.plusMinutes(3))
        )

        // THEN the closing time comes from updated_on
        harness.cache.get(key(id = 19L))?.closedOn shouldBe CACHE_TEST_START.plusMinutes(3)
    }

    @Test
    fun `clear - should tell the owner of the stored notifications`() {
        // GIVEN a cache with a clear callback
        var cleared = 0
        val cache = DiffyPullRequestCache(
            store = { DiffyPullRequestCacheState.State() },
            clock = { Instant.parse("2026-11-08T10:00:00Z") },
            onCleared = { cleared++ }
        )

        // WHEN
        cache.clear()

        // THEN
        cleared shouldBe 1
    }

    @Test
    fun `clear - should empty entries, listings, and the owner`() {
        // GIVEN a cache with an owner, an entry, and a listing
        val harness = Harness()
        harness.cache.bindUser(userUuid = "{owner}")
        harness.cache.upsert(workspace = "workspace", repoSlug = "repo", summary = cacheTestSummary(id = 20L))
        harness.cache.recordListing(
            listingId = PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo"),
            keys = listOf(key(id = 20L)),
            isTruncated = false
        )

        // WHEN it is cleared
        harness.cache.clear()

        // THEN nothing is left
        harness.cache.get(key(id = 20L)).shouldBeNull()
        harness.cache.listing(PullRequestListingIds.project(workspace = "workspace", repoSlug = "repo")).shouldBeNull()
        harness.state.ownerUserUuid.orEmpty() shouldBe ""
    }

    @Test
    fun `cachedCurrentUser - should return the remembered user only for the same token hash`() {
        // GIVEN a remembered current user
        val harness = Harness()
        val user = CurrentUser(uuid = "{owner}", avatarUrl = null)
        harness.cache.rememberCurrentUser(tokenHash = 42, user = user)

        // THEN the same token hash returns it and another does not
        harness.cache.cachedCurrentUser(tokenHash = 42) shouldBe user
        harness.cache.cachedCurrentUser(tokenHash = 43).shouldBeNull()
    }

    private fun key(id: Long): PullRequestCacheKey {
        return PullRequestCacheKey.of(workspace = "workspace", repoSlug = "repo", prNum = id)
    }

    private fun kotlin.time.Instant.plusMinutes(minutes: Long): kotlin.time.Instant {
        return this + minutes.minutes
    }
}