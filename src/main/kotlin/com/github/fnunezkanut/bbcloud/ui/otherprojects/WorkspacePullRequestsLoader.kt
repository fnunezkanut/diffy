package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.AuthoredPullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryUrls
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequestListResult
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListEntry
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListingSource
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsViewModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * The stored configuration and the Bitbucket listings the Workspace tab reads, backed by the configuration,
 * authored-PR, and review-queue services in production and by a fake in tests.
 */
interface WorkspacePullRequestsDataSource {
    fun organizationUrlOrNull(): String?

    fun projectsDirectoryOrNull(): String?

    fun watchedRepoSlugs(): List<String>

    fun repoAvatarUrls(): Map<String, String>

    suspend fun listAuthored(organizationUrl: String, excludedRepoSlug: String?, refresh: PullRequestCacheRefresh): AuthoredPullRequestListResult

    suspend fun listReviewing(
        organizationUrl: String,
        excludedRepoSlug: String?,
        repoSlugs: List<String>,
        refresh: PullRequestCacheRefresh
    ): ReviewingPullRequestListResult

    /** Re-scans the watched projects before a user-requested refresh. The default does nothing. */
    suspend fun recheckWatchedProjects() {
    }
}

/**
 * Loads the two sources of the merged Workspace tab (pull requests the user opened and pull requests the user
 * reviews) into [pullRequestsViewModel] and keeps the [headerViewModel] refresh button disabled while any load runs.
 * The host only switches its card through [onShowContentCallback] and [onShowNotConfiguredCallback].
 */
class WorkspacePullRequestsLoader(
    private val pullRequestsViewModel: PullRequestsViewModel,
    private val headerViewModel: WorkspaceHeaderViewModel,
    private val dataSource: WorkspacePullRequestsDataSource,
    private val onShowContentCallback: () -> Unit,
    private val onShowNotConfiguredCallback: () -> Unit,
    private val runOnEdt: (() -> Unit) -> Unit = { block -> ApplicationManager.getApplication().invokeLater(block) }
) {
    /** Counter so overlapping loads keep the refresh button disabled until all finish. */
    private var activeLoadCount = 0

    /**
     * Loads both Workspace sources under one refresh generation, so a single Refresh starts one generation and a
     * slow response of one source cannot overwrite a newer load of the other.
     */
    fun loadWorkspacePullRequests(
        excludedRepoSlug: String?,
        coroutineScope: CoroutineScope,
        refresh: PullRequestCacheRefresh = PullRequestCacheRefresh.IfStale
    ) {
        val generation = pullRequestsViewModel.beginRefresh()
        loadAuthored(excludedRepoSlug = excludedRepoSlug, generation = generation, coroutineScope = coroutineScope, refresh = refresh)
        loadReviewing(excludedRepoSlug = excludedRepoSlug, generation = generation, coroutineScope = coroutineScope, refresh = refresh)
    }

    fun showNotConfigured() {
        headerViewModel.setRefreshVisible(false)
        headerViewModel.setRefreshEnabled(false)
        pullRequestsViewModel.showHidden()
        onShowNotConfiguredCallback()
    }

    private fun loadAuthored(excludedRepoSlug: String?, generation: Long, coroutineScope: CoroutineScope, refresh: PullRequestCacheRefresh) {
        val organizationUrl = dataSource.organizationUrlOrNull()
        if (organizationUrl == null) {
            LOG.info("Cannot load authored PRs: no organization URL")
            pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.WorkspaceAuthored)
            return
        }
        val workspaceOverviewUrl = BitbucketRepositoryUrls.workspaceOverviewUrl(organizationUrl)
        val workspaceName = BitbucketRepositoryUrls.workspaceFromOrganizationUrl(organizationUrl)
        headerViewModel.setWorkspaceUrl(url = workspaceOverviewUrl, name = workspaceName)
        headerViewModel.setRefreshVisible(true)
        onShowContentCallback()
        beginLoad()
        coroutineScope.launch {
            val result = dataSource.listAuthored(
                organizationUrl = organizationUrl,
                excludedRepoSlug = excludedRepoSlug,
                refresh = refresh
            )
            runOnEdt {
                showAuthoredResult(result = result, generation = generation, coroutineScope = coroutineScope)
                endLoad()
            }
        }
    }

    private fun loadReviewing(excludedRepoSlug: String?, generation: Long, coroutineScope: CoroutineScope, refresh: PullRequestCacheRefresh) {
        val organizationUrl = dataSource.organizationUrlOrNull()
        val projectsDirectory = dataSource.projectsDirectoryOrNull()
        val workspaceOverviewUrl = organizationUrl?.let { BitbucketRepositoryUrls.workspaceOverviewUrl(it) }
        val workspaceName = organizationUrl?.let { BitbucketRepositoryUrls.workspaceFromOrganizationUrl(it) }
        headerViewModel.setWorkspaceUrl(url = workspaceOverviewUrl, name = workspaceName)
        headerViewModel.setRefreshVisible(workspaceOverviewUrl != null)
        if (organizationUrl == null) {
            LOG.info("Cannot load reviewing PRs: no organization URL")
            pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.WorkspaceAssigned)
            return
        }
        if (projectsDirectory == null) {
            LOG.info("Assigned PRs: projects directory not set; prompting for Settings")
            // Route to the generic centered not-configured card (R2) rather than showing partial content with the
            // header still visible. showNotConfigured switches the single Workspace root and hides its refresh
            // button.
            showNotConfigured()
            return
        }
        val repoSlugs = dataSource.watchedRepoSlugs()
        if (repoSlugs.isEmpty()) {
            LOG.info("No watched projects are enabled for the current workspace, so no pull requests to review are listed")
        }
        headerViewModel.setRefreshVisible(true)
        onShowContentCallback()
        beginLoad()
        coroutineScope.launch {
            // A user's Refresh first re-checks the watched projects, so the fan-out uses the rechecked set. A check
            // that fails or hangs past the timeout leaves the stored set in place.
            if (refresh == PullRequestCacheRefresh.Force) {
                recheckWatchedProjectsOrKeepStoredSet()
            }
            val result = dataSource.listReviewing(
                organizationUrl = organizationUrl,
                excludedRepoSlug = excludedRepoSlug,
                repoSlugs = dataSource.watchedRepoSlugs(),
                refresh = refresh
            )
            runOnEdt {
                showReviewingResult(result = result, generation = generation, coroutineScope = coroutineScope)
                endLoad()
            }
        }
    }

    /** A failed or slow re-check is logged, and the pull request load goes on with the stored watched set. */
    private suspend fun recheckWatchedProjectsOrKeepStoredSet() {
        try {
            val finished = withTimeoutOrNull(WATCHED_RECHECK_TIMEOUT) { dataSource.recheckWatchedProjects() }
            if (finished == null) {
                LOG.warn("Watched projects re-check did not finish in time; loading with the stored set")
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Watched projects re-check failed; loading with the stored set", exception)
        }
    }

    private fun showAuthoredResult(result: AuthoredPullRequestListResult, generation: Long, coroutineScope: CoroutineScope) {
        when (result) {
            is AuthoredPullRequestListResult.Success -> {
                // The repository avatar lookup is copied verbatim from WorkspaceMyPullRequestsViewModel.showResult: the
                // persisted URL map is keyed by the raw repoSlug, with no lowercasing.
                val avatarUrlBySlug = dataSource.repoAvatarUrls()
                val entries = result.pullRequests.map { pullRequest ->
                    PullRequestListEntry(
                        pullRequest = pullRequest,
                        source = PullRequestListingSource.WorkspaceAuthored,
                        repoAvatarUrl = avatarUrlBySlug[pullRequest.repoSlug]
                    )
                }
                pullRequestsViewModel.applySource(
                    generation = generation,
                    source = PullRequestListingSource.WorkspaceAuthored,
                    entries = entries,
                    isTruncated = result.isTruncated,
                    currentUserUuid = result.currentUserUuid,
                    coroutineScope = coroutineScope
                )
            }
            is AuthoredPullRequestListResult.Failed -> {
                if (result.cause != null) {
                    LOG.warn("Could not load the pull requests you opened", result.cause)
                } else {
                    LOG.warn("Could not load the pull requests you opened")
                }
                pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.WorkspaceAuthored)
            }
        }
    }

    private fun showReviewingResult(result: ReviewingPullRequestListResult, generation: Long, coroutineScope: CoroutineScope) {
        when (result) {
            is ReviewingPullRequestListResult.Success -> {
                // The repository avatar lookup is copied verbatim from WorkspaceAssignedPullRequestsViewModel.showResult:
                // the persisted URL map is keyed by the raw repoSlug, with no lowercasing.
                val avatarUrlBySlug = dataSource.repoAvatarUrls()
                val entries = result.pullRequests.map { pullRequest ->
                    PullRequestListEntry(
                        pullRequest = pullRequest,
                        source = PullRequestListingSource.WorkspaceAssigned,
                        repoAvatarUrl = avatarUrlBySlug[pullRequest.repoSlug]
                    )
                }
                pullRequestsViewModel.applySource(
                    generation = generation,
                    source = PullRequestListingSource.WorkspaceAssigned,
                    entries = entries,
                    isTruncated = result.isTruncated,
                    currentUserUuid = result.currentUserUuid,
                    coroutineScope = coroutineScope
                )
            }
            is ReviewingPullRequestListResult.Failed -> {
                if (result.cause != null) {
                    LOG.warn("Could not load the pull requests to review", result.cause)
                } else {
                    LOG.warn("Could not load the pull requests to review")
                }
                pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.WorkspaceAssigned)
            }
        }
    }

    private fun beginLoad() {
        activeLoadCount++
        headerViewModel.setRefreshEnabled(false)
    }

    @Synchronized
    private fun endLoad() {
        if (--activeLoadCount <= 0) {
            activeLoadCount = 0
            headerViewModel.setRefreshEnabled(true)
        }
    }

    companion object {
        private val LOG = logger<WorkspacePullRequestsLoader>()

        /** A Refresh does not wait longer than this for the watched projects re-check, so a hung directory scan cannot keep it disabled. */
        private val WATCHED_RECHECK_TIMEOUT = 15.seconds
    }
}