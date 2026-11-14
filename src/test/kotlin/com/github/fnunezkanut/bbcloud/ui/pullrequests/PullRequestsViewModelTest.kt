package com.github.fnunezkanut.bbcloud.ui.pullrequests

import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestReviewer
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.github.fnunezkanut.bbcloud.bitbucket.ReviewerStatus
import com.github.fnunezkanut.bbcloud.settings.PullRequestListFilter
import com.github.fnunezkanut.bbcloud.settings.PullRequestProjectScope
import com.github.fnunezkanut.bbcloud.settings.PullRequestUserScope
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

private const val CURRENT_USER = "{5b10ac8d}"

@Tag("unit")
class PullRequestsViewModelTest {
    @Test
    fun `onExcludeDraftsChanged - should persist the filter and recompute without new source loads`() {
        // GIVEN a project view model with one open and one draft row loaded
        val onFilterChanged = mockk<(PullRequestListFilter) -> Unit>(relaxed = true)
        val viewModel =
            projectViewModel(
                onFilterChanged = onFilterChanged,
                initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false)
            )
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            loadBothProjectSources(
                viewModel = viewModel,
                generation = generation,
                openEntries = listOf(
                    listEntry(id = 1, source = PullRequestListingSource.ProjectOpen, isDraft = false),
                    listEntry(id = 2, source = PullRequestListingSource.ProjectOpen, isDraft = true)
                ),
                scope = scope
            )

            // WHEN the Drafts exclusion is toggled on
            viewModel.onExcludeDraftsChanged(checked = true)

            // THEN the callback is given the updated filter, and the draft row is filtered out without a reload
            verify {
                onFilterChanged(
                    PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeApprovedByMe = false, excludeDrafts = true)
                )
            }
            viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(1L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onOpenTitle - should route the matching entry with its source to the callback`() {
        // GIVEN a filter that keeps resolved rows visible, so the resolved entry can be looked up for routing
        val opened = mutableListOf<PullRequestListEntry>()
        val viewModel = projectViewModel(
            onOpenTitle = { entry -> opened.add(entry) },
            initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false)
        )
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            val resolvedEntry = listEntry(id = 9, source = PullRequestListingSource.ProjectResolved, state = PullRequestState.Merged)
            loadBothProjectSources(
                viewModel = viewModel,
                generation = generation,
                openEntries = emptyList(),
                resolvedEntries = listOf(resolvedEntry),
                scope = scope
            )

            // WHEN a title click arrives for the resolved pull request
            viewModel.onOpenTitle(pullRequest = resolvedEntry.pullRequest)

            // THEN the matching entry, with its source, is forwarded
            opened.map { it.source } shouldContainExactly listOf(PullRequestListingSource.ProjectResolved)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failSource - should keep the other source rows visible and list the failed source`() {
        // GIVEN a workspace view model where one source loaded and the other failed
        val viewModel = workspaceViewModel()
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.WatchedOpen,
                entries = listOf(listEntry(id = 1, source = PullRequestListingSource.WatchedOpen)),
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )
            viewModel.failSource(generation = generation, source = PullRequestListingSource.WorkspaceAuthored)

            // THEN the loaded rows stay visible and the failed source is reported
            viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(1L)
            viewModel.state.value.failedSources shouldBe setOf(PullRequestListingSource.WorkspaceAuthored)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `applySource - should ignore a stale generation`() {
        // GIVEN a view model with a current generation
        val viewModel = projectViewModel()
        val scope = CoroutineScope(SupervisorJob())
        try {
            viewModel.beginRefresh()
            val staleGeneration = -1L

            // WHEN a response from a stale generation arrives
            viewModel.applySource(
                generation = staleGeneration,
                source = PullRequestListingSource.ProjectOpen,
                entries = listOf(listEntry(id = 99, source = PullRequestListingSource.ProjectOpen)),
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )

            // THEN it is dropped, so no rows appear
            viewModel.state.value.rows shouldBe emptyList()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onCountsChanged - should report the visible open count before and after a filter toggle`() {
        // GIVEN a project view model that records every counts callback
        val counts = mutableListOf<Int>()
        val viewModel = projectViewModel(
            onCountsChanged = { openCount, _ -> counts.add(openCount) },
            initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false)
        )
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            loadBothProjectSources(
                viewModel = viewModel,
                generation = generation,
                openEntries = listOf(
                    listEntry(id = 1, source = PullRequestListingSource.ProjectOpen, state = PullRequestState.Open, isDraft = false),
                    listEntry(id = 2, source = PullRequestListingSource.ProjectOpen, state = PullRequestState.Open, isDraft = true)
                ),
                scope = scope
            )
            // Two visible open rows after loading
            counts.last() shouldBe 2

            // WHEN the Drafts exclusion hides one open row
            viewModel.onExcludeDraftsChanged(checked = true)

            // THEN the reported visible open count drops to one (decision 4c)
            counts.last() shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `merged rows - should list open rows before resolved rows, newest first within each group`() {
        // GIVEN a resolved row that is newer than two open rows
        val viewModel =
            workspaceViewModel(initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.WatchedOpen,
                entries = listOf(
                    listEntry(id = 1, source = PullRequestListingSource.WatchedOpen, updatedOn = recent(hoursAgo = 3)),
                    listEntry(id = 2, source = PullRequestListingSource.WatchedOpen, updatedOn = recent(hoursAgo = 2))
                ),
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.WatchedResolved,
                entries = listOf(
                    listEntry(
                        id = 3,
                        source = PullRequestListingSource.WatchedResolved,
                        state = PullRequestState.Merged,
                        updatedOn = recent(hoursAgo = 1)
                    )
                ),
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )

            // THEN open rows come first (newest first), then the resolved row
            viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(2L, 1L, 3L)
        } finally {
            scope.cancel()
        }
    }

    @Nested
    inner class ProjectScope {
        @Test
        fun `setCurrentProject - should keep only the rows of the found repository when In current project is selected`() {
            // GIVEN rows of two repositories and a saved In current project choice
            val viewModel = workspaceViewModel(
                initialFilter = PullRequestListFilter(
                    userScope = PullRequestUserScope.Others,
                    excludeResolved = false,
                    excludeDrafts = false,
                    projectScope = PullRequestProjectScope.CurrentProject
                )
            )
            val scope = CoroutineScope(SupervisorJob())
            try {
                val generation = viewModel.beginRefresh()
                loadWatched(
                    viewModel = viewModel,
                    generation = generation,
                    scope = scope,
                    entries = listOf(
                        listEntry(id = 1, source = PullRequestListingSource.WatchedOpen, repoSlug = "current"),
                        listEntry(id = 2, source = PullRequestListingSource.WatchedOpen, repoSlug = "other")
                    )
                )

                // WHEN the current project is found
                viewModel.setCurrentProject(
                    state = CurrentProjectRepositoryState.Found(repository = PullRequestRepositoryKey.of(workspace = "acme", repoSlug = "current"))
                )

                // THEN only its row stays and the dropdown offers the option
                assertSoftly {
                    viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(1L)
                    viewModel.state.value.isProjectScopeOffered shouldBe true
                }
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `pending current project - should behave as In any project and not offer the option`() {
            // GIVEN a saved In current project choice and a lookup that has not resolved
            val viewModel = workspaceViewModel(
                initialFilter = PullRequestListFilter(
                    userScope = PullRequestUserScope.Others,
                    excludeResolved = false,
                    excludeDrafts = false,
                    projectScope = PullRequestProjectScope.CurrentProject
                )
            )
            val scope = CoroutineScope(SupervisorJob())
            try {
                val generation = viewModel.beginRefresh()
                loadWatched(
                    viewModel = viewModel,
                    generation = generation,
                    scope = scope,
                    entries = listOf(
                        listEntry(id = 1, source = PullRequestListingSource.WatchedOpen, repoSlug = "current"),
                        listEntry(id = 2, source = PullRequestListingSource.WatchedOpen, repoSlug = "other")
                    )
                )

                // THEN every row is listed, the option is not offered, and the saved choice is kept
                assertSoftly {
                    viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
                    viewModel.state.value.isProjectScopeOffered shouldBe false
                    viewModel.state.value.filter.projectScope shouldBe PullRequestProjectScope.CurrentProject
                }
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `unavailable current project - should show no rows and the dedicated card for In current project`() {
            // GIVEN a saved In current project choice and a project that is not a Bitbucket repository
            val viewModel = workspaceViewModel(
                initialFilter = PullRequestListFilter(
                    userScope = PullRequestUserScope.Others,
                    excludeResolved = false,
                    excludeDrafts = false,
                    projectScope = PullRequestProjectScope.CurrentProject
                )
            )
            val scope = CoroutineScope(SupervisorJob())
            try {
                val generation = viewModel.beginRefresh()
                loadWatched(
                    viewModel = viewModel,
                    generation = generation,
                    scope = scope,
                    entries = listOf(listEntry(id = 1, source = PullRequestListingSource.WatchedOpen, repoSlug = "other"))
                )
                loadRemainingSourcesEmpty(viewModel = viewModel, generation = generation, scope = scope)

                // WHEN the lookup reports the project unavailable
                viewModel.setCurrentProject(state = CurrentProjectRepositoryState.Unavailable)

                // THEN no row passes and the card says why
                assertSoftly {
                    viewModel.state.value.rows shouldBe emptyList()
                    viewModel.state.value.card shouldBe PullRequestsCard.CurrentProjectUnavailable
                    viewModel.state.value.isProjectScopeOffered shouldBe true
                    viewModel.state.value.isCurrentProjectUnavailable shouldBe true
                }
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `unavailable current project - should list every row for In any project`() {
            // GIVEN the default In any project choice and an unavailable project
            val viewModel =
                workspaceViewModel(
                    initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false)
                )
            val scope = CoroutineScope(SupervisorJob())
            try {
                val generation = viewModel.beginRefresh()
                loadWatched(
                    viewModel = viewModel,
                    generation = generation,
                    scope = scope,
                    entries = listOf(listEntry(id = 1, source = PullRequestListingSource.WatchedOpen, repoSlug = "other"))
                )
                loadRemainingSourcesEmpty(viewModel = viewModel, generation = generation, scope = scope)

                // WHEN the lookup reports the project unavailable
                viewModel.setCurrentProject(state = CurrentProjectRepositoryState.Unavailable)

                // THEN the rows are listed
                viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(1L)
            } finally {
                scope.cancel()
            }
        }

        @Test
        fun `onProjectScopeChanged - should persist the filter with the new project scope`() {
            // GIVEN
            val onFilterChanged = mockk<(PullRequestListFilter) -> Unit>(relaxed = true)
            val viewModel = workspaceViewModel(onFilterChanged = onFilterChanged)

            // WHEN
            viewModel.onProjectScopeChanged(projectScope = PullRequestProjectScope.CurrentProject)

            // THEN
            verify { onFilterChanged(PullRequestListFilter(userScope = PullRequestUserScope.Others, projectScope = PullRequestProjectScope.CurrentProject)) }
        }
    }

    @Nested
    inner class PartialReload {
        @Test
        fun `beginReload - should keep the other sources loaded and ignore a response of an older full generation`() {
            // GIVEN a loaded watched source and a loaded project source
            val viewModel =
                workspaceViewModel(
                    initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false)
                )
            val scope = CoroutineScope(SupervisorJob())
            try {
                val firstGeneration = viewModel.beginRefresh()
                loadWatched(
                    viewModel = viewModel,
                    generation = firstGeneration,
                    scope = scope,
                    entries = listOf(listEntry(id = 1, source = PullRequestListingSource.WatchedOpen))
                )

                // WHEN only the project sources are reloaded
                val reloadGeneration = viewModel.beginReload(
                    reloaded = setOf(PullRequestListingSource.ProjectOpen, PullRequestListingSource.ProjectResolved)
                )

                // THEN the generation is unchanged, the watched row stays and the project sources load
                assertSoftly {
                    reloadGeneration shouldBe firstGeneration
                    viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(1L)
                    viewModel.state.value.isAnySourceLoading shouldBe true
                }

                // WHEN a full refresh starts, the late partial response is stale
                viewModel.beginRefresh()
                viewModel.applySource(
                    generation = reloadGeneration,
                    source = PullRequestListingSource.ProjectOpen,
                    entries = listOf(listEntry(id = 9, source = PullRequestListingSource.ProjectOpen)),
                    isTruncated = false,
                    currentUserUuid = CURRENT_USER,
                    coroutineScope = scope
                )
                viewModel.state.value.rows shouldBe emptyList()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `applySource - should flag rows approved by the current user and update once the user uuid arrives`() {
        // GIVEN a project view model whose first load happens before the current user uuid is known
        val viewModel =
            projectViewModel(initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            val entries = listOf(
                listEntry(id = 1, source = PullRequestListingSource.ProjectOpen, approverUuids = setOf(CURRENT_USER)),
                listEntry(id = 2, source = PullRequestListingSource.ProjectOpen, approverUuids = setOf("{other}"))
            )
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.ProjectOpen,
                entries = entries,
                isTruncated = false,
                currentUserUuid = null,
                coroutineScope = scope
            )
            viewModel.state.value.rows.associate { it.pullRequest.summary.id to it.isApprovedByMe } shouldBe
                mapOf(1L to false, 2L to false)

            // WHEN the current user uuid becomes known on the next source response
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.ProjectOpen,
                entries = entries,
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )

            // THEN only the pull request the user approved is flagged
            viewModel.state.value.rows.associate { it.pullRequest.summary.id to it.isApprovedByMe } shouldBe
                mapOf(1L to true, 2L to false)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `applySource - should populate the reviewer stack of a loaded open row`() {
        // GIVEN an open entry carrying three reviewers
        val viewModel =
            projectViewModel(initialFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others, excludeResolved = false, excludeDrafts = false))
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            val reviewers = listOf(
                reviewer(uuid = "{a}"),
                reviewer(uuid = "{b}"),
                reviewer(uuid = "{c}")
            )
            loadBothProjectSources(
                viewModel = viewModel,
                generation = generation,
                openEntries = listOf(
                    listEntry(id = 1, source = PullRequestListingSource.ProjectOpen, reviewers = reviewers)
                ),
                scope = scope
            )

            // THEN the row's reviewer stack shows two and folds the third
            val stack = viewModel.state.value.rows.single().reviewerStack
            stack.visible.map { it.uuid } shouldContainExactly listOf("{a}", "{b}")
            stack.hiddenCount shouldBe 1
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `applySource - should show every row for Assigned to me until the user uuid is known, then only assigned rows`() {
        // GIVEN a project view model saved with Assigned to me and no current user uuid yet
        val viewModel = projectViewModel(
            initialFilter = PullRequestListFilter(excludeResolved = false, excludeDrafts = false, userScope = PullRequestUserScope.AssignedToMe)
        )
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            val entries = listOf(
                listEntry(id = 1, source = PullRequestListingSource.ProjectOpen, reviewers = listOf(reviewer(uuid = CURRENT_USER))),
                listEntry(id = 2, source = PullRequestListingSource.ProjectOpen, reviewers = listOf(reviewer(uuid = "{other}")))
            )
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.ProjectOpen,
                entries = entries,
                isTruncated = false,
                currentUserUuid = null,
                coroutineScope = scope
            )

            // THEN the saved choice is treated as Created by any user, so both rows show
            viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)

            // WHEN the current user uuid arrives with the next response
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.ProjectOpen,
                entries = entries,
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )

            // THEN only the row where the user is a reviewer shows, and the saved choice is kept
            viewModel.state.value.rows.map { it.pullRequest.summary.id } shouldContainExactly listOf(1L)
            viewModel.state.value.filter.userScope shouldBe PullRequestUserScope.AssignedToMe
        } finally {
            scope.cancel()
        }
    }

    private fun reviewer(uuid: String): PullRequestReviewer {
        return PullRequestReviewer(
            uuid = uuid,
            displayName = "Reviewer $uuid",
            avatarUrl = null,
            status = ReviewerStatus.None
        )
    }

    @Test
    fun `applySource - should seed known users with the loaded entries`() {
        // GIVEN a project view model with a capturing seed lambda
        val seeded = mutableListOf<List<PullRequestListEntry>>()
        val viewModel = projectViewModel(seedKnownUsers = { entries -> seeded.add(entries) })
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            val entries = listOf(listEntry(id = 1, source = PullRequestListingSource.ProjectOpen))

            // WHEN a source loads
            viewModel.applySource(
                generation = generation,
                source = PullRequestListingSource.ProjectOpen,
                entries = entries,
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )

            // THEN the loaded entries are forwarded to the seed lambda
            seeded shouldContainExactly listOf(entries)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `applySource - should not seed known users for a stale generation`() {
        // GIVEN a project view model with a capturing seed lambda
        val seeded = mutableListOf<List<PullRequestListEntry>>()
        val viewModel = projectViewModel(seedKnownUsers = { entries -> seeded.add(entries) })
        val scope = CoroutineScope(SupervisorJob())
        try {
            val generation = viewModel.beginRefresh()
            val staleGeneration = generation - 1

            // WHEN a source arrives for a stale generation
            viewModel.applySource(
                generation = staleGeneration,
                source = PullRequestListingSource.ProjectOpen,
                entries = listOf(listEntry(id = 99, source = PullRequestListingSource.ProjectOpen)),
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )

            // THEN nothing is seeded
            seeded.shouldBeEmpty()
        } finally {
            scope.cancel()
        }
    }

    private fun loadBothProjectSources(
        viewModel: PullRequestsViewModel,
        generation: Long,
        openEntries: List<PullRequestListEntry>,
        resolvedEntries: List<PullRequestListEntry> = emptyList(),
        scope: CoroutineScope
    ) {
        viewModel.applySource(
            generation = generation,
            source = PullRequestListingSource.ProjectOpen,
            entries = openEntries,
            isTruncated = false,
            currentUserUuid = CURRENT_USER,
            coroutineScope = scope
        )
        viewModel.applySource(
            generation = generation,
            source = PullRequestListingSource.ProjectResolved,
            entries = resolvedEntries,
            isTruncated = false,
            currentUserUuid = CURRENT_USER,
            coroutineScope = scope
        )
    }

    private fun projectViewModel(
        onFilterChanged: (PullRequestListFilter) -> Unit = {},
        onOpenTitle: (PullRequestListEntry) -> Unit = {},
        onCountsChanged: (Int, PullRequestsCard) -> Unit = { _, _ -> },
        seedKnownUsers: (List<PullRequestListEntry>) -> Unit = {},
        initialFilter: PullRequestListFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others)
    ): PullRequestsViewModel {
        return PullRequestsViewModel(
            logName = "Pull Requests",
            sourcesOfTab = setOf(PullRequestListingSource.ProjectOpen, PullRequestListingSource.ProjectResolved),
            initialFilter = initialFilter,
            organizationUrl = { "https://bitbucket.org/acme/" },
            onFilterChangedCallback = onFilterChanged,
            onOpenTitleCallback = onOpenTitle,
            onOpenPrOnWebCallback = {},
            onOpenRepositoryCallback = {},
            onCountsChanged = onCountsChanged,
            seedKnownUsers = seedKnownUsers
        )
    }

    private fun loadWatched(viewModel: PullRequestsViewModel, generation: Long, scope: CoroutineScope, entries: List<PullRequestListEntry>) {
        viewModel.applySource(
            generation = generation,
            source = PullRequestListingSource.WatchedOpen,
            entries = entries,
            isTruncated = false,
            currentUserUuid = CURRENT_USER,
            coroutineScope = scope
        )
    }

    private fun loadRemainingSourcesEmpty(viewModel: PullRequestsViewModel, generation: Long, scope: CoroutineScope) {
        PullRequestListingSource.entries.filter { source -> source != PullRequestListingSource.WatchedOpen }.forEach { source ->
            viewModel.applySource(
                generation = generation,
                source = source,
                entries = emptyList(),
                isTruncated = false,
                currentUserUuid = CURRENT_USER,
                coroutineScope = scope
            )
        }
    }

    private fun workspaceViewModel(
        initialFilter: PullRequestListFilter = PullRequestListFilter(userScope = PullRequestUserScope.Others),
        onFilterChanged: (PullRequestListFilter) -> Unit = {}
    ): PullRequestsViewModel {
        return PullRequestsViewModel(
            logName = "Pull Requests",
            sourcesOfTab = PullRequestListingSource.entries.toSet(),
            initialFilter = initialFilter,
            organizationUrl = { "https://bitbucket.org/acme/" },
            onFilterChangedCallback = onFilterChanged,
            onOpenTitleCallback = {},
            onOpenPrOnWebCallback = {},
            onOpenRepositoryCallback = {}
        )
    }

    private fun recent(hoursAgo: Int): String {
        return (Clock.System.now() - hoursAgo.hours).toString()
    }
}