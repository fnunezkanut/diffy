package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState
import com.github.fnunezkanut.bbcloud.cache.MutableTestClock
import com.github.fnunezkanut.bbcloud.cache.PullRequestListingIds
import com.github.fnunezkanut.bbcloud.cache.cacheTestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
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