package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState
import com.github.fnunezkanut.bbcloud.cache.MutableTestClock
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.cache.PullRequestListingIds
import com.github.fnunezkanut.bbcloud.cache.cacheTestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
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

/** The cache wiring of the Workspace "authored" and "assigned to me" services, with a mocked client and a fake clock. */
@Tag("unit")
class DiffyWorkspacePullRequestServicesCacheTest {
    private val clock = MutableTestClock()
    private val state = DiffyPullRequestCacheState.State()
    private val cache = DiffyPullRequestCache(store = { state }, clock = { clock.read() })
    private val client = mockk<BitbucketPullRequestClient>()
    private val tokens = mockk<DiffyAccessTokenProvider>()

    init {
        coEvery { tokens.bearerTokenOrNull() } returns "token"
        coEvery { client.getCurrentUser(apiToken = any()) } returns CurrentUser(uuid = "{me}", avatarUrl = null)
    }

    @Test
    fun `authored - a second automatic load is served from the snapshot with no list or user request`() {
        // GIVEN an authored listing loaded once
        val service = DiffyAuthoredPullRequestService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
        coEvery {
            client.listOpenAuthoredPullRequestsInWorkspace(workspace = "acme", selectedUserUuid = "{me}", apiToken = "token")
        } returns authoredPage(id = 1L, repoSlug = "payments")
        runBlocking { service.listAuthoredPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null) }

        // WHEN the tab loads again within a minute
        clock.advance(20.seconds)
        val result = runBlocking { service.listAuthoredPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null) }

        // THEN the snapshot answers and the list was requested once
        assertSoftly {
            result.shouldBeInstanceOf<AuthoredPullRequestListResult.Success>().pullRequests.map { it.summary.id } shouldContainExactly listOf(1L)
            coVerify(exactly = 1) {
                client.listOpenAuthoredPullRequestsInWorkspace(workspace = "acme", selectedUserUuid = "{me}", apiToken = "token")
            }
            coVerify(exactly = 1) { client.getCurrentUser(apiToken = "token") }
        }
    }

    @Test
    fun `authored - one snapshot filters differently for two project windows that exclude different repositories`() {
        // GIVEN a snapshot of two repositories, loaded by a window that excludes neither
        val service = DiffyAuthoredPullRequestService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
        coEvery {
            client.listOpenAuthoredPullRequestsInWorkspace(workspace = "acme", selectedUserUuid = "{me}", apiToken = "token")
        } returns AuthoredPullRequestPage(
            entries = listOf(
                AuthoredPullRequestEntry(summary = cacheTestSummary(id = 1L), repoSlug = "payments"),
                AuthoredPullRequestEntry(summary = cacheTestSummary(id = 2L), repoSlug = "billing")
            ),
            isTruncated = false
        )
        runBlocking { service.listAuthoredPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null) }

        // WHEN a window of the payments project reads the same snapshot
        val forPayments = runBlocking {
            service.listAuthoredPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = "Payments")
        }

        // THEN its own repository is hidden, and the snapshot still holds both
        assertSoftly {
            forPayments.shouldBeInstanceOf<AuthoredPullRequestListResult.Success>().pullRequests.map { it.repoSlug } shouldContainExactly listOf("billing")
            cache.listing(PullRequestListingIds.workspaceAuthored(workspace = "acme"))?.entries?.size shouldBe 2
        }
    }

    @Test
    fun `authored - an entry without a repository slug keeps the snapshot from being recorded`() {
        // GIVEN a listing where one row names no repository
        val service = DiffyAuthoredPullRequestService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
        coEvery {
            client.listOpenAuthoredPullRequestsInWorkspace(workspace = "acme", selectedUserUuid = "{me}", apiToken = "token")
        } returns AuthoredPullRequestPage(
            entries = listOf(
                AuthoredPullRequestEntry(summary = cacheTestSummary(id = 1L), repoSlug = "payments"),
                AuthoredPullRequestEntry(summary = cacheTestSummary(id = 2L), repoSlug = null)
            ),
            isTruncated = false
        )

        // WHEN it loads
        runBlocking { service.listAuthoredPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null) }

        // THEN no snapshot is stored, so the next load asks Bitbucket again
        cache.freshListing(PullRequestListingIds.workspaceAuthored(workspace = "acme")).shouldBeNull()
    }

    @Test
    fun `reviewing - only the repository whose listing is stale is requested again`() {
        // GIVEN payments was listed 30 seconds ago
        val service = DiffyReviewQueueService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
        coEvery {
            client.listOpenPullRequestsReviewedBy(workspace = "acme", repoSlug = "payments", reviewerUuid = "{me}", pageLen = any(), apiToken = "token")
        } returns ReviewerQueueQueryResult.Listed(pullRequests = listOf(cacheTestSummary(id = 10L)))
        runBlocking {
            service.listReviewingPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null, repoSlugs = listOf("payments"))
        }
        clock.advance(30.seconds)
        coEvery {
            client.listOpenPullRequestsReviewedBy(workspace = "acme", repoSlug = "billing", reviewerUuid = "{me}", pageLen = any(), apiToken = "token")
        } returns ReviewerQueueQueryResult.Listed(pullRequests = listOf(cacheTestSummary(id = 20L)))

        // WHEN both watched repositories load
        val result = runBlocking {
            service.listReviewingPullRequests(
                organizationUrl = ORGANIZATION,
                excludedRepoSlug = null,
                repoSlugs = listOf("payments", "billing")
            )
        }

        // THEN payments came from the cache and only billing was requested
        assertSoftly {
            result.shouldBeInstanceOf<ReviewingPullRequestListResult.Success>().pullRequests.map { it.summary.id } shouldContainExactly listOf(10L, 20L)
            coVerify(exactly = 1) {
                client.listOpenPullRequestsReviewedBy(workspace = "acme", repoSlug = "payments", reviewerUuid = "{me}", pageLen = any(), apiToken = "token")
            }
        }
    }

    @Test
    fun `reviewing - a skipped repository writes no listing`() {
        // GIVEN a repository the token cannot read
        val service = DiffyReviewQueueService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
        coEvery {
            client.listOpenPullRequestsReviewedBy(workspace = "acme", repoSlug = "archived", reviewerUuid = "{me}", pageLen = any(), apiToken = "token")
        } returns ReviewerQueueQueryResult.Skipped

        // WHEN it is queried
        runBlocking {
            service.listReviewingPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null, repoSlugs = listOf("archived"))
        }

        // THEN no listing is stored for it
        cache.listing(PullRequestListingIds.workspaceReviewing(workspace = "acme", repoSlug = "archived")).shouldBeNull()
    }

    @Test
    fun `reviewing - a failed repository falls back to its stored listing`() {
        // GIVEN payments listed once, then a request that fails
        val service = DiffyReviewQueueService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
        coEvery {
            client.listOpenPullRequestsReviewedBy(workspace = "acme", repoSlug = "payments", reviewerUuid = "{me}", pageLen = any(), apiToken = "token")
        } returns ReviewerQueueQueryResult.Listed(pullRequests = listOf(cacheTestSummary(id = 30L)))
        runBlocking {
            service.listReviewingPullRequests(organizationUrl = ORGANIZATION, excludedRepoSlug = null, repoSlugs = listOf("payments"))
        }
        clock.advance(120.seconds)
        coEvery {
            client.listOpenPullRequestsReviewedBy(workspace = "acme", repoSlug = "payments", reviewerUuid = "{me}", pageLen = any(), apiToken = "token")
        } returns ReviewerQueueQueryResult.Failed()

        // WHEN it loads again
        val result = runBlocking {
            service.listReviewingPullRequests(
                organizationUrl = ORGANIZATION,
                excludedRepoSlug = null,
                repoSlugs = listOf("payments"),
                refresh = PullRequestCacheRefresh.Force
            )
        }

        // THEN the stored row is shown and the load is not truncated
        assertSoftly {
            val success = result.shouldBeInstanceOf<ReviewingPullRequestListResult.Success>()
            success.pullRequests.map { it.summary.id } shouldContainExactly listOf(30L)
            success.isTruncated shouldBe false
        }
        cache.get(PullRequestCacheKey.of(workspace = "acme", repoSlug = "payments", prNum = 30L)).shouldNotBeNull()
    }

    private fun authoredPage(id: Long, repoSlug: String): AuthoredPullRequestPage {
        return AuthoredPullRequestPage(
            entries = listOf(AuthoredPullRequestEntry(summary = cacheTestSummary(id = id), repoSlug = repoSlug)),
            isTruncated = false
        )
    }

    private companion object {
        const val ORGANIZATION = "https://bitbucket.org/acme/"
    }
}