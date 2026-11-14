package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.AuthoredPullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.ProjectRepositoryStatus
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListSource
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequest
import com.github.fnunezkanut.bbcloud.bitbucket.WatchedPullRequestListResult
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.settings.PullRequestListFilter
import com.github.fnunezkanut.bbcloud.settings.PullRequestUserScope
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListingSource
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsCard
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsViewModel
import com.github.fnunezkanut.bbcloud.ui.pullrequests.listEntry
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PullRequestsLoaderTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `loadOverview - fails every source and starts no load when no organization URL is set`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource(organizationUrl = null)
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN
        assertSoftly {
            dataSource.authoredCalls shouldBe 0
            dataSource.watchedCalls shouldBe 0
            dataSource.lookupCalls shouldBe 0
            fixture.pullRequestsViewModel.state.value.card shouldBe PullRequestsCard.Error
            fixture.headerViewModel.state.value.isRefreshVisible shouldBe false
        }
    }

    @Test
    fun `loadOverview - routes to not-configured when no projects directory is set`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource(projectsDirectory = null)
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN
        assertSoftly {
            fixture.host.notConfigured shouldBe 1
            dataSource.watchedCalls shouldBe 0
            fixture.headerViewModel.state.value.isRefreshVisible shouldBe false
        }
    }

    @Test
    fun `loadOverview - applies all three sources and passes the found repository to the watched source`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource(
            currentProjectListing = listingOf(listOf(row(id = 1, repoSlug = "current")), source = PullRequestListSource.Cache),
            authoredRows = listOf(row(id = 2, repoSlug = "mine")),
            watchedRows = listOf(row(id = 3, repoSlug = "watched"))
        )
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN the Created by others scope hides the authored row 2
        assertSoftly {
            fixture.pullRequestsViewModel.state.value.rows.map { it.pullRequest.summary.id }.toSet() shouldBe setOf(1L, 3L)
            dataSource.lastExcludedRepoSlug shouldBe "current"
            fixture.pullRequestsViewModel.state.value.isProjectScopeOffered shouldBe true
            fixture.headerViewModel.state.value.isCreatePullRequestVisible shouldBe true
            fixture.headerViewModel.state.value.workspaceName shouldBe "acme"
        }
    }

    @Test
    fun `loadOverview - applies the authored source even when the current project lookup is slow`() {
        // GIVEN a lookup that has not answered yet
        val lookup = CompletableDeferred<CurrentProjectLookup>()
        val dataSource = FakePullRequestsDataSource(lookupResult = lookup, authoredRows = listOf(row(id = 2, repoSlug = "mine")))
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN the authored row is listed and the watched source has not been asked yet
        assertSoftly {
            fixture.pullRequestsViewModel.state.value.rows.map { it.pullRequest.summary.id } shouldBe listOf(2L)
            dataSource.watchedCalls shouldBe 0
            fixture.headerViewModel.state.value.isRefreshEnabled shouldBe false
        }
    }

    @Test
    fun `loadOverview - an unavailable project loads empty project sources and still loads the watched source`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource(
            lookupResult = CompletableDeferred(CurrentProjectLookup.Unavailable(status = ProjectRepositoryStatus.NoLocalProjectPath)),
            watchedRows = listOf(row(id = 3, repoSlug = "watched"))
        )
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN
        assertSoftly {
            dataSource.listCurrentProjectCalls shouldBe 0
            dataSource.lastExcludedRepoSlug shouldBe null
            fixture.pullRequestsViewModel.state.value.loadedSources shouldBe setOf(
                PullRequestListingSource.ProjectOpen,
                PullRequestListingSource.ProjectResolved,
                PullRequestListingSource.WatchedOpen,
                PullRequestListingSource.WatchedResolved,
                PullRequestListingSource.WorkspaceAuthored
            )
            fixture.pullRequestsViewModel.state.value.isCurrentProjectUnavailable shouldBe true
            fixture.headerViewModel.state.value.isCreatePullRequestVisible shouldBe false
            fixture.pullRequestsViewModel.state.value.rows.map { it.pullRequest.summary.id } shouldBe listOf(3L)
        }
    }

    @Test
    fun `loadOverview - splits watched rows into open and resolved sources by state`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource(
            watchedRows = listOf(
                row(id = 3, repoSlug = "watched", state = PullRequestState.Open),
                row(id = 4, repoSlug = "watched", state = PullRequestState.Merged)
            )
        )
        val fixture =
            fixture(
                dataSource = dataSource,
                filter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false)
            )

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN open rows come first
        fixture.pullRequestsViewModel.state.value.rows.map { it.pullRequest.summary.id } shouldBe listOf(3L, 4L)
    }

    @Test
    fun `loadOverview - a forced refresh re-checks watched projects first and forces only the current project and authored sources`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource()
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope, refresh = PullRequestCacheRefresh.Force)

        // THEN the watched set was re-checked, and the watched source still uses IfStale
        assertSoftly {
            dataSource.recheckCalls shouldBe 1
            dataSource.lastWatchedSlugs shouldBe listOf("repo", "other")
            dataSource.lookupRefresh shouldBe PullRequestCacheRefresh.Force
            dataSource.currentProjectRefresh shouldBe PullRequestCacheRefresh.Force
            dataSource.authoredRefresh shouldBe PullRequestCacheRefresh.Force
            dataSource.watchedRefresh shouldBe PullRequestCacheRefresh.IfStale
        }
    }

    @Test
    fun `loadOverview - an automatic load neither re-checks nor forces the cache`() {
        // GIVEN
        val dataSource = FakePullRequestsDataSource()
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN
        assertSoftly {
            dataSource.recheckCalls shouldBe 0
            dataSource.lastWatchedSlugs shouldBe listOf("repo")
            dataSource.currentProjectRefresh shouldBe PullRequestCacheRefresh.IfStale
            dataSource.authoredRefresh shouldBe PullRequestCacheRefresh.IfStale
        }
    }

    @Test
    fun `loadOverview - a failed re-check still loads with the stored watched set`() {
        // GIVEN a re-check that throws
        val dataSource = FakePullRequestsDataSource(recheckFailure = IllegalStateException("scan failed"))
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope, refresh = PullRequestCacheRefresh.Force)

        // THEN
        assertSoftly {
            dataSource.watchedCalls shouldBe 1
            dataSource.lastWatchedSlugs shouldBe listOf("repo")
        }
    }

    @Test
    fun `loadOverview - a live current project listing hands its resolved ids to the cleanup and a cached one does not`() {
        // GIVEN a live listing with one open and one merged pull request
        val live = FakePullRequestsDataSource(
            currentProjectListing = listingOf(
                listOf(row(id = 1, repoSlug = "current"), row(id = 2, repoSlug = "current", state = PullRequestState.Merged)),
                source = PullRequestListSource.Live
            )
        )
        val liveFixture = fixture(dataSource = live)
        val cached = FakePullRequestsDataSource(
            currentProjectListing = listingOf(
                listOf(row(id = 2, repoSlug = "current", state = PullRequestState.Merged)),
                source = PullRequestListSource.Cache
            )
        )
        val cachedFixture = fixture(dataSource = cached)

        // WHEN
        liveFixture.loader.loadOverview(coroutineScope = scope)
        cachedFixture.loader.loadOverview(coroutineScope = scope)

        // THEN
        assertSoftly {
            liveFixture.host.cleanups shouldBe listOf(Triple("acme", "current", setOf(2L)))
            cachedFixture.host.cleanups shouldBe emptyList()
        }
    }

    @Test
    fun `loadOverview - every watched repository listed live triggers one cleanup with its own resolved ids`() {
        // GIVEN two watched repositories, only one of them listed live
        val dataSource = FakePullRequestsDataSource(
            watchedRows = listOf(
                row(id = 10, repoSlug = "alpha", state = PullRequestState.Merged),
                row(id = 11, repoSlug = "alpha", state = PullRequestState.Open),
                row(id = 20, repoSlug = "beta", state = PullRequestState.Declined)
            ),
            watchedLiveSlugs = setOf("alpha")
        )
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)

        // THEN
        fixture.host.cleanups shouldBe listOf(Triple("acme", "alpha", setOf(10L)))
    }

    @Test
    fun `loadOverview - keeps refresh disabled until every load ends`() {
        // GIVEN
        val authored = CompletableDeferred<AuthoredPullRequestListResult>()
        val watched = CompletableDeferred<WatchedPullRequestListResult>()
        val dataSource = FakePullRequestsDataSource(authoredResult = authored, watchedResult = watched)
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadOverview(coroutineScope = scope)
        val whileAllRun = fixture.headerViewModel.state.value.isRefreshEnabled
        authored.complete(AuthoredPullRequestListResult.Success(pullRequests = emptyList()))
        val whileWatchedRuns = fixture.headerViewModel.state.value.isRefreshEnabled
        watched.complete(watchedSuccess(rows = emptyList()))
        val afterAll = fixture.headerViewModel.state.value.isRefreshEnabled

        // THEN
        listOf(whileAllRun, whileWatchedRuns, afterAll) shouldBe listOf(false, false, true)
    }

    @Test
    fun `reloadCurrentProject - reloads only the project sources`() {
        // GIVEN a loaded overview
        val dataSource = FakePullRequestsDataSource(
            currentProjectListing = listingOf(listOf(row(id = 1, repoSlug = "current")), source = PullRequestListSource.Cache),
            authoredRows = listOf(row(id = 2, repoSlug = "mine")),
            watchedRows = listOf(row(id = 3, repoSlug = "watched"))
        )
        val fixture = fixture(dataSource = dataSource)
        fixture.loader.loadOverview(coroutineScope = scope)
        val authoredCalls = dataSource.authoredCalls
        val watchedCalls = dataSource.watchedCalls
        dataSource.currentProjectListing = listingOf(listOf(row(id = 5, repoSlug = "current")), source = PullRequestListSource.Cache)

        // WHEN
        fixture.loader.reloadCurrentProject(coroutineScope = scope, reason = "test")

        // THEN the project rows changed, the others were not requested again, and the current project was forced
        assertSoftly {
            fixture.pullRequestsViewModel.state.value.rows.map { it.pullRequest.summary.id }.toSet() shouldBe setOf(5L, 3L)
            dataSource.authoredCalls shouldBe authoredCalls
            dataSource.watchedCalls shouldBe watchedCalls
            dataSource.currentProjectRefresh shouldBe PullRequestCacheRefresh.Force
        }
    }

    @Test
    fun `showNotConfigured - hides the header refresh and the listing`() {
        // GIVEN
        val fixture = fixture(dataSource = FakePullRequestsDataSource())

        // WHEN
        fixture.loader.showNotConfigured()

        // THEN
        assertSoftly {
            fixture.host.notConfigured shouldBe 1
            fixture.headerViewModel.state.value.isRefreshVisible shouldBe false
            fixture.headerViewModel.state.value.isRefreshEnabled shouldBe false
            fixture.pullRequestsViewModel.state.value.card shouldBe PullRequestsCard.Hidden
        }
    }

    private fun row(id: Long, repoSlug: String, state: PullRequestState = PullRequestState.Open): ReviewingPullRequest {
        return listEntry(id = id, source = PullRequestListingSource.WatchedOpen, repoSlug = repoSlug, state = state).pullRequest
    }

    private fun listingOf(rows: List<ReviewingPullRequest>, source: PullRequestListSource): CurrentProjectListing {
        return CurrentProjectListing(
            result = PullRequestListResult.Success(pullRequests = rows.map { it.summary }, source = source),
            currentUserUuid = "{me}"
        )
    }

    private fun watchedSuccess(rows: List<ReviewingPullRequest>, liveSlugs: Set<String> = emptySet()): WatchedPullRequestListResult.Success {
        return WatchedPullRequestListResult.Success(
            pullRequests = rows,
            isTruncated = false,
            failedRepoSlugs = emptySet(),
            liveRepoSlugs = liveSlugs,
            currentUserUuid = "{me}"
        )
    }

    private fun fixture(
        dataSource: FakePullRequestsDataSource,
        filter: PullRequestListFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others)
    ): Fixture {
        val host = HostCalls()
        val headerViewModel = WorkspaceHeaderViewModel(onRefreshRequested = {})
        val pullRequestsViewModel = PullRequestsViewModel(
            logName = "Pull Requests",
            sourcesOfTab = PullRequestListingSource.entries.toSet(),
            initialFilter = filter,
            organizationUrl = { "https://bitbucket.org/acme/" },
            onFilterChangedCallback = {},
            onOpenTitleCallback = {},
            onOpenPrOnWebCallback = {},
            onOpenRepositoryCallback = {}
        )
        val loader = PullRequestsLoader(
            pullRequestsViewModel = pullRequestsViewModel,
            headerViewModel = headerViewModel,
            dataSource = dataSource,
            onShowContentCallback = { host.content++ },
            onShowNotConfiguredCallback = { host.notConfigured++ },
            onCurrentProjectStatusChanged = { lookup -> headerViewModel.showCurrentProject(lookup = lookup) },
            onRepositoryListedLive = { workspace, repoSlug, resolvedIds -> host.cleanups.add(Triple(workspace, repoSlug, resolvedIds)) },
            runOnEdt = { block -> block() }
        )
        return Fixture(loader = loader, headerViewModel = headerViewModel, pullRequestsViewModel = pullRequestsViewModel, host = host)
    }

    private data class Fixture(
        val loader: PullRequestsLoader,
        val headerViewModel: WorkspaceHeaderViewModel,
        val pullRequestsViewModel: PullRequestsViewModel,
        val host: HostCalls
    )

    private class HostCalls {
        var content = 0
        var notConfigured = 0
        val cleanups = mutableListOf<Triple<String, String, Set<Long>>>()
    }

    private inner class FakePullRequestsDataSource(
        private val organizationUrl: String? = "https://bitbucket.org/acme/",
        private val projectsDirectory: String? = "/projects",
        private val lookupResult: CompletableDeferred<CurrentProjectLookup> = CompletableDeferred(
            CurrentProjectLookup.Found(
                workspace = "acme",
                repoSlug = "current",
                repositoryWebUrl = "https://bitbucket.org/acme/current",
                avatarUrl = null
            )
        ),
        var currentProjectListing: CurrentProjectListing = listingOf(emptyList(), source = PullRequestListSource.Cache),
        authoredRows: List<ReviewingPullRequest> = emptyList(),
        watchedRows: List<ReviewingPullRequest> = emptyList(),
        watchedLiveSlugs: Set<String> = emptySet(),
        private val authoredResult: CompletableDeferred<AuthoredPullRequestListResult> =
            CompletableDeferred(AuthoredPullRequestListResult.Success(pullRequests = authoredRows)),
        private val watchedResult: CompletableDeferred<WatchedPullRequestListResult> =
            CompletableDeferred(watchedSuccess(rows = watchedRows, liveSlugs = watchedLiveSlugs)),
        private val recheckFailure: Throwable? = null
    ) : PullRequestsDataSource {
        var lookupCalls = 0
        var listCurrentProjectCalls = 0
        var authoredCalls = 0
        var watchedCalls = 0
        var recheckCalls = 0
        var lastExcludedRepoSlug: String? = null
        var lastWatchedSlugs: List<String> = emptyList()
        var lookupRefresh: PullRequestCacheRefresh? = null
        var currentProjectRefresh: PullRequestCacheRefresh? = null
        var authoredRefresh: PullRequestCacheRefresh? = null
        var watchedRefresh: PullRequestCacheRefresh? = null

        /** The watched set; a successful re-check adds "other", so tests can see whether the re-check ran first. */
        private var watched = listOf("repo")

        override fun organizationUrlOrNull(): String? {
            return organizationUrl
        }

        override fun projectsDirectoryOrNull(): String? {
            return projectsDirectory
        }

        override fun watchedRepoSlugs(): List<String> {
            return watched
        }

        override fun repoAvatarUrls(): Map<String, String> {
            return emptyMap()
        }

        override suspend fun lookupCurrentProject(refresh: PullRequestCacheRefresh): CurrentProjectLookup {
            lookupCalls++
            lookupRefresh = refresh
            return lookupResult.await()
        }

        override suspend fun listCurrentProject(refresh: PullRequestCacheRefresh): CurrentProjectListing {
            listCurrentProjectCalls++
            currentProjectRefresh = refresh
            return currentProjectListing
        }

        override suspend fun listAuthored(organizationUrl: String, refresh: PullRequestCacheRefresh): AuthoredPullRequestListResult {
            authoredCalls++
            authoredRefresh = refresh
            return authoredResult.await()
        }

        override suspend fun listWatched(
            organizationUrl: String,
            repoSlugs: List<String>,
            excludedRepoSlug: String?,
            refresh: PullRequestCacheRefresh
        ): WatchedPullRequestListResult {
            watchedCalls++
            lastExcludedRepoSlug = excludedRepoSlug
            lastWatchedSlugs = repoSlugs
            watchedRefresh = refresh
            return watchedResult.await()
        }

        override suspend fun recheckWatchedProjects() {
            recheckCalls++
            recheckFailure?.let { failure -> throw failure }
            watched = listOf("repo", "other")
        }
    }
}