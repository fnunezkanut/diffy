package com.github.fnunezkanut.bbcloud.ui.pullrequests

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest
import com.github.fnunezkanut.bbcloud.settings.PullRequestListFilter
import com.github.fnunezkanut.bbcloud.settings.PullRequestProjectScope
import com.github.fnunezkanut.bbcloud.settings.PullRequestUserScope
import com.github.fnunezkanut.bbcloud.ui.main.AuthorAvatarCache
import com.github.fnunezkanut.bbcloud.ui.otherprojects.buildWorkspacePullRequestRow
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope

/**
 * The view model behind the Pull Requests. There is one instance, owned by the other-projects panel; its sources are all
 * [PullRequestListingSource] entries. It owns the source slots, the filter, one [AuthorAvatarCache], and the derived
 * visible rows.
 *
 * Filtering is pure and local: a toggle re-filters the already-loaded rows and persists the flag; it does not reload.
 * All source-map mutation happens on the EDT, as the coordinators already arrange via invokeLater.
 */
class PullRequestsViewModel(
    // Used in log lines only.
    private val logName: String,
    private val sourcesOfTab: Set<PullRequestListingSource>,
    initialFilter: PullRequestListFilter,
    private val organizationUrl: () -> String?,
    // Persists the filter via DiffyConfigurationService.
    private val onFilterChangedCallback: (PullRequestListFilter) -> Unit,
    private val onOpenTitleCallback: (PullRequestListEntry) -> Unit,
    private val onOpenPrOnWebCallback: (PullRequestListEntry) -> Unit,
    private val onOpenRepositoryCallback: (PullRequestListEntry) -> Unit,
    // openCount is the number of visible open rows (after the Exclude filters); card is the derived card (decision 4c).
    private val onCountsChanged: (openCount: Int, card: PullRequestsCard) -> Unit = { _, _ -> },
    // Seeds the recently-seen mention cache from the loaded entries; a no-op default so plain unit tests need no application.
    private val seedKnownUsers: (List<PullRequestListEntry>) -> Unit = {}
) {
    val state: MutableState<PullRequestsUiState> = mutableStateOf(
        PullRequestsUiState(filter = initialFilter)
    )

    /** The load state of every source of this tab. Mutated on the EDT only. */
    private val sources: MutableMap<PullRequestListingSource, PullRequestSourceState> =
        sourcesOfTab.associateWith<PullRequestListingSource, PullRequestSourceState> { PullRequestSourceState.Idle }
            .toMutableMap()

    private var currentUserUuid: String? = null

    private var currentProject: CurrentProjectRepositoryState = CurrentProjectRepositoryState.Pending

    /** Incremented by [beginRefresh]; stale responses are ignored so a slow reload cannot overwrite a newer one. */
    private var refreshGeneration: Long = 0

    private val avatarCache = AuthorAvatarCache(avatarSize = LISTING_AUTHOR_AVATAR_SIZE_DP)

    /** The visible entries, keyed for action routing; rebuilt by [recompute]. */
    private var visibleEntriesByKey: Map<PullRequestListKey, PullRequestListEntry> = emptyMap()

    /** Sets every source back to Idle, so the card derivation shows Hidden, and forgets the current project. */
    fun showHidden() {
        sourcesOfTab.forEach { source -> sources[source] = PullRequestSourceState.Idle }
        currentProject = CurrentProjectRepositoryState.Pending
        recompute()
    }

    /** Stores what is known about the IDE project's repository and re-filters the rows. */
    fun setCurrentProject(state: CurrentProjectRepositoryState) {
        LOG.debug("$logName: current project state is ${describe(state = state)}")
        currentProject = state
        recompute()
    }

    /** Sets [source] to Loading and returns the current generation. */
    fun beginLoad(source: PullRequestListingSource): Long {
        sources[source] = PullRequestSourceState.Loading
        recompute()
        return refreshGeneration
    }

    /** Increments the generation, sets every source of the tab to Loading, keeps the filter, and returns the new generation. */
    fun beginRefresh(): Long {
        refreshGeneration += 1
        sourcesOfTab.forEach { source -> sources[source] = PullRequestSourceState.Loading }
        recompute()
        return refreshGeneration
    }

    /**
     * Sets only the given [reloaded] sources to Loading, without incrementing the generation, and returns the current
     * generation. Used to reload the current project alone (after a branch switch or a created PR) without disturbing
     * the watched and authored rows. A concurrent full [beginRefresh] makes the partial reload stale.
     */
    fun beginReload(reloaded: Set<PullRequestListingSource>): Long {
        reloaded.forEach { source -> sources[source] = PullRequestSourceState.Loading }
        recompute()
        return refreshGeneration
    }

    /**
     * Stores the loaded entries of [source], unless [generation] is stale. Updates the current user uuid when it is
     * non-null, prefetches author and repository avatars, and recomputes the visible rows.
     */
    fun applySource(
        generation: Long,
        source: PullRequestListingSource,
        entries: List<PullRequestListEntry>,
        isTruncated: Boolean,
        currentUserUuid: String?,
        coroutineScope: CoroutineScope
    ) {
        if (generation != refreshGeneration) {
            LOG.debug("$logName: ignoring a stale $source response (generation $generation, current $refreshGeneration)")
            return
        }
        sources[source] = PullRequestSourceState.Loaded(entries = entries, isTruncated = isTruncated)
        LOG.debug("$logName: seeded known users from ${entries.size} listing entries")
        runCatching { seedKnownUsers(entries) }.onFailure { failure -> LOG.debug("$logName: seeding known users failed", failure) }
        if (currentUserUuid != null) {
            this.currentUserUuid = currentUserUuid
        }
        avatarCache.prefetch(
            urls = entries.mapNotNull { entry -> entry.pullRequest.summary.authorAvatarUrl },
            coroutineScope = coroutineScope,
            onLoaded = { bumpAvatarRevision() }
        )
        avatarCache.prefetch(
            urls = entries.mapNotNull { entry -> entry.repoAvatarUrl },
            coroutineScope = coroutineScope,
            onLoaded = { bumpAvatarRevision() }
        )
        // Only the reviewers that can actually be visible, and only for open pull requests (decisions 8a and 11b).
        val reviewerAvatarUrls = entries
            .filter { entry -> entry.pullRequest.summary.state == PullRequestState.Open }
            .flatMap { entry -> buildReviewerStack(reviewers = entry.pullRequest.summary.reviewers).visible }
            .mapNotNull { reviewer -> reviewer.avatarUrl }
        LOG.debug("$logName: queueing ${reviewerAvatarUrls.distinct().size} reviewer avatar URLs for prefetch")
        avatarCache.prefetch(
            urls = reviewerAvatarUrls,
            coroutineScope = coroutineScope,
            onLoaded = { bumpAvatarRevision() }
        )
        recompute()
    }

    /** Marks [source] as failed, unless [generation] is stale. */
    fun failSource(generation: Long, source: PullRequestListingSource) {
        if (generation != refreshGeneration) {
            LOG.debug("$logName: ignoring a stale $source failure (generation $generation, current $refreshGeneration)")
            return
        }
        sources[source] = PullRequestSourceState.Failed
        recompute()
    }

    fun onExcludeResolvedChanged(checked: Boolean) {
        LOG.info("User toggled the Exclude Resolved checkbox on the $logName tab: checked=$checked")
        updateFilter(filter = state.value.filter.copy(excludeResolved = checked))
    }

    fun onUserScopeChanged(userScope: PullRequestUserScope) {
        LOG.info("User changed the user scope dropdown on the $logName tab: userScope=$userScope")
        updateFilter(filter = state.value.filter.copy(userScope = userScope))
    }

    fun onProjectScopeChanged(projectScope: PullRequestProjectScope) {
        LOG.info("User changed the project scope dropdown on the $logName tab: projectScope=$projectScope")
        updateFilter(filter = state.value.filter.copy(projectScope = projectScope))
    }

    fun onExcludeApprovedByMeChanged(checked: Boolean) {
        LOG.info("User toggled the Exclude Approved-by-me checkbox on the $logName tab: checked=$checked")
        updateFilter(filter = state.value.filter.copy(excludeApprovedByMe = checked))
    }

    fun onExcludeDraftsChanged(checked: Boolean) {
        LOG.info("User toggled the Exclude Drafts checkbox on the $logName tab: checked=$checked")
        updateFilter(filter = state.value.filter.copy(excludeDrafts = checked))
    }

    /** Re-applies the last computed card without a reload. Used by the editor navigation. */
    fun showListingCard() {
        recompute()
    }

    fun onOpenTitle(pullRequest: ReviewingPullRequest) {
        forwardEntry(pullRequest = pullRequest, action = "open title", callback = onOpenTitleCallback)
    }

    fun onOpenPrOnWeb(pullRequest: ReviewingPullRequest) {
        forwardEntry(pullRequest = pullRequest, action = "open PR on web", callback = onOpenPrOnWebCallback)
    }

    fun onOpenRepository(pullRequest: ReviewingPullRequest) {
        forwardEntry(pullRequest = pullRequest, action = "open repository", callback = onOpenRepositoryCallback)
    }

    fun avatarIconFor(url: String?, fallback: javax.swing.Icon): javax.swing.Icon {
        return avatarCache.iconFor(url = url, fallback = fallback)
    }

    private fun forwardEntry(pullRequest: ReviewingPullRequest, action: String, callback: (PullRequestListEntry) -> Unit) {
        val entry = lookupEntry(pullRequest = pullRequest)
        if (entry == null) {
            LOG.info("$logName: no entry found for the $action request on pull request #${pullRequest.summary.id}; ignoring")
            return
        }
        LOG.info("$logName: $action request on pull request #${pullRequest.summary.id} from source ${entry.source}")
        callback(entry)
    }

    private fun lookupEntry(pullRequest: ReviewingPullRequest): PullRequestListEntry? {
        val key = PullRequestListKey(
            workspace = pullRequest.workspace.lowercase(),
            repoSlug = pullRequest.repoSlug.lowercase(),
            id = pullRequest.summary.id
        )
        return visibleEntriesByKey[key]
    }

    private fun updateFilter(filter: PullRequestListFilter) {
        state.value = state.value.copy(filter = filter)
        onFilterChangedCallback(filter)
        recompute()
    }

    private fun recompute() {
        val filter = state.value.filter
        val isCurrentUserKnown = !currentUserUuid.isNullOrBlank()
        // While the current project is pending, a saved In current project choice behaves as In any project. When the
        // project is unavailable, that choice keeps nothing (a null repository matches no row).
        val currentRepository = (currentProject as? CurrentProjectRepositoryState.Found)?.repository
        val effectiveProjectScopeFilter = if (currentProject == CurrentProjectRepositoryState.Pending) {
            filter.copy(projectScope = PullRequestProjectScope.AnyProject)
        } else {
            filter
        }
        val isCurrentProjectFilterUnavailable = currentProject == CurrentProjectRepositoryState.Unavailable &&
            effectiveProjectScopeFilter.projectScope == PullRequestProjectScope.CurrentProject
        val entriesBySource = sources
            .mapNotNull { (source, sourceState) ->
                (sourceState as? PullRequestSourceState.Loaded)?.let { loaded -> source to loaded.entries }
            }
            .toMap()
        val merged = PullRequestListMerger.merge(entriesBySource = entriesBySource, openFirst = true)
        val visibleEntries = PullRequestListFiltering.apply(
            entries = merged,
            filter = effectiveProjectScopeFilter,
            currentUserUuid = currentUserUuid,
            currentProjectRepository = currentRepository
        )
        visibleEntriesByKey = visibleEntries.associateBy { entry -> entry.key }
        val resolvedOrganizationUrl = organizationUrl()
        val rows = visibleEntries.map { entry ->
            buildWorkspacePullRequestRow(
                pullRequest = entry.pullRequest,
                organizationUrl = resolvedOrganizationUrl,
                authorAvatarUrl = entry.pullRequest.summary.authorAvatarUrl,
                repoAvatarUrl = entry.repoAvatarUrl,
                isApprovedByMe = PullRequestListFiltering.isApprovedBy(
                    summary = entry.pullRequest.summary,
                    userUuid = currentUserUuid
                )
            )
        }
        val totalCount = merged.size
        val card = derivePullRequestsCard(
            sources = sources,
            totalCount = totalCount,
            visibleCount = rows.size,
            isCurrentProjectFilterUnavailable = isCurrentProjectFilterUnavailable
        )
        val anyLoaded = sources.values.any { sourceState -> sourceState is PullRequestSourceState.Loaded }
        val isUserFilterUnavailable = filter.excludeApprovedByMe && !isCurrentUserKnown && anyLoaded
        // The pill tracks the visible open rows (after filters), so toggling an Exclude checkbox updates it (decision 4c).
        val visibleOpenCount = visibleEntries.count { entry -> entry.pullRequest.summary.state == PullRequestState.Open }
        state.value = state.value.copy(
            card = card,
            rows = rows,
            totalCount = totalCount,
            isAnySourceLoading = sources.values.any { sourceState -> sourceState is PullRequestSourceState.Loading },
            failedSources = sources.filterValues { sourceState -> sourceState is PullRequestSourceState.Failed }.keys,
            truncatedSources = sources
                .filterValues { sourceState -> sourceState is PullRequestSourceState.Loaded && sourceState.isTruncated }
                .keys,
            loadedSources = sources.filterValues { sourceState -> sourceState is PullRequestSourceState.Loaded }.keys,
            isUserFilterUnavailable = isUserFilterUnavailable,
            isProjectScopeOffered = currentProject != CurrentProjectRepositoryState.Pending,
            isCurrentProjectUnavailable = currentProject == CurrentProjectRepositoryState.Unavailable
        )
        LOG.debug("$logName: merged $totalCount rows, ${rows.size} visible after filters")
        onCountsChanged(visibleOpenCount, card)
    }

    private fun bumpAvatarRevision() {
        state.value = state.value.copy(avatarRevision = state.value.avatarRevision + 1)
    }

    private fun describe(state: CurrentProjectRepositoryState): String {
        return when (state) {
            CurrentProjectRepositoryState.Pending -> "Pending"
            CurrentProjectRepositoryState.Unavailable -> "Unavailable"
            is CurrentProjectRepositoryState.Found -> "Found(${state.repository.repoSlug})"
        }
    }

    companion object {
        private val LOG = logger<PullRequestsViewModel>()
    }
}