package com.github.fnunezkanut.bbcloud.ui.otherprojects

import com.github.fnunezkanut.bbcloud.bitbucket.AuthoredPullRequestListResult
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewingPullRequestListResult
import com.github.fnunezkanut.bbcloud.cache.PullRequestCacheRefresh
import com.github.fnunezkanut.bbcloud.settings.PullRequestListFilter
import com.github.fnunezkanut.bbcloud.settings.PullRequestListScope
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestListingSource
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsCard
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsViewModel
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class WorkspacePullRequestsLoaderTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `loadWorkspacePullRequests - fails both sources and starts no load when no organization URL is set`() {
        // GIVEN
        val dataSource = FakeWorkspacePullRequestsDataSource(organizationUrl = null)
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadWorkspacePullRequests(excludedRepoSlug = null, coroutineScope = scope)

        // THEN
        assertSoftly {
            dataSource.authoredCalls shouldBe 0
            dataSource.reviewingCalls shouldBe 0
            fixture.pullRequestsViewModel.state.value.card shouldBe PullRequestsCard.Error
            fixture.headerViewModel.state.value.isRefreshVisible shouldBe false
        }
    }

    @Test
    fun `loadWorkspacePullRequests - routes to not-configured when no projects directory is set`() {
        // GIVEN
        val dataSource = FakeWorkspacePullRequestsDataSource(projectsDirectory = null)
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadWorkspacePullRequests(excludedRepoSlug = null, coroutineScope = scope)

        // THEN
        assertSoftly {
            fixture.host.notConfigured shouldBe 1
            dataSource.reviewingCalls shouldBe 0
            fixture.headerViewModel.state.value.isRefreshVisible shouldBe false
        }
    }

    @Test
    fun `loadWorkspacePullRequests - keeps refresh disabled until both sources finish`() {
        // GIVEN
        val authored = CompletableDeferred<AuthoredPullRequestListResult>()
        val reviewing = CompletableDeferred<ReviewingPullRequestListResult>()
        val dataSource = FakeWorkspacePullRequestsDataSource(authoredResult = authored, reviewingResult = reviewing)
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadWorkspacePullRequests(excludedRepoSlug = "current", coroutineScope = scope)
        val whileBothRun = fixture.headerViewModel.state.value.isRefreshEnabled
        authored.complete(AuthoredPullRequestListResult.Success(pullRequests = emptyList()))
        val whileOneRuns = fixture.headerViewModel.state.value.isRefreshEnabled
        reviewing.complete(ReviewingPullRequestListResult.Success(pullRequests = emptyList()))
        val afterBoth = fixture.headerViewModel.state.value.isRefreshEnabled

        // THEN
        assertSoftly {
            listOf(whileBothRun, whileOneRuns, afterBoth) shouldBe listOf(false, false, true)
            fixture.host.content shouldBe 2
            dataSource.lastExcludedRepoSlug shouldBe "current"
            fixture.headerViewModel.state.value.workspaceName shouldBe "acme"
        }
    }

    @Test
    fun `loadWorkspacePullRequests - a forced refresh re-checks watched projects before reading the watched set`() {
        // GIVEN
        val dataSource = FakeWorkspacePullRequestsDataSource()
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadWorkspacePullRequests(excludedRepoSlug = null, coroutineScope = scope, refresh = PullRequestCacheRefresh.Force)

        // THEN the re-check ran once, the reviewing load was forced, and it saw the rechecked set
        assertSoftly {
            dataSource.recheckCalls shouldBe 1
            dataSource.lastRefresh shouldBe PullRequestCacheRefresh.Force
            dataSource.lastRepoSlugs shouldBe listOf("repo", "other")
        }
    }

    @Test
    fun `loadWorkspacePullRequests - an automatic load neither re-checks nor forces the cache`() {
        // GIVEN
        val dataSource = FakeWorkspacePullRequestsDataSource()
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadWorkspacePullRequests(excludedRepoSlug = null, coroutineScope = scope)

        // THEN
        assertSoftly {
            dataSource.recheckCalls shouldBe 0
            dataSource.lastRefresh shouldBe PullRequestCacheRefresh.IfStale
            dataSource.lastRepoSlugs shouldBe listOf("repo")
        }
    }

    @Test
    fun `loadWorkspacePullRequests - a failed re-check still loads with the stored watched set`() {
        // GIVEN a re-check that throws
        val dataSource = FakeWorkspacePullRequestsDataSource(recheckFailure = IllegalStateException("scan failed"))
        val fixture = fixture(dataSource = dataSource)

        // WHEN
        fixture.loader.loadWorkspacePullRequests(excludedRepoSlug = null, coroutineScope = scope, refresh = PullRequestCacheRefresh.Force)

        // THEN both sources still load, with the stored set
        assertSoftly {
            dataSource.reviewingCalls shouldBe 1
            dataSource.lastRepoSlugs shouldBe listOf("repo")
            fixture.host.content shouldBe 2
        }
    }

    @Test
    fun `showNotConfigured - hides the header refresh and the listing`() {
        // GIVEN
        val fixture = fixture(dataSource = FakeWorkspacePullRequestsDataSource())

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

    private fun fixture(dataSource: WorkspacePullRequestsDataSource): Fixture {
        val host = HostCalls()
        val headerViewModel = WorkspaceHeaderViewModel(onRefreshRequested = {})
        val pullRequestsViewModel = PullRequestsViewModel(
            logName = "Workspace",
            sourcesOfTab = setOf(PullRequestListingSource.WorkspaceAuthored, PullRequestListingSource.WorkspaceAssigned),
            scope = PullRequestListScope.Workspace,
            initialFilter = PullRequestListFilter(),
            organizationUrl = { "https://bitbucket.org/acme/" },
            onFilterChangedCallback = {},
            onOpenTitleCallback = {},
            onOpenPrOnWebCallback = {},
            onOpenRepositoryCallback = {}
        )
        val loader = WorkspacePullRequestsLoader(
            pullRequestsViewModel = pullRequestsViewModel,
            headerViewModel = headerViewModel,
            dataSource = dataSource,
            onShowContentCallback = { host.content++ },
            onShowNotConfiguredCallback = { host.notConfigured++ },
            runOnEdt = { block -> block() }
        )
        return Fixture(loader = loader, headerViewModel = headerViewModel, pullRequestsViewModel = pullRequestsViewModel, host = host)
    }

    private data class Fixture(
        val loader: WorkspacePullRequestsLoader,
        val headerViewModel: WorkspaceHeaderViewModel,
        val pullRequestsViewModel: PullRequestsViewModel,
        val host: HostCalls
    )

    private class HostCalls {
        var content = 0
        var notConfigured = 0
    }

    private class FakeWorkspacePullRequestsDataSource(
        private val organizationUrl: String? = "https://bitbucket.org/acme/",
        private val projectsDirectory: String? = "/projects",
        private val authoredResult: CompletableDeferred<AuthoredPullRequestListResult> =
            CompletableDeferred(AuthoredPullRequestListResult.Success(pullRequests = emptyList())),
        private val reviewingResult: CompletableDeferred<ReviewingPullRequestListResult> =
            CompletableDeferred(ReviewingPullRequestListResult.Success(pullRequests = emptyList())),
        /** When set, the re-check throws this instead of changing the watched set. */
        private val recheckFailure: Throwable? = null
    ) : WorkspacePullRequestsDataSource {
        var authoredCalls = 0
        var reviewingCalls = 0
        var recheckCalls = 0
        var lastExcludedRepoSlug: String? = null
        var lastRefresh: PullRequestCacheRefresh? = null
        var lastRepoSlugs: List<String> = emptyList()

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

        override suspend fun listAuthored(organizationUrl: String, excludedRepoSlug: String?, refresh: PullRequestCacheRefresh): AuthoredPullRequestListResult {
            authoredCalls++
            lastExcludedRepoSlug = excludedRepoSlug
            lastRefresh = refresh
            return authoredResult.await()
        }

        override suspend fun listReviewing(
            organizationUrl: String,
            excludedRepoSlug: String?,
            repoSlugs: List<String>,
            refresh: PullRequestCacheRefresh
        ): ReviewingPullRequestListResult {
            reviewingCalls++
            lastExcludedRepoSlug = excludedRepoSlug
            lastRepoSlugs = repoSlugs
            lastRefresh = refresh
            return reviewingResult.await()
        }

        override suspend fun recheckWatchedProjects() {
            recheckCalls++
            recheckFailure?.let { failure -> throw failure }
            watched = listOf("repo", "other")
        }
    }
}