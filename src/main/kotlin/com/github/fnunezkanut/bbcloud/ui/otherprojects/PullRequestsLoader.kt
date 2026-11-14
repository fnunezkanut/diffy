package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.AuthoredPullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.BitbucketRepositoryUrls
import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListSource
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestOrdering
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestSummary
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest
import com.github.fnunezkanut.bbcloud.bitbucket.WatchedPullRequestListResult
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.ui.pullrequests.CurrentProjectRepositoryState
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListEntry
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListingSource
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestRepositoryKey
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsViewModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** The outcome of resolving and verifying the IDE project's Bitbucket repository. */
sealed interface CurrentProjectLookup {
    data class Found(val workspace: String, val repoSlug: String, val repositoryWebUrl: String, val avatarUrl: String?) : CurrentProjectLookup

    /** The project is not a verified Bitbucket repository of the workspace; [status] says why. */
    data class Unavailable(val status: ProjectRepositoryStatus) : CurrentProjectLookup
}

/** The listing of the current project's repository together with the account it was keyed by. */
data class CurrentProjectListing(val result: PullRequestListResult, val currentUserUuid: String?)

/**
 * The stored configuration and the Bitbucket listings the Pull Requests reads, backed by the configuration, current project,
 * authored-PR, and watched-projects services in production and by a fake in tests.
 */
interface PullRequestsDataSource {
    fun organizationUrlOrNull(): String?

    fun projectsDirectoryOrNull(): String?

    fun watchedRepoSlugs(): List<String>

    fun repoAvatarUrls(): Map<String, String>

    /** An [PullRequestCacheRefresh.IfStale] lookup may reuse a recent verification; [PullRequestCacheRefresh.Force] always verifies again. */
    suspend fun lookupCurrentProject(refresh: PullRequestCacheRefresh): CurrentProjectLookup

    suspend fun listCurrentProject(refresh: PullRequestCacheRefresh): CurrentProjectListing

    suspend fun listAuthored(organizationUrl: String, refresh: PullRequestCacheRefresh): AuthoredPullRequestListResult

    suspend fun listWatched(
        organizationUrl: String,
        repoSlugs: List<String>,
        excludedRepoSlug: String?,
        refresh: PullRequestCacheRefresh
    ): WatchedPullRequestListResult

    /** Re-scans the watched projects before a user-requested refresh. The default does nothing. */
    suspend fun recheckWatchedProjects() {
    }
}

/**
 * Loads the three sources of the Pull Requests (the current project, the pull requests the user opened, and the watched
 * projects) into [pullRequestsViewModel] and keeps the [headerViewModel] refresh button disabled while any load runs.
 * The host only switches its card through [onShowContentCallback] and [onShowNotConfiguredCallback].
 *
 * [beginLoad] and [endLoad] run on the EDT only, so [activeLoadCount] needs no synchronization.
 */
class PullRequestsLoader(
    private val pullRequestsViewModel: PullRequestsViewModel,
    private val headerViewModel: WorkspaceHeaderViewModel,
    private val dataSource: PullRequestsDataSource,
    private val onShowContentCallback: () -> Unit,
    private val onShowNotConfiguredCallback: () -> Unit,
    // Null while the current project is being checked.
    private val onCurrentProjectStatusChanged: (CurrentProjectLookup?) -> Unit,
    // Work tree cleanup hook: called with the resolved pull request numbers of every repository listed live.
    private val onRepositoryListedLive: (workspace: String, repoSlug: String, resolvedPrNumbers: Set<Long>) -> Unit,
    private val runOnEdt: (() -> Unit) -> Unit = { block -> ApplicationManager.getApplication().invokeLater(block) }
) {
    /** Counter so overlapping loads keep the refresh button disabled until all finish. EDT only. */
    private var activeLoadCount = 0

    /**
     * Loads all three sources under one refresh generation, so a single Refresh starts one generation and a slow
     * response of one source cannot overwrite a newer load of another. The authored source does not depend on the
     * current project and starts at once. The current project is resolved first, because the watched source leaves it
     * out and the project scope filter needs its repository.
     */
    fun loadOverview(coroutineScope: CoroutineScope, refresh: PullRequestCacheRefresh = PullRequestCacheRefresh.IfStale) {
        val organizationUrl = dataSource.organizationUrlOrNull()
        headerViewModel.setWorkspaceUrl(
            url = organizationUrl?.let { url -> BitbucketRepositoryUrls.workspaceOverviewUrl(url) },
            name = organizationUrl?.let { url -> BitbucketRepositoryUrls.workspaceFromOrganizationUrl(url) }
        )
        if (organizationUrl == null) {
            LOG.info("Cannot load the Pull Requests: no organization URL")
            val generation = pullRequestsViewModel.beginRefresh()
            PullRequestListingSource.entries.forEach { source ->
                pullRequestsViewModel.failSource(generation = generation, source = source)
            }
            headerViewModel.setRefreshVisible(false)
            return
        }
        if (dataSource.projectsDirectoryOrNull() == null) {
            LOG.info("Pull Requests: projects directory not set; prompting for Settings")
            // Route to the generic centered not-configured card rather than showing partial content with the header
            // still visible.
            showNotConfigured()
            return
        }
        val generation = pullRequestsViewModel.beginRefresh()
        headerViewModel.setRefreshVisible(true)
        onShowContentCallback()
        loadAuthored(organizationUrl = organizationUrl, generation = generation, coroutineScope = coroutineScope, refresh = refresh)
        loadCurrentProjectThenWatched(
            organizationUrl = organizationUrl,
            generation = generation,
            coroutineScope = coroutineScope,
            refresh = refresh
        )
    }

    /**
     * Reloads only the current project source, after a branch switch or a created pull request. The watched and
     * authored rows stay as they are. A concurrent full [loadOverview] makes this reload's response stale.
     */
    fun reloadCurrentProject(coroutineScope: CoroutineScope, reason: String) {
        LOG.info("Reloading the current project of the Pull Requests: $reason")
        val organizationUrl = dataSource.organizationUrlOrNull()
        if (organizationUrl == null || dataSource.projectsDirectoryOrNull() == null) {
            LOG.info("Cannot reload the current project: the plugin is not configured")
            return
        }
        val generation = pullRequestsViewModel.beginReload(
            reloaded = setOf(PullRequestListingSource.ProjectOpen, PullRequestListingSource.ProjectResolved)
        )
        beginLoad()
        coroutineScope.launch {
            val lookup = lookupSafely(refresh = PullRequestCacheRefresh.IfStale)
            runOnEdt {
                applyLookup(lookup = lookup, generation = generation, coroutineScope = coroutineScope)
            }
            if (lookup is CurrentProjectLookup.Found) {
                val listing = listCurrentProjectSafely(refresh = PullRequestCacheRefresh.Force)
                runOnEdt {
                    showCurrentProjectResult(listing = listing, lookup = lookup, generation = generation, coroutineScope = coroutineScope)
                    endLoad()
                }
            } else {
                runOnEdt { endLoad() }
            }
        }
    }

    fun showNotConfigured() {
        headerViewModel.setRefreshVisible(false)
        headerViewModel.setRefreshEnabled(false)
        pullRequestsViewModel.showHidden()
        onCurrentProjectStatusChanged(null)
        pullRequestsViewModel.setCurrentProject(state = CurrentProjectRepositoryState.Pending)
        onShowNotConfiguredCallback()
    }

    private fun loadAuthored(organizationUrl: String, generation: Long, coroutineScope: CoroutineScope, refresh: PullRequestCacheRefresh) {
        beginLoad()
        coroutineScope.launch {
            val result = try {
                dataSource.listAuthored(organizationUrl = organizationUrl, refresh = refresh)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                AuthoredPullRequestListResult.Failed(cause = exception)
            }
            runOnEdt {
                showAuthoredResult(result = result, generation = generation, coroutineScope = coroutineScope)
                endLoad()
            }
        }
    }

    private fun loadCurrentProjectThenWatched(organizationUrl: String, generation: Long, coroutineScope: CoroutineScope, refresh: PullRequestCacheRefresh) {
        // The lookup itself counts as one load; the source loads it starts count on top, so the count never reaches
        // zero while a source is still running.
        beginLoad()
        coroutineScope.launch {
            runOnEdt { onCurrentProjectStatusChanged(null) }
            val lookup = lookupSafely(refresh = refresh)
            runOnEdt {
                applyLookup(lookup = lookup, generation = generation, coroutineScope = coroutineScope)
                if (lookup is CurrentProjectLookup.Found) {
                    beginLoad()
                    coroutineScope.launch {
                        val listing = listCurrentProjectSafely(refresh = refresh)
                        runOnEdt {
                            showCurrentProjectResult(listing = listing, lookup = lookup, generation = generation, coroutineScope = coroutineScope)
                            endLoad()
                        }
                    }
                }
                beginLoad()
                coroutineScope.launch {
                    loadWatched(
                        organizationUrl = organizationUrl,
                        excludedRepoSlug = (lookup as? CurrentProjectLookup.Found)?.repoSlug,
                        generation = generation,
                        coroutineScope = coroutineScope,
                        refresh = refresh
                    )
                }
                endLoad()
            }
        }
    }

    private suspend fun loadWatched(
        organizationUrl: String,
        excludedRepoSlug: String?,
        generation: Long,
        coroutineScope: CoroutineScope,
        refresh: PullRequestCacheRefresh
    ) {
        // A user's Refresh first re-checks the watched projects, so the fan-out uses the rechecked set. A check that
        // fails or hangs past the timeout leaves the stored set in place.
        if (refresh == PullRequestCacheRefresh.Force) {
            recheckWatchedProjectsOrKeepStoredSet()
        }
        val result = try {
            // The watched source always uses IfStale, also on Refresh: a watched listing younger than 60 seconds is
            // served from the cache and older ones are requested. This bounds the request volume of a Refresh, which
            // would otherwise force up to 20 repository listings at once.
            dataSource.listWatched(
                organizationUrl = organizationUrl,
                repoSlugs = dataSource.watchedRepoSlugs(),
                excludedRepoSlug = excludedRepoSlug,
                refresh = PullRequestCacheRefresh.IfStale
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            WatchedPullRequestListResult.Failed(cause = exception)
        }
        runOnEdt {
            showWatchedResult(result = result, organizationUrl = organizationUrl, generation = generation, coroutineScope = coroutineScope)
            endLoad()
        }
    }

    private suspend fun lookupSafely(refresh: PullRequestCacheRefresh): CurrentProjectLookup {
        return try {
            dataSource.lookupCurrentProject(refresh = refresh)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Could not look up the current project", exception)
            CurrentProjectLookup.Unavailable(status = ProjectRepositoryStatus.CheckFailed(repositoryWebUrl = null, cause = exception))
        }
    }

    private suspend fun listCurrentProjectSafely(refresh: PullRequestCacheRefresh): CurrentProjectListing {
        return try {
            dataSource.listCurrentProject(refresh = refresh)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            CurrentProjectListing(result = PullRequestListResult.Failed(cause = exception), currentUserUuid = null)
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

    /**
     * Publishes the lookup: the host learns the status, the view model learns the repository, and an unavailable
     * project marks both project sources as loaded and empty, so the footer shows the status line instead of a failure.
     */
    private fun applyLookup(lookup: CurrentProjectLookup, generation: Long, coroutineScope: CoroutineScope) {
        onCurrentProjectStatusChanged(lookup)
        when (lookup) {
            is CurrentProjectLookup.Found -> {
                pullRequestsViewModel.setCurrentProject(
                    state = CurrentProjectRepositoryState.Found(
                        repository = PullRequestRepositoryKey.of(workspace = lookup.workspace, repoSlug = lookup.repoSlug)
                    )
                )
            }
            is CurrentProjectLookup.Unavailable -> {
                pullRequestsViewModel.setCurrentProject(state = CurrentProjectRepositoryState.Unavailable)
                markProjectSourcesEmpty(generation = generation, coroutineScope = coroutineScope)
            }
        }
    }

    private fun markProjectSourcesEmpty(generation: Long, coroutineScope: CoroutineScope) {
        listOf(PullRequestListingSource.ProjectOpen, PullRequestListingSource.ProjectResolved).forEach { source ->
            pullRequestsViewModel.applySource(
                generation = generation,
                source = source,
                entries = emptyList(),
                isTruncated = false,
                currentUserUuid = null,
                coroutineScope = coroutineScope
            )
        }
    }

    private fun showCurrentProjectResult(listing: CurrentProjectListing, lookup: CurrentProjectLookup.Found, generation: Long, coroutineScope: CoroutineScope) {
        when (val result = listing.result) {
            is PullRequestListResult.Success -> {
                LOG.debug("Loaded ${result.pullRequests.size} pull requests of the current project")
                val partitioned = PullRequestOrdering.partitionForDisplay(result.pullRequests)
                applyCurrentProjectSource(
                    source = PullRequestListingSource.ProjectOpen,
                    summaries = partitioned.open,
                    listing = listing,
                    lookup = lookup,
                    generation = generation,
                    coroutineScope = coroutineScope
                )
                applyCurrentProjectSource(
                    source = PullRequestListingSource.ProjectResolved,
                    summaries = partitioned.closed,
                    listing = listing,
                    lookup = lookup,
                    generation = generation,
                    coroutineScope = coroutineScope
                )
                // Work tree cleanup needs the live open set. A listing served from the cache is not proof of it.
                if (result.source == PullRequestListSource.Live) {
                    onRepositoryListedLive(lookup.workspace, lookup.repoSlug, partitioned.closed.map { summary -> summary.id }.toSet())
                } else {
                    LOG.debug("Skipping work tree cleanup: the current project listing came from the cache")
                }
            }
            is PullRequestListResult.Failed -> {
                if (result.cause != null) {
                    LOG.warn("Could not load the pull requests of the current project", result.cause)
                } else {
                    LOG.warn("Could not load the pull requests of the current project")
                }
                pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.ProjectOpen)
                pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.ProjectResolved)
            }
        }
    }

    private fun applyCurrentProjectSource(
        source: PullRequestListingSource,
        summaries: List<PullRequestSummary>,
        listing: CurrentProjectListing,
        lookup: CurrentProjectLookup.Found,
        generation: Long,
        coroutineScope: CoroutineScope
    ) {
        pullRequestsViewModel.applySource(
            generation = generation,
            source = source,
            entries = summaries.map { summary ->
                PullRequestListEntry(
                    pullRequest = ReviewingPullRequest(summary = summary, workspace = lookup.workspace, repoSlug = lookup.repoSlug),
                    source = source,
                    repoAvatarUrl = lookup.avatarUrl
                )
            },
            isTruncated = false,
            currentUserUuid = listing.currentUserUuid,
            coroutineScope = coroutineScope
        )
    }

    private fun showAuthoredResult(result: AuthoredPullRequestListResult, generation: Long, coroutineScope: CoroutineScope) {
        when (result) {
            is AuthoredPullRequestListResult.Success -> {
                // The persisted avatar URL map is keyed by the raw repoSlug, with no lowercasing.
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

    private fun showWatchedResult(result: WatchedPullRequestListResult, organizationUrl: String, generation: Long, coroutineScope: CoroutineScope) {
        when (result) {
            is WatchedPullRequestListResult.Success -> {
                val avatarUrlBySlug = dataSource.repoAvatarUrls()
                val (open, resolved) = result.pullRequests.partition { pullRequest -> pullRequest.summary.state == PullRequestState.Open }
                applyWatchedSource(
                    source = PullRequestListingSource.WatchedOpen,
                    pullRequests = open,
                    result = result,
                    avatarUrlBySlug = avatarUrlBySlug,
                    generation = generation,
                    coroutineScope = coroutineScope
                )
                applyWatchedSource(
                    source = PullRequestListingSource.WatchedResolved,
                    pullRequests = resolved,
                    result = result,
                    avatarUrlBySlug = avatarUrlBySlug,
                    generation = generation,
                    coroutineScope = coroutineScope
                )
                cleanLiveWatchedRepositories(result = result, resolved = resolved, organizationUrl = organizationUrl)
            }
            is WatchedPullRequestListResult.Failed -> {
                if (result.cause != null) {
                    LOG.warn("Could not load the pull requests of the watched projects", result.cause)
                } else {
                    LOG.warn("Could not load the pull requests of the watched projects")
                }
                pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.WatchedOpen)
                pullRequestsViewModel.failSource(generation = generation, source = PullRequestListingSource.WatchedResolved)
            }
        }
    }

    private fun applyWatchedSource(
        source: PullRequestListingSource,
        pullRequests: List<ReviewingPullRequest>,
        result: WatchedPullRequestListResult.Success,
        avatarUrlBySlug: Map<String, String>,
        generation: Long,
        coroutineScope: CoroutineScope
    ) {
        pullRequestsViewModel.applySource(
            generation = generation,
            source = source,
            entries = pullRequests.map { pullRequest ->
                PullRequestListEntry(pullRequest = pullRequest, source = source, repoAvatarUrl = avatarUrlBySlug[pullRequest.repoSlug])
            },
            isTruncated = result.isTruncated,
            currentUserUuid = result.currentUserUuid,
            coroutineScope = coroutineScope
        )
    }

    /** Hands every repository that was listed live to the work tree cleanup, with that repository's resolved ids only. */
    private fun cleanLiveWatchedRepositories(result: WatchedPullRequestListResult.Success, resolved: List<ReviewingPullRequest>, organizationUrl: String) {
        val workspace = BitbucketRepositoryUrls.workspaceFromOrganizationUrl(organizationUrl) ?: return
        result.liveRepoSlugs.forEach { repoSlug ->
            val resolvedIds = resolved
                .filter { pullRequest -> pullRequest.repoSlug == repoSlug }
                .map { pullRequest -> pullRequest.summary.id }
                .toSet()
            onRepositoryListedLive(workspace, repoSlug, resolvedIds)
        }
    }

    private fun beginLoad() {
        activeLoadCount++
        headerViewModel.setRefreshEnabled(false)
    }

    private fun endLoad() {
        if (--activeLoadCount <= 0) {
            activeLoadCount = 0
            headerViewModel.setRefreshEnabled(true)
        }
    }

    companion object {
        private val LOG = logger<PullRequestsLoader>()

        /** A Refresh does not wait longer than this for the watched projects re-check, so a hung directory scan cannot keep it disabled. */
        private val WATCHED_RECHECK_TIMEOUT = 15.seconds
    }
}