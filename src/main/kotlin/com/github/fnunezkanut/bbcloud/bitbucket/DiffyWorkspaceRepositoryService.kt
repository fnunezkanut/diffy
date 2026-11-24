package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.CachedWorkspaceRepositories
import com.github.fnunezkanut.bbcloud.cache.NoOpWorkspaceRepositoryCacheStore
import com.github.fnunezkanut.bbcloud.cache.WorkspaceRepositoryCacheStore
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Lists the repositories of the configured workspace, backed by a persisted snapshot, and re-checks a single
 * repository against Bitbucket right before it is cloned.
 */
class DiffyWorkspaceRepositoryService(
    private val accessTokenProvider: DiffyAccessTokenProvider,
    private val repositoryListClient: BitbucketRepositoryListClient,
    private val repositoryCache: WorkspaceRepositoryCacheStore = NoOpWorkspaceRepositoryCacheStore,
    private val clock: () -> Instant = { Clock.System.now() }
) {
    /**
     * The snapshot of [workspace] when it is younger than [REPOSITORY_LIST_MAX_AGE]. A snapshot stamped in the future
     * (clock moved backwards) counts as stale. Call it on an IO dispatcher.
     */
    fun cachedRepositories(workspace: String): CachedWorkspaceRepositories? {
        val snapshot = repositoryCache.read(workspace = workspace) ?: return null
        val now = clock()
        if (snapshot.fetchedAt > now || now - snapshot.fetchedAt >= REPOSITORY_LIST_MAX_AGE) {
            return null
        }
        return snapshot
    }

    suspend fun listRepositories(workspace: String, refresh: WorkspaceRepositoryRefresh): WorkspaceRepositoryListing {
        return withContext(Dispatchers.IO) {
            if (refresh == WorkspaceRepositoryRefresh.IfStale) {
                val fresh = cachedRepositories(workspace = workspace)
                if (fresh != null) {
                    LOG.debug("Using the cached repository list of $workspace")
                    return@withContext fresh.toListing(fromCache = true, refreshFailed = false)
                }
            }
            val token = accessTokenProvider.bearerTokenOrNull()
            if (token == null) {
                LOG.info("Cannot list repositories of $workspace: no working Bitbucket login")
                return@withContext WorkspaceRepositoryListing.Unauthorized
            }
            when (val result = repositoryListClient.listRepositories(workspace = workspace, apiToken = token)) {
                is RepositoryListResult.Success -> {
                    repositoryCache.replace(workspace = workspace, repositories = result.repositories, isTruncated = result.isTruncated)
                    WorkspaceRepositoryListing.Listed(
                        repositories = result.repositories,
                        isTruncated = result.isTruncated,
                        fetchedAt = clock(),
                        fromCache = false,
                        refreshFailed = false
                    )
                }
                RepositoryListResult.Unauthorized -> {
                    WorkspaceRepositoryListing.Unauthorized
                }
                is RepositoryListResult.Failed -> {
                    val stale = repositoryCache.read(workspace = workspace)
                    if (stale == null) {
                        WorkspaceRepositoryListing.Failed(cause = result.cause)
                    } else {
                        LOG.debug("Refreshing the repository list of $workspace failed; using the saved list")
                        stale.toListing(fromCache = true, refreshFailed = true)
                    }
                }
            }
        }
    }

    /**
     * Searches [workspace] for repositories whose slug or name contains [query]. One page only. The result is never
     * written to the saved repository list.
     */
    suspend fun searchRepositories(workspace: String, query: String): WorkspaceRepositorySearch {
        return withContext(Dispatchers.IO) {
            val token = accessTokenProvider.bearerTokenOrNull()
            if (token == null) {
                LOG.info("Cannot search repositories of $workspace: no working Bitbucket login")
                return@withContext WorkspaceRepositorySearch.Unauthorized
            }
            when (val result = repositoryListClient.searchRepositories(workspace = workspace, query = query, apiToken = token)) {
                is RepositorySearchResult.Success -> {
                    WorkspaceRepositorySearch.Found(repositories = result.repositories, hasMore = result.hasMore)
                }
                RepositorySearchResult.Unauthorized -> {
                    WorkspaceRepositorySearch.Unauthorized
                }
                is RepositorySearchResult.Failed -> {
                    WorkspaceRepositorySearch.Failed(cause = result.cause)
                }
            }
        }
    }

    /** Asks Bitbucket whether [repoSlug] still exists and refreshes or drops its saved row accordingly. */
    suspend fun verifyRepository(workspace: String, repoSlug: String): RepositoryLookupResult {
        return withContext(Dispatchers.IO) {
            val token = accessTokenProvider.bearerTokenOrNull()
            if (token == null) {
                LOG.info("Cannot verify $workspace/$repoSlug: no working Bitbucket login")
                return@withContext RepositoryLookupResult.Unauthorized
            }
            val result = repositoryListClient.fetchRepository(workspace = workspace, repoSlug = repoSlug, apiToken = token)
            when (result) {
                is RepositoryLookupResult.Found -> {
                    if (result.repository.slug == repoSlug) {
                        repositoryCache.upsert(workspace = workspace, repository = result.repository)
                        result
                    } else {
                        LOG.info("Repository $workspace/$repoSlug was renamed on Bitbucket; removed from the cached list")
                        repositoryCache.remove(workspace = workspace, repoSlug = repoSlug)
                        RepositoryLookupResult.NotFound
                    }
                }
                RepositoryLookupResult.NotFound -> {
                    repositoryCache.remove(workspace = workspace, repoSlug = repoSlug)
                    LOG.info("Repository $workspace/$repoSlug no longer exists on Bitbucket; removed from the cached list")
                    result
                }
                RepositoryLookupResult.Unauthorized, is RepositoryLookupResult.Failed -> {
                    result
                }
            }
        }
    }

    private fun CachedWorkspaceRepositories.toListing(fromCache: Boolean, refreshFailed: Boolean): WorkspaceRepositoryListing.Listed {
        return WorkspaceRepositoryListing.Listed(
            repositories = repositories,
            isTruncated = isTruncated,
            fetchedAt = fetchedAt,
            fromCache = fromCache,
            refreshFailed = refreshFailed
        )
    }

    companion object {
        private val LOG = logger<DiffyWorkspaceRepositoryService>()

        val REPOSITORY_LIST_MAX_AGE: Duration = 24.hours
    }
}