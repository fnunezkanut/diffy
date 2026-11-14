package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache
import com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCacheState
import com.github.fnunezkanut.bbcloud.cache.MutableTestClock
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.cache.cacheTestSummary
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

@Tag("unit")
class DiffyWatchedProjectsPullRequestServiceTest {
    private val clock = MutableTestClock()
    private val cache = DiffyPullRequestCache(store = { DiffyPullRequestCacheState.State() }, clock = { clock.read() })
    private val client = mockk<BitbucketPullRequestClient>()
    private val tokens = mockk<DiffyAccessTokenProvider>()
    private val openService = DiffyOpenPullRequestService(accessTokenProvider = tokens, pullRequestClient = client, pullRequestCache = cache)
    private val service = DiffyWatchedProjectsPullRequestService(
        accessTokenProvider = tokens,
        openPullRequestService = openService,
        pullRequestClient = client,
        pullRequestCache = cache
    )

    init {
        coEvery { tokens.bearerTokenOrNull() } returns "token"
        coEvery { client.getCurrentUser(apiToken = any()) } returns CurrentUser(uuid = "{me}", avatarUrl = null)
    }

    private fun listing(slug: String, id: Long) {
        coEvery {
            client.listRecentPullRequests(workspace = "acme", repoSlug = slug, apiToken = "token")
        } returns PullRequestListResult.Success(pullRequests = listOf(cacheTestSummary(id = id)))
    }

    private fun load(
        slugs: List<String>,
        excluded: String? = null,
        refresh: PullRequestCacheRefresh = PullRequestCacheRefresh.IfStale
    ): WatchedPullRequestListResult {
        return runBlocking {
            service.listWatchedPullRequests(
                organizationUrl = "https://bitbucket.org/acme/",
                repoSlugs = slugs,
                excludedRepoSlug = excluded,
                refresh = refresh
            )
        }
    }

    @Test
    fun `listWatchedPullRequests - empty slugs yield an empty success without a listing request`() {
        // WHEN
        val result = load(slugs = emptyList())

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<WatchedPullRequestListResult.Success>().pullRequests shouldBe emptyList()
            coVerify(exactly = 0) { client.listRecentPullRequests(workspace = any(), repoSlug = any(), apiToken = any()) }
        }
    }

    @Test
    fun `listWatchedPullRequests - skips the excluded repository without regard to case`() {
        // GIVEN
        listing(slug = "beta", id = 2L)

        // WHEN
        val result = load(slugs = listOf("Alpha", "beta"), excluded = "alpha")

        // THEN
        assertSoftly {
            result.shouldBeInstanceOf<WatchedPullRequestListResult.Success>().pullRequests.map { it.repoSlug } shouldBe listOf("beta")
            coVerify(exactly = 0) { client.listRecentPullRequests(workspace = "acme", repoSlug = "Alpha", apiToken = "token") }
        }
    }

    @Test
    fun `listWatchedPullRequests - asks at most 20 repositories and reports truncation`() {
        // GIVEN 25 watched repositories
        val slugs = (1..25).map { index -> "repo-$index" }
        slugs.forEachIndexed { index, slug -> listing(slug = slug, id = index + 1L) }

        // WHEN
        val result = load(slugs = slugs)

        // THEN
        assertSoftly {
            val success = result.shouldBeInstanceOf<WatchedPullRequestListResult.Success>()
            success.pullRequests.size shouldBe 20
            success.isTruncated shouldBe true
        }
    }

    @Test
    fun `listWatchedPullRequests - one failed repository keeps the others and marks the result truncated`() {
        // GIVEN
        listing(slug = "good", id = 1L)
        coEvery {
            client.listRecentPullRequests(workspace = "acme", repoSlug = "bad", apiToken = "token")
        } returns PullRequestListResult.Failed()

        // WHEN
        val result = load(slugs = listOf("good", "bad"))

        // THEN
        assertSoftly {
            val success = result.shouldBeInstanceOf<WatchedPullRequestListResult.Success>()
            success.pullRequests.map { it.repoSlug } shouldBe listOf("good")
            success.failedRepoSlugs shouldBe setOf("bad")
            success.isTruncated shouldBe true
            success.liveRepoSlugs shouldBe setOf("good")
        }
    }

    @Test
    fun `listWatchedPullRequests - fails when every repository failed`() {
        // GIVEN
        coEvery {
            client.listRecentPullRequests(workspace = "acme", repoSlug = any(), apiToken = "token")
        } returns PullRequestListResult.Failed()

        // WHEN
        val result = load(slugs = listOf("a", "b"))

        // THEN
        result.shouldBeInstanceOf<WatchedPullRequestListResult.Failed>()
    }

    @Test
    fun `listWatchedPullRequests - fails without a token`() {
        // GIVEN
        coEvery { tokens.bearerTokenOrNull() } returns null

        // WHEN
        val result = load(slugs = listOf("a"))

        // THEN
        result.shouldBeInstanceOf<WatchedPullRequestListResult.Failed>()
    }

    @Test
    fun `listWatchedPullRequests - a fresh listing is served from the cache on IfStale and requested again on Force`() {
        // GIVEN a repository listed live once
        listing(slug = "alpha", id = 1L)
        load(slugs = listOf("alpha"))
        clock.advance(20.seconds)

        // WHEN it loads again, automatically and then forced
        val cached = load(slugs = listOf("alpha"))
        val forced = load(slugs = listOf("alpha"), refresh = PullRequestCacheRefresh.Force)

        // THEN only the cached answer is not live, and the client saw two requests
        assertSoftly {
            cached.shouldBeInstanceOf<WatchedPullRequestListResult.Success>().liveRepoSlugs shouldBe emptySet()
            forced.shouldBeInstanceOf<WatchedPullRequestListResult.Success>().liveRepoSlugs shouldBe setOf("alpha")
            coVerify(exactly = 2) { client.listRecentPullRequests(workspace = "acme", repoSlug = "alpha", apiToken = "token") }
        }
    }

    @Test
    fun `listRecentPullRequestsForRepository - falls back to the stored listing when the request fails`() {
        // GIVEN a stored listing that went stale and a failing request
        listing(slug = "alpha", id = 7L)
        runBlocking { openService.listRecentPullRequestsForRepository(workspace = "acme", repoSlug = "alpha") }
        clock.advance(120.seconds)
        coEvery {
            client.listRecentPullRequests(workspace = "acme", repoSlug = "alpha", apiToken = "token")
        } returns PullRequestListResult.Failed()

        // WHEN
        val result = runBlocking { openService.listRecentPullRequestsForRepository(workspace = "acme", repoSlug = "alpha") }

        // THEN
        val success = result.shouldBeInstanceOf<PullRequestListResult.Success>()
        assertSoftly {
            success.source shouldBe PullRequestListSource.StaleFallback
            success.pullRequests.map { it.id } shouldContainExactlyInAnyOrder listOf(7L)
        }
    }
}