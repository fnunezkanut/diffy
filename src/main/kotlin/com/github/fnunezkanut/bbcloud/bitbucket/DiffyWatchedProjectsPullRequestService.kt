package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.NoOpPullRequestCacheStore
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheStore
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Lists the recent pull requests (open, merged, declined) of every watched repository for the Pull Requests. Each
 * repository uses the same `project:` cache listing as the current project, so a repository that is both watched and
 * the current project is requested once. The repositories are the verified watched projects of the configured
 * workspace; each repository slug is used as given; the current project may be left out through `excludedRepoSlug`.
 */
class DiffyWatchedProjectsPullRequestService(
    private val accessTokenProvider: DiffyAccessTokenProvider,
    private val openPullRequestService: DiffyOpenPullRequestService,
    private val pullRequestClient: BitbucketPullRequestClient,
    /** Production callers pass [com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache.getInstance]. */
    private val pullRequestCache: PullRequestCacheStore = NoOpPullRequestCacheStore
) {
    /**
     * Lists the pull requests of [repoSlugs]. Empty targets yield an empty success without any repository request. A
     * repository whose request fails falls back to its stored listing when one exists (inside the listing call), and
     * otherwise counts as failed. When every repository failed the whole load is failed.
     */
    suspend fun listWatchedPullRequests(
        organizationUrl: String,
        repoSlugs: List<String>,
        excludedRepoSlug: String?,
        refresh: PullRequestCacheRefresh
    ): WatchedPullRequestListResult {
        val workspace = BitbucketRepositoryUrls.workspaceFromOrganizationUrl(organizationUrl)
        if (workspace == null) {
            LOG.info("Cannot list the watched projects' pull requests: no workspace in organization URL")
            return WatchedPullRequestListResult.Failed()
        }

        val apiToken = accessTokenProvider.bearerTokenOrNull()
        if (apiToken == null) {
            LOG.info("Cannot list the watched projects' pull requests: no Bitbucket credentials")
            return WatchedPullRequestListResult.Failed()
        }

        val currentUserUuid = currentUserThroughCache(
            pullRequestCache = pullRequestCache,
            pullRequestClient = pullRequestClient,
            apiToken = apiToken
        )?.uuid
        if (currentUserUuid != null) {
            pullRequestCache.bindUser(userUuid = currentUserUuid)
        }

        val candidates = ReviewingPullRequestAssembler.repositoryTargets(
            repoSlugs = repoSlugs,
            excludedRepoSlug = excludedRepoSlug,
            maxRepositories = Int.MAX_VALUE
        )
        val targets = candidates.take(MAX_REPOSITORIES)
        val isCapped = candidates.size > MAX_REPOSITORIES

        val outcomes = coroutineScope {
            val permits = Semaphore(MAX_PARALLEL_REPOSITORY_REQUESTS)
            targets.map { repoSlug ->
                async {
                    permits.withPermit {
                        repoSlug to openPullRequestService.listRecentPullRequestsForRepository(
                            workspace = workspace,
                            repoSlug = repoSlug,
                            refresh = refresh
                        )
                    }
                }
            }.awaitAll()
        }

        val failedRepoSlugs = mutableSetOf<String>()
        val liveRepoSlugs = mutableSetOf<String>()
        val pullRequests = mutableListOf<ReviewingPullRequest>()
        for ((repoSlug, result) in outcomes) {
            when (result) {
                is PullRequestListResult.Success -> {
                    if (result.source == PullRequestListSource.Live) {
                        liveRepoSlugs.add(repoSlug)
                    }
                    result.pullRequests.mapTo(pullRequests) { summary ->
                        ReviewingPullRequest(summary = summary, workspace = workspace, repoSlug = repoSlug)
                    }
                }
                is PullRequestListResult.Failed -> {
                    failedRepoSlugs.add(repoSlug)
                }
            }
        }
        LOG.info("Checked ${targets.size} watched repositories: listed ${targets.size - failedRepoSlugs.size}, failed ${failedRepoSlugs.size}")

        if (targets.isNotEmpty() && failedRepoSlugs.size == targets.size) {
            return WatchedPullRequestListResult.Failed()
        }
        return WatchedPullRequestListResult.Success(
            pullRequests = pullRequests,
            isTruncated = failedRepoSlugs.isNotEmpty() || isCapped,
            failedRepoSlugs = failedRepoSlugs,
            liveRepoSlugs = liveRepoSlugs,
            currentUserUuid = currentUserUuid
        )
    }

    companion object {
        private val LOG = logger<DiffyWatchedProjectsPullRequestService>()

        /**
         * How many repositories one load may ask. It repeats the UI limit of watched projects
         * (`WatchedProjectsViewModel.MAX_WATCHED_PROJECTS`); this layer must not depend on the UI package.
         */
        private const val MAX_REPOSITORIES = 20

        private const val MAX_PARALLEL_REPOSITORY_REQUESTS = 4
    }
}