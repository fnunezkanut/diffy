package com.github.fnunezkanut.bbcloud.bitbucket

import com.github.fnunezkanut.bbcloud.auth.DiffyAccessTokenProvider
import com.github.fnunezkanut.bbcloud.cache.CachedListing
import com.github.fnunezkanut.bbcloud.cache.CachedPullRequestMapper
import com.github.fnunezkanut.bbcloud.cache.NoOpPullRequestCacheStore
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheKey
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheStore
import com.github.fnunezkanut.bbcloud.cache.PullRequestListingIds
import com.intellij.openapi.diagnostic.logger

/**
 * Collects the "PRs opened by me" listing of the Other Projects tab: every open pull request the current user
 * authored in the workspace, minus [excludedRepoSlug], which is the repository of the current project and is
 * therefore already listed by the Current Project tab.
 *
 * Bitbucket answers this in one documented request, `GET /2.0/workspaces/{workspace}/pullrequests/{selected_user}`,
 * so the workspace repositories are not walked the way the review queue has to be. That route returns no
 * repository per row, so the repository behind each pull request is recovered from its own links; see
 * [BitbucketWorkspacePullRequestResponseParser].
 *
 * The workspace snapshot is stored before the repository exclusion, because each project window excludes a different
 * repository and the snapshot is shared by all of them.
 */
class DiffyAuthoredPullRequestService(
    private val accessTokenProvider: DiffyAccessTokenProvider,
    private val pullRequestClient: BitbucketPullRequestClient,
    /** Production callers pass [com.github.fnunezkanut.bbcloud.cache.DiffyPullRequestCache.getInstance]. */
    private val pullRequestCache: PullRequestCacheStore = NoOpPullRequestCacheStore
) {
    /**
     * Lists the open pull requests the current user authored in the workspace. An [PullRequestCacheRefresh.IfStale]
     * load returns the stored snapshot with no request while it is fresh. A failed request falls back to the stored
     * snapshot, if one exists.
     */
    suspend fun listAuthoredPullRequests(
        organizationUrl: String,
        excludedRepoSlug: String?,
        refresh: PullRequestCacheRefresh = PullRequestCacheRefresh.IfStale
    ): AuthoredPullRequestListResult {
        val workspace = BitbucketRepositoryUrls.workspaceFromOrganizationUrl(organizationUrl)
        if (workspace == null) {
            LOG.info("Cannot list the pull requests you opened: no workspace in organization URL $organizationUrl")
            return AuthoredPullRequestListResult.Failed()
        }

        val apiToken = accessTokenProvider.bearerTokenOrNull()
        if (apiToken == null) {
            LOG.info("Cannot list the pull requests you opened: no Bitbucket credentials")
            return AuthoredPullRequestListResult.Failed()
        }

        // The route is keyed by uuid, which the nickname-based one does not expose.
        val currentUserUuid = currentUserThroughCache(
            pullRequestCache = pullRequestCache,
            pullRequestClient = pullRequestClient,
            apiToken = apiToken
        )?.uuid
        if (currentUserUuid == null) {
            LOG.info("Cannot list the pull requests you opened: the current Bitbucket user uuid is unknown")
            return AuthoredPullRequestListResult.Failed()
        }

        val listingId = PullRequestListingIds.workspaceAuthored(workspace = workspace)
        if (refresh == PullRequestCacheRefresh.IfStale) {
            val fresh = pullRequestCache.freshListing(listingId = listingId)
            if (fresh != null) {
                LOG.debug("Serving ${fresh.entries.size} cached pull request(s) opened in $workspace")
                return resultFrom(listing = fresh, excludedRepoSlug = excludedRepoSlug, currentUserUuid = currentUserUuid)
            }
        }

        val page = pullRequestClient.listOpenAuthoredPullRequestsInWorkspace(
            workspace = workspace,
            selectedUserUuid = currentUserUuid,
            apiToken = apiToken
        )
        if (page == null) {
            LOG.debug("Could not list the pull requests opened in $workspace")
            val stale = pullRequestCache.listing(listingId = listingId)
                ?: return AuthoredPullRequestListResult.Failed()
            LOG.info("Could not refresh the pull requests opened in $workspace; showing ${stale.entries.size} cached pull request(s)")
            return resultFrom(listing = stale, excludedRepoSlug = excludedRepoSlug, currentUserUuid = currentUserUuid)
        }
        if (page.isTruncated) {
            LOG.info("The pull requests opened in $workspace ran past the page limit; the listing may be incomplete")
        }

        val identified = page.entries.mapNotNull { entry ->
            val repoSlug = entry.repoSlug?.takeIf { slug -> slug.isNotBlank() } ?: return@mapNotNull null
            ReviewingPullRequest(summary = entry.summary, workspace = workspace, repoSlug = repoSlug)
        }
        val droppedCount = page.entries.size - identified.size
        if (droppedCount > 0) {
            LOG.debug("Dropped $droppedCount of ${page.entries.size} pull requests of $workspace that named no repository")
        }
        pullRequestCache.upsertAll(pullRequests = identified)
        if (droppedCount == 0) {
            pullRequestCache.recordListing(
                listingId = listingId,
                keys = identified.map { pullRequest ->
                    PullRequestCacheKey.of(
                        workspace = pullRequest.workspace,
                        repoSlug = pullRequest.repoSlug,
                        prNum = pullRequest.summary.id
                    )
                },
                isTruncated = page.isTruncated
            )
        } else {
            // A snapshot without the dropped rows would be wrong, and the old one would be stale, so it is invalidated.
            pullRequestCache.invalidateListing(listingId = listingId)
        }

        val pullRequests = excludeRepository(pullRequests = identified, excludedRepoSlug = excludedRepoSlug)
        LOG.debug("Listed ${pullRequests.size} pull requests opened in $workspace")
        return AuthoredPullRequestListResult.Success(
            pullRequests = pullRequests,
            isTruncated = page.isTruncated || droppedCount > 0,
            // The service already resolved this uuid above, so the Mine and Approved-by-me filters make no extra request.
            currentUserUuid = currentUserUuid
        )
    }

    /** A cached snapshot with missing entries is shown as truncated, because some rows could not be recovered. */
    private fun resultFrom(listing: CachedListing, excludedRepoSlug: String?, currentUserUuid: String): AuthoredPullRequestListResult.Success {
        val listed = listing.entries.map { entry -> CachedPullRequestMapper.toReviewingPullRequest(entry = entry) }
        return AuthoredPullRequestListResult.Success(
            pullRequests = excludeRepository(pullRequests = listed, excludedRepoSlug = excludedRepoSlug),
            isTruncated = listing.isTruncated || !listing.isComplete,
            currentUserUuid = currentUserUuid
        )
    }

    /**
     * Drops the pull requests of [excludedRepoSlug]. The match is case-insensitive: the exclusion comes from the local
     * directory name, while Bitbucket slugs are lowercase.
     */
    private fun excludeRepository(pullRequests: List<ReviewingPullRequest>, excludedRepoSlug: String?): List<ReviewingPullRequest> {
        val excluded = excludedRepoSlug?.trim()?.lowercase()?.takeIf { slug -> slug.isNotEmpty() }
        return pullRequests.filter { pullRequest ->
            excluded == null || pullRequest.repoSlug.lowercase() != excluded
        }
    }

    companion object {
        private val LOG = logger<DiffyAuthoredPullRequestService>()
    }
}