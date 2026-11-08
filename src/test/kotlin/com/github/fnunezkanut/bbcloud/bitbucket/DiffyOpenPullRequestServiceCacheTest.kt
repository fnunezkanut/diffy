package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.CACHE_TEST_START
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState
import com.github.fnunezkanut.bbcloud.cache.MutableTestClock
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.cache.PullRequestListingIds
import com.github.fnunezkanut.bbcloud.cache.cacheTestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** Exercises the cache wiring of [DiffyOpenPullRequestService] with a mocked Bitbucket client and a fake clock. */
@Tag("unit")
class DiffyOpenPullRequestServiceCacheTest {
    private val clock = MutableTestClock()
    private val state = DiffyPullRequestCacheState.State()
    private val cache = DiffyPullRequestCache(store = { state }, clock = { clock.read() })
    private val client = mockk<BitbucketPullRequestClient>()
    private val tokens = mockk<DiffyAccessTokenProvider>()
    private val service = DiffyOpenPullRequestService(
        accessTokenProvider = tokens,
        pullRequestClient = client,
        readRemoteUrl = { "https://bitbucket.org/acme/payments.git" },
        pullRequestCache = cache
    )

    init {
        coEvery { tokens.bearerTokenOrNull() } returns "token"
        coEvery { client.getCurrentUser(apiToken = any()) } returns CurrentUser(uuid = "{me}", avatarUrl = null)
    }

    @Test
    fun `listRecentPullRequests - an automatic load within 60 seconds is served from the cache without a request`() {
        // GIVEN a live listing already stored
        val listed = cacheTestSummary(id = 1L)
        coEvery { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Success(pullRequests = listOf(listed))
        runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // WHEN the tab loads again 30 seconds later
        clock.advance(30.seconds)
        val result = runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // THEN the cached rows are returned and Bitbucket was asked once
        assertSoftly {
            result.shouldBeInstanceOf<PullRequestListResult.Success>().pullRequests shouldBe listOf(listed)
            result.shouldBeInstanceOf<PullRequestListResult.Success>().source shouldBe PullRequestListSource.Cache
            coVerify(exactly = 1) { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") }
        }
    }

    @Test
    fun `listRecentPullRequests - a load after 61 seconds asks Bitbucket again`() {
        // GIVEN a live listing already stored
        coEvery { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Success(pullRequests = listOf(cacheTestSummary(id = 1L)))
        runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // WHEN the tab loads 61 seconds later
        clock.advance(61.seconds)
        val result = runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // THEN the result is live
        result.shouldBeInstanceOf<PullRequestListResult.Success>().source shouldBe PullRequestListSource.Live
        coVerify(exactly = 2) { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") }
    }

    @Test
    fun `listRecentPullRequests - a forced refresh always asks Bitbucket`() {
        // GIVEN a fresh stored listing
        coEvery { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Success(pullRequests = listOf(cacheTestSummary(id = 1L)))
        runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // WHEN the user refreshes at once
        val result = runBlocking {
            service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION, refresh = PullRequestCacheRefresh.Force)
        }

        // THEN Bitbucket answered
        result.shouldBeInstanceOf<PullRequestListResult.Success>().source shouldBe PullRequestListSource.Live
        coVerify(exactly = 2) { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") }
    }

    @Test
    fun `listRecentPullRequests - a failed request serves the stored listing as a stale fallback`() {
        // GIVEN a stored listing, then a request that fails
        val listed = cacheTestSummary(id = 2L)
        coEvery { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Success(pullRequests = listOf(listed))
        runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }
        clock.advance(120.seconds)
        coEvery { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Failed()

        // WHEN the next load fails
        val result = runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // THEN the stored rows are shown as a stale fallback instead of the error
        assertSoftly {
            result.shouldBeInstanceOf<PullRequestListResult.Success>().pullRequests shouldBe listOf(listed)
            result.shouldBeInstanceOf<PullRequestListResult.Success>().source shouldBe PullRequestListSource.StaleFallback
        }
    }

    @Test
    fun `listRecentPullRequests - a failed request without a stored listing stays a failure`() {
        // GIVEN nothing stored and a request that fails
        coEvery { client.listRecentPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Failed()

        // WHEN it loads
        val result = runBlocking { service.listRecentPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // THEN the failure is reported
        result.shouldBeInstanceOf<PullRequestListResult.Failed>()
    }

    @Test
    fun `listAllOpenPullRequests - is never served from the cache and never writes to it`() {
        // GIVEN a cached entry with participants
        cache.upsert(workspace = "acme", repoSlug = "payments", summary = cacheTestSummary(id = 3L, approverUuids = setOf("{ben}")))
        coEvery { client.listAllOpenPullRequests(workspace = "acme", repoSlug = "payments", apiToken = "token") } returns
            PullRequestListResult.Success(pullRequests = listOf(cacheTestSummary(id = 4L)))

        // WHEN the full open set is listed
        val result = runBlocking { service.listAllOpenPullRequests(projectBasePath = PROJECT, organizationUrl = ORGANIZATION) }

        // THEN the live answer is returned and the cache holds only what it held before
        assertSoftly {
            result.shouldBeInstanceOf<PullRequestListResult.Success>().pullRequests.map { it.id } shouldBe listOf(4L)
            cache.get(key(id = 4L)).shouldBeNull()
            cache.get(key(id = 3L)).shouldNotBeNull().approverUuids shouldBe setOf("{ben}")
        }
    }

    @Test
    fun `pullRequestDetails - a successful read stores the details and resets the fetch time`() {
        // GIVEN details that Bitbucket returns ten minutes after the entry was first stored
        cache.upsert(workspace = "acme", repoSlug = "payments", summary = cacheTestSummary(id = 5L))
        clock.advance(10.seconds)
        coEvery {
            client.getPullRequestDetails(workspace = "acme", repoSlug = "payments", pullRequestId = 5L, currentUserUuid = "{me}", apiToken = "token")
        } returns PullRequestDetails(summary = cacheTestSummary(id = 5L, commentCount = 7), isApprovedByCurrentUser = false)

        // WHEN the details are loaded
        runBlocking { service.pullRequestDetails(projectBasePath = PROJECT, organizationUrl = ORGANIZATION, pullRequestId = 5L) }

        // THEN the entry holds the new comment count and the fetch time moved
        assertSoftly(cache.get(key(id = 5L)).shouldNotBeNull()) {
            commentCount shouldBe 7
            lastFetchedAt shouldBe CACHE_TEST_START + 10.seconds
        }
    }

    @Test
    fun `performAction - a successful merge marks the entry merged and drops it from the open listings`() {
        // GIVEN an open entry shown by the reviewing listing
        val listingId = PullRequestListingIds.workspaceReviewing(workspace = "acme", repoSlug = "payments")
        cache.upsert(workspace = "acme", repoSlug = "payments", summary = cacheTestSummary(id = 6L))
        cache.recordListing(listingId = listingId, keys = listOf(key(id = 6L)), isTruncated = false)
        coEvery {
            client.performAction(workspace = "acme", repoSlug = "payments", pullRequest = any(), action = PullRequestAction.Merge, apiToken = "token")
        } returns PullRequestActionResult.Success

        // WHEN the pull request is merged
        val result = runBlocking {
            service.performAction(
                projectBasePath = PROJECT,
                organizationUrl = ORGANIZATION,
                pullRequest = cacheTestSummary(id = 6L),
                action = PullRequestAction.Merge
            )
        }

        // THEN the entry is merged and no longer listed as open
        assertSoftly {
            result shouldBe PullRequestActionResult.Success
            cache.get(key(id = 6L)).shouldNotBeNull().state shouldBe PullRequestState.Merged
            cache.listing(listingId)?.entries?.isEmpty() shouldBe true
        }
    }

    @Test
    fun `performAction - a failed action leaves the cached entry untouched`() {
        // GIVEN an open entry and an action Bitbucket rejects
        cache.upsert(workspace = "acme", repoSlug = "payments", summary = cacheTestSummary(id = 7L))
        coEvery {
            client.performAction(workspace = "acme", repoSlug = "payments", pullRequest = any(), action = PullRequestAction.Merge, apiToken = "token")
        } returns PullRequestActionResult.Failed(message = "conflict")

        // WHEN the merge fails
        runBlocking {
            service.performAction(
                projectBasePath = PROJECT,
                organizationUrl = ORGANIZATION,
                pullRequest = cacheTestSummary(id = 7L),
                action = PullRequestAction.Merge
            )
        }

        // THEN the entry is still open
        cache.get(key(id = 7L)).shouldNotBeNull().state shouldBe PullRequestState.Open
    }

    @Test
    fun `addComment and delete - a created comment adds one and a delete takes one away without going below zero`() {
        // GIVEN an entry with no comments
        cache.upsert(workspace = "acme", repoSlug = "payments", summary = cacheTestSummary(id = 8L, commentCount = 0))
        coEvery {
            client.createComment(
                workspace = "acme",
                repoSlug = "payments",
                pullRequestId = 8L,
                content = "Looks good",
                apiToken = "token",
                parentCommentId = null,
                inlinePath = null,
                inlineLine = null,
                inlineSide = any()
            )
        } returns PullRequestActionResult.Success
        coEvery {
            client.deleteComment(workspace = "acme", repoSlug = "payments", pullRequestId = 8L, commentId = 99L, apiToken = "token")
        } returns PullRequestActionResult.Success

        // WHEN a comment is added, then deleted twice
        runBlocking { service.addComment(projectBasePath = PROJECT, organizationUrl = ORGANIZATION, pullRequestId = 8L, content = "Looks good") }
        val afterAdd = cache.get(key(id = 8L)).shouldNotBeNull().commentCount
        runBlocking {
            service.applyCommentChange(
                projectBasePath = PROJECT,
                organizationUrl = ORGANIZATION,
                pullRequestId = 8L,
                change = CommentChange.Delete(commentId = 99L)
            )
        }
        runBlocking {
            service.applyCommentChange(
                projectBasePath = PROJECT,
                organizationUrl = ORGANIZATION,
                pullRequestId = 8L,
                change = CommentChange.Delete(commentId = 99L)
            )
        }

        // THEN the count went 0, 1, 0 and not below zero
        afterAdd shouldBe 1
        cache.get(key(id = 8L)).shouldNotBeNull().commentCount shouldBe 0
    }

    private fun key(id: Long): PullRequestCacheKey {
        return PullRequestCacheKey.of(workspace = "acme", repoSlug = "payments", prNum = id)
    }

    private companion object {
        const val PROJECT = "/Users/me/Projects/payments"
        const val ORGANIZATION = "https://bitbucket.org/acme/"
    }
}