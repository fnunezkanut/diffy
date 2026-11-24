package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.CachedWorkspaceRepositories
import com.github.fnunezkanut.bbcloud.cache.WorkspaceRepositoryCacheStore
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@Tag("unit")
class DiffyWorkspaceRepositoryServiceTest {
    private val now = Instant.parse("2026-11-24T10:00:00Z")

    private fun repo(slug: String): BitbucketWorkspaceRepository {
        return BitbucketWorkspaceRepository(slug = slug, name = slug, mainBranch = "main", httpsCloneUrl = null, sshCloneUrl = null, avatarUrl = null)
    }

    private class FakeCache : WorkspaceRepositoryCacheStore {
        var snapshot: CachedWorkspaceRepositories? = null
        var workspace: String = "acme"

        override fun read(workspace: String): CachedWorkspaceRepositories? {
            return if (workspace == this.workspace) snapshot else null
        }

        override fun replace(workspace: String, repositories: List<BitbucketWorkspaceRepository>, isTruncated: Boolean) {
            snapshot = CachedWorkspaceRepositories(repositories = repositories, isTruncated = isTruncated, fetchedAt = Instant.parse("2026-11-24T10:00:00Z"))
        }

        override fun upsert(workspace: String, repository: BitbucketWorkspaceRepository) {
            val current = snapshot ?: return
            snapshot = current.copy(repositories = current.repositories.filterNot { existing -> existing.slug == repository.slug } + repository)
        }

        override fun remove(workspace: String, repoSlug: String) {
            val current = snapshot ?: return
            snapshot = current.copy(repositories = current.repositories.filterNot { existing -> existing.slug == repoSlug })
        }

        override fun clear() {
            snapshot = null
        }
    }

    private class FakeClient(
        var listResult: RepositoryListResult = RepositoryListResult.Success(emptyList(), false),
        var lookupResult: RepositoryLookupResult = RepositoryLookupResult.NotFound,
        var searchResult: RepositorySearchResult = RepositorySearchResult.Success(emptyList(), false)
    ) : BitbucketRepositoryListClient {
        var listCalls = 0
        val searchedQueries = mutableListOf<String>()

        override suspend fun listRepositories(workspace: String, apiToken: String): RepositoryListResult {
            listCalls++
            return listResult
        }

        override suspend fun fetchRepository(workspace: String, repoSlug: String, apiToken: String): RepositoryLookupResult {
            return lookupResult
        }

        override suspend fun searchRepositories(workspace: String, query: String, apiToken: String): RepositorySearchResult {
            searchedQueries.add(query)
            return searchResult
        }
    }

    private class FakeTokenProvider(private val token: String?) : DiffyAccessTokenProvider {
        override suspend fun bearerTokenOrNull(): String? {
            return token
        }

        override suspend fun oauthAccessTokenOrNull(): String? {
            return token
        }

        override suspend fun refreshAfterUnauthorized(rejectedToken: String): String? {
            return null
        }

        override fun markOAuthNotWorking() {
        }

        override fun onOAuthTokensChanged() {
        }
    }

    private fun service(client: FakeClient, cache: FakeCache, token: String? = "token"): DiffyWorkspaceRepositoryService {
        return DiffyWorkspaceRepositoryService(
            accessTokenProvider = FakeTokenProvider(token),
            repositoryListClient = client,
            repositoryCache = cache,
            clock = { now }
        )
    }

    private fun snapshotAged(hours: Int, vararg slugs: String): CachedWorkspaceRepositories {
        return CachedWorkspaceRepositories(repositories = slugs.map { slug -> repo(slug) }, isTruncated = false, fetchedAt = now - hours.hours)
    }

    @Test
    fun `cachedRepositories - should honor the 24 hour age and reject a future snapshot`() {
        // GIVEN
        val cache = FakeCache()
        val service = service(FakeClient(), cache)

        // THEN
        assertSoftly {
            cache.snapshot = snapshotAged(hours = 23, "a")
            service.cachedRepositories(workspace = "acme")?.repositories?.size shouldBe 1
            cache.snapshot = snapshotAged(hours = 25, "a")
            service.cachedRepositories(workspace = "acme") shouldBe null
            cache.snapshot = snapshotAged(hours = -1, "a")
            service.cachedRepositories(workspace = "acme") shouldBe null
        }
    }

    @Test
    fun `listRepositories - IfStale with a fresh snapshot should make no request`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient()
            val cache = FakeCache().apply { snapshot = snapshotAged(hours = 1, "a") }

            // WHEN
            val result = service(client, cache).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale)

            // THEN
            assertSoftly {
                result.shouldBeInstanceOf<WorkspaceRepositoryListing.Listed>().fromCache shouldBe true
                client.listCalls shouldBe 0
            }
        }

    @Test
    fun `listRepositories - IfStale with a stale snapshot should fetch and replace the cache`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient(listResult = RepositoryListResult.Success(listOf(repo("new")), false))
            val cache = FakeCache().apply { snapshot = snapshotAged(hours = 30, "old") }

            // WHEN
            val result = service(client, cache).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale)

            // THEN
            assertSoftly {
                result.shouldBeInstanceOf<WorkspaceRepositoryListing.Listed>().fromCache shouldBe false
                cache.snapshot?.repositories?.map { repository -> repository.slug } shouldBe listOf("new")
            }
        }

    @Test
    fun `listRepositories - Force should fetch even with a fresh snapshot`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient(listResult = RepositoryListResult.Success(listOf(repo("new")), false))
            val cache = FakeCache().apply { snapshot = snapshotAged(hours = 1, "old") }

            // WHEN
            service(client, cache).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.Force)

            // THEN
            client.listCalls shouldBe 1
        }

    @Test
    fun `listRepositories - a failed fetch should fall back to the stale snapshot or fail without one`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient(listResult = RepositoryListResult.Failed())
            val withSnapshot = FakeCache().apply { snapshot = snapshotAged(hours = 30, "old") }

            // THEN
            assertSoftly {
                val fallback = service(client, withSnapshot).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale)
                fallback.shouldBeInstanceOf<WorkspaceRepositoryListing.Listed>().refreshFailed shouldBe true
                fallback.shouldBeInstanceOf<WorkspaceRepositoryListing.Listed>().fromCache shouldBe true
                service(client, FakeCache()).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale)
                    .shouldBeInstanceOf<WorkspaceRepositoryListing.Failed>()
            }
        }

    @Test
    fun `listRepositories - an Unauthorized fetch should not fall back to the snapshot`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient(listResult = RepositoryListResult.Unauthorized)
            val cache = FakeCache().apply { snapshot = snapshotAged(hours = 30, "old") }

            // WHEN
            val result = service(client, cache).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale)

            // THEN
            result shouldBe WorkspaceRepositoryListing.Unauthorized
        }

    @Test
    fun `listRepositories - without a token should return Unauthorized and make no request, but a fresh snapshot still works`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient()

            // THEN
            assertSoftly {
                service(client, FakeCache(), token = null).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale) shouldBe
                    WorkspaceRepositoryListing.Unauthorized
                client.listCalls shouldBe 0
                val fresh = FakeCache().apply { snapshot = snapshotAged(hours = 1, "a") }
                service(client, fresh, token = null).listRepositories(workspace = "acme", refresh = WorkspaceRepositoryRefresh.IfStale)
                    .shouldBeInstanceOf<WorkspaceRepositoryListing.Listed>()
            }
        }

    @Test
    fun `verifyRepository - Found should upsert the cache`(): Unit =
        runBlocking {
            // GIVEN
            val client = FakeClient(lookupResult = RepositoryLookupResult.Found(repo("a")))
            val cache = FakeCache().apply { snapshot = snapshotAged(hours = 1, "a") }

            // WHEN
            val result = service(client, cache).verifyRepository(workspace = "acme", repoSlug = "a")

            // THEN
            result.shouldBeInstanceOf<RepositoryLookupResult.Found>()
        }

    @Test
    fun `verifyRepository - NotFound and a renamed repository should remove the slug`(): Unit =
        runBlocking {
            // GIVEN
            val notFoundCache = FakeCache().apply { snapshot = snapshotAged(hours = 1, "a", "b") }
            val renamedCache = FakeCache().apply { snapshot = snapshotAged(hours = 1, "a", "b") }

            // WHEN
            val notFound = service(
                FakeClient(lookupResult = RepositoryLookupResult.NotFound),
                notFoundCache
            ).verifyRepository(workspace = "acme", repoSlug = "a")
            val renamed = service(FakeClient(lookupResult = RepositoryLookupResult.Found(repo("a-new"))), renamedCache)
                .verifyRepository(workspace = "acme", repoSlug = "a")

            // THEN
            assertSoftly {
                notFound shouldBe RepositoryLookupResult.NotFound
                renamed shouldBe RepositoryLookupResult.NotFound
                notFoundCache.snapshot?.repositories?.map { repository -> repository.slug } shouldBe listOf("b")
                renamedCache.snapshot?.repositories?.map { repository -> repository.slug } shouldBe listOf("b")
            }
        }

    @Test
    fun `verifyRepository - Failed should leave the cache untouched`(): Unit =
        runBlocking {
            // GIVEN
            val cache = FakeCache().apply { snapshot = snapshotAged(hours = 1, "a") }

            // WHEN
            service(FakeClient(lookupResult = RepositoryLookupResult.Failed()), cache).verifyRepository(workspace = "acme", repoSlug = "a")

            // THEN
            cache.snapshot?.repositories?.map { repository -> repository.slug } shouldBe listOf("a")
        }

    @Nested
    inner class SearchRepositoriesTest {
        @Test
        fun `should return Unauthorized and not call the client without a token`(): Unit =
            runBlocking {
                // GIVEN
                val client = FakeClient()

                // WHEN
                val result = service(client, FakeCache(), token = null).searchRepositories(workspace = "acme", query = "abc")

                // THEN
                assertSoftly {
                    result shouldBe WorkspaceRepositorySearch.Unauthorized
                    client.searchedQueries shouldBe emptyList()
                }
            }

        @Test
        fun `should map each client result to the matching search result`(): Unit =
            runBlocking {
                // GIVEN
                val found = FakeClient(searchResult = RepositorySearchResult.Success(listOf(repo("a")), true))
                val unauthorized = FakeClient(searchResult = RepositorySearchResult.Unauthorized)
                val cause = IllegalStateException("boom")
                val failed = FakeClient(searchResult = RepositorySearchResult.Failed(cause))

                // THEN
                assertSoftly {
                    service(found, FakeCache()).searchRepositories(workspace = "acme", query = "abc") shouldBe
                        WorkspaceRepositorySearch.Found(repositories = listOf(repo("a")), hasMore = true)
                    service(unauthorized, FakeCache()).searchRepositories(workspace = "acme", query = "abc") shouldBe
                        WorkspaceRepositorySearch.Unauthorized
                    service(failed, FakeCache()).searchRepositories(workspace = "acme", query = "abc") shouldBe
                        WorkspaceRepositorySearch.Failed(cause = cause)
                    found.searchedQueries shouldBe listOf("abc")
                }
            }

        @Test
        fun `should never write search results to the cache`(): Unit =
            runBlocking {
                // GIVEN
                val cache = FakeCache()
                val client = FakeClient(searchResult = RepositorySearchResult.Success(listOf(repo("a")), false))

                // WHEN
                service(client, cache).searchRepositories(workspace = "acme", query = "abc")

                // THEN
                cache.snapshot shouldBe null
            }
    }
}