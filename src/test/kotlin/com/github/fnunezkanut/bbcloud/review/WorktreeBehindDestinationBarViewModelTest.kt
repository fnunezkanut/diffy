package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.bitbucket.ItemCount
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Tag("unit")
class WorktreeBehindDestinationBarViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach
    fun cancelScope() {
        scope.cancel()
    }

    private fun tips(
        sourceRemote: String = "remote-source",
        destinationRemote: String = "remote-destination",
        sourceLocal: String = "local-source"
    ): ObservedBranchTips {
        return ObservedBranchTips(
            headBranch = "feature/foo",
            headRevision = "head",
            sourceLocal = sourceLocal,
            destinationLocal = "local-destination",
            sourceRemote = sourceRemote,
            destinationRemote = destinationRemote
        )
    }

    /** The view model with fakes for every collaborator. The reload debounce is short so the tests run in real time. */
    private inner class Fixture(retryDelays: List<Duration> = emptyList()) {
        var banner = WorktreeTodoBannerUiState(
            isWorktree = true,
            repoSlug = "repo",
            pullRequestId = 9L,
            sourceBranch = "feature/foo",
            destinationBranch = "main"
        )
        var redBarVisible = false
        var rebasing = false
        var mergeInProgress = false
        var currentTips = tips()
        val repository = mockk<GitRepository>(relaxed = true)
        val refreshes = AtomicInteger()
        val reloads = AtomicInteger()
        val rebasedStates = mutableListOf<BehindDestinationBarUiState>()

        val viewModel: WorktreeBehindDestinationBarViewModel = WorktreeBehindDestinationBarViewModel.forTesting(
            project = mockk<Project>(relaxed = true),
            coroutineScope = scope,
            dependencies = WorktreeBehindDestinationBarViewModel.Dependencies(
                bannerState = { banner },
                refreshNotifications = { refreshes.incrementAndGet() },
                rebaseWithAi = { state -> rebasedStates.add(state) },
                isRedBarVisible = { redBarVisible },
                readTips = { _, _, _ -> currentTips },
                isRepositoryRebasing = { rebasing || mergeInProgress },
                isRebasing = { rebasing },
                currentRepository = { repository },
                requestReload = { reloads.incrementAndGet() },
                reloadDebounce = DEBOUNCE,
                retryDelays = retryDelays
            )
        )

        fun showBehind(commits: Int = 2, pullRequestId: Long = 9L) {
            viewModel.show(
                pullRequestId = pullRequestId,
                state = PullRequestState.Open,
                commitsBehind = ItemCount(value = commits, isTruncated = false),
                sourceBranch = "feature/foo",
                destinationBranch = "main"
            )
        }

        fun changeBanner(next: WorktreeTodoBannerUiState) {
            val previous = banner
            banner = next
            viewModel.onBannerChanged(previous = previous, current = next)
        }

        fun observeTips(next: ObservedBranchTips) {
            currentTips = next
            viewModel.onRepositoryChanged(repository)
        }
    }

    private fun awaitReloads(fixture: Fixture, expected: Int) {
        val deadline = System.nanoTime() + AWAIT_NANOS
        while (fixture.reloads.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
    }

    private fun letDebounceExpire() {
        Thread.sleep(SETTLE_MILLIS)
    }

    @Nested
    inner class Visibility {
        @Test
        fun `show - should show the bar for an open pull request that is behind`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN
            fixture.showBehind()

            // THEN
            assertSoftly {
                fixture.viewModel.state.value.shouldNotBeNull().pullRequestId shouldBe 9L
                fixture.viewModel.visible.value shouldBe true
                fixture.viewModel.isVisible() shouldBe true
                fixture.refreshes.get() shouldBe 1
            }
        }

        @Test
        fun `show - should keep the bar hidden when the banner is not a proven open work tree`() {
            // GIVEN
            val fixture = Fixture()
            fixture.banner = fixture.banner.copy(isWorktree = false)

            // WHEN
            fixture.showBehind()

            // THEN
            assertSoftly {
                fixture.viewModel.visible.value shouldBe false
                fixture.viewModel.isVisible() shouldBe false
                fixture.refreshes.get() shouldBe 0
            }
        }

        @Test
        fun `show - should keep the bar hidden when the banner shows another pull request`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN
            fixture.showBehind(pullRequestId = 10L)

            // THEN
            assertSoftly {
                fixture.viewModel.visible.value shouldBe false
                fixture.viewModel.isVisible() shouldBe false
                fixture.refreshes.get() shouldBe 0
            }
        }

        @Test
        fun `show - should not refresh the notifications again for the same values`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.showBehind()

            // THEN
            fixture.refreshes.get() shouldBe 1
        }

        @Test
        fun `show - should hide the bar when the pull request is no longer behind`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.showBehind(commits = 0)

            // THEN
            assertSoftly {
                fixture.viewModel.state.value.shouldBeNull()
                fixture.viewModel.visible.value shouldBe false
                fixture.refreshes.get() shouldBe 2
            }
        }

        @Test
        fun `clear - should keep the state of another pull request`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.viewModel.clear(pullRequestId = 10L)

            // THEN
            fixture.viewModel.state.value.shouldNotBeNull()
        }

        @Test
        fun `clear - should clear the state of the same pull request`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.viewModel.clear(pullRequestId = 9L)

            // THEN
            assertSoftly {
                fixture.viewModel.state.value.shouldBeNull()
                fixture.viewModel.visible.value shouldBe false
            }
        }

        @Test
        fun `onBannerChanged - should hide the bar when the pull request is no longer proven open`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.changeBanner(fixture.banner.copy(isWorktree = false))

            // THEN
            assertSoftly {
                fixture.viewModel.visible.value shouldBe false
                fixture.refreshes.get() shouldBe 2
            }
        }
    }

    @Nested
    inner class RebaseWithAi {
        @Test
        fun `rebaseWithAi - should pass the current state to the dependency`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.viewModel.rebaseWithAi()

            // THEN
            fixture.rebasedStates shouldBe listOf(fixture.viewModel.state.value.shouldNotBeNull())
        }

        @Test
        fun `rebaseWithAi - should do nothing without a state`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN
            fixture.viewModel.rebaseWithAi()

            // THEN
            fixture.rebasedStates shouldBe emptyList()
        }

        @Test
        fun `rebaseWithAi - should do nothing while the red bar is visible`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.redBarVisible = true

            // WHEN
            fixture.viewModel.rebaseWithAi()

            // THEN
            fixture.rebasedStates shouldBe emptyList()
        }
    }

    @Nested
    inner class BranchTips {
        @Test
        fun `onRepositoryChanged - should only record the tips on the first call`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()

            // WHEN
            fixture.observeTips(tips())
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 0
        }

        @Test
        fun `onRepositoryChanged - should reload once after the debounce when the remote source moved`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())

            // WHEN
            fixture.observeTips(tips(sourceRemote = "pushed"))
            awaitReloads(fixture = fixture, expected = 1)
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 1
        }

        @Test
        fun `onRepositoryChanged - should reload once for two changes within the debounce window`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())

            // WHEN
            fixture.observeTips(tips(sourceRemote = "first"))
            fixture.observeTips(tips(sourceRemote = "second"))
            awaitReloads(fixture = fixture, expected = 1)
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 1
        }

        @Test
        fun `onRepositoryChanged - should reload when only the remote destination moved`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())

            // WHEN
            fixture.observeTips(tips(destinationRemote = "moved"))
            awaitReloads(fixture = fixture, expected = 1)

            // THEN
            fixture.reloads.get() shouldBe 1
        }

        @Test
        fun `onRepositoryChanged - should not reload while a rebase is in progress`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())
            fixture.rebasing = true

            // WHEN
            fixture.observeTips(tips(sourceRemote = "pushed"))
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 0
        }

        @Test
        fun `onRepositoryChanged - should not reload when no state is stored`() {
            // GIVEN
            val fixture = Fixture()
            fixture.observeTips(tips())

            // WHEN
            fixture.observeTips(tips(sourceRemote = "pushed"))
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 0
        }

        @Test
        fun `onRepositoryChanged - should reload once after the rebase ended when the tips moved during it`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())
            fixture.rebasing = true
            fixture.observeTips(tips(sourceRemote = "pushed"))
            letDebounceExpire()

            // WHEN the rebase ends and the repository reports a change with the same tips
            fixture.rebasing = false
            fixture.observeTips(tips(sourceRemote = "pushed"))
            awaitReloads(fixture = fixture, expected = 1)
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 1
        }

        @Test
        fun `onRepositoryChanged - should reload once after another git operation ended when the tips moved during it`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())
            fixture.mergeInProgress = true
            fixture.observeTips(tips(sourceRemote = "pushed"))
            letDebounceExpire()

            // WHEN
            fixture.mergeInProgress = false
            fixture.observeTips(tips(sourceRemote = "pushed"))
            awaitReloads(fixture = fixture, expected = 1)
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 1
        }

        @Test
        fun `onRepositoryChanged - should not reload again for a change without moved tips after the deferred reload ran`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())
            fixture.rebasing = true
            fixture.observeTips(tips(sourceRemote = "pushed"))
            fixture.rebasing = false
            fixture.observeTips(tips(sourceRemote = "pushed"))
            awaitReloads(fixture = fixture, expected = 1)
            letDebounceExpire()

            // WHEN
            fixture.observeTips(tips(sourceRemote = "pushed"))
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 1
        }

        @Test
        fun `onRepositoryChanged - should not reload after the rebase ended when no state is stored`() {
            // GIVEN
            val fixture = Fixture()
            fixture.observeTips(tips())
            fixture.rebasing = true
            fixture.observeTips(tips(sourceRemote = "pushed"))

            // WHEN
            fixture.rebasing = false
            fixture.observeTips(tips(sourceRemote = "pushed"))
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 0
        }

        @Test
        fun `onRepositoryChanged - should not reload when only the local tips changed`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())

            // WHEN
            fixture.observeTips(tips(sourceLocal = "rebased locally"))
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 0
        }

        @Test
        fun `onBannerChanged - should record the tips again after the branches changed`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.observeTips(tips())
            fixture.changeBanner(fixture.banner.copy(sourceBranch = "feature/bar"))

            // WHEN the first observation after the change only records the tips
            fixture.observeTips(tips(sourceRemote = "other branch tip"))
            letDebounceExpire()

            // THEN
            fixture.reloads.get() shouldBe 0
        }
    }

    @Nested
    inner class RebaseState {
        @Test
        fun `onRepositoryChanged - should report a rebase while the repository rebases and stop after it ended`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN the repository starts rebasing
            fixture.rebasing = true
            fixture.viewModel.onRepositoryChanged(fixture.repository)
            val whileRebasing = fixture.viewModel.isRebaseInProgress.value
            fixture.rebasing = false
            fixture.viewModel.onRepositoryChanged(fixture.repository)

            // THEN
            assertSoftly {
                whileRebasing shouldBe true
                fixture.viewModel.isRebaseInProgress.value shouldBe false
            }
        }

        @Test
        fun `onRepositoryChanged - should report a rebase when the banner has no branches`() {
            // GIVEN
            val fixture = Fixture()
            fixture.banner = fixture.banner.copy(sourceBranch = "", destinationBranch = "")

            // WHEN
            fixture.rebasing = true
            fixture.viewModel.onRepositoryChanged(fixture.repository)

            // THEN
            fixture.viewModel.isRebaseInProgress.value shouldBe true
        }

        @Test
        fun `onRepositoryChanged - should not report a rebase for another git operation`() {
            // GIVEN
            val fixture = Fixture()
            fixture.mergeInProgress = true

            // WHEN
            fixture.viewModel.onRepositoryChanged(fixture.repository)

            // THEN
            fixture.viewModel.isRebaseInProgress.value shouldBe false
        }

        @Test
        fun `onRepositoryChanged - should ignore a change of another repository`() {
            // GIVEN
            val fixture = Fixture()
            val otherRepository = mockk<GitRepository>(relaxed = true)
            fixture.rebasing = true

            // WHEN
            fixture.viewModel.onRepositoryChanged(otherRepository)

            // THEN
            fixture.viewModel.isRebaseInProgress.value shouldBe false
        }

        @Test
        fun `seedRebaseState - should show a rebase that was already running`() {
            // GIVEN
            val fixture = Fixture()
            fixture.rebasing = true

            // WHEN
            fixture.viewModel.seedRebaseState()

            // THEN
            fixture.viewModel.isRebaseInProgress.value shouldBe true
        }

        @Test
        fun `rebaseWithAi - should do nothing while a rebase is in progress and work again afterwards`() {
            // GIVEN
            val fixture = Fixture()
            fixture.showBehind()
            fixture.rebasing = true
            fixture.viewModel.onRepositoryChanged(fixture.repository)

            // WHEN
            fixture.viewModel.rebaseWithAi()
            val duringRebase = fixture.rebasedStates.size
            fixture.rebasing = false
            fixture.viewModel.onRepositoryChanged(fixture.repository)
            fixture.viewModel.rebaseWithAi()

            // THEN
            assertSoftly {
                duringRebase shouldBe 0
                fixture.rebasedStates.size shouldBe 1
            }
        }
    }

    @Nested
    inner class Retries {
        @Test
        fun `onRepositoryChanged - should reload again while the bar is still shown`() {
            // GIVEN
            val fixture = Fixture(retryDelays = listOf(50.milliseconds, 50.milliseconds))
            fixture.showBehind()
            fixture.observeTips(tips())

            // WHEN
            fixture.observeTips(tips(sourceRemote = "pushed"))
            awaitReloads(fixture = fixture, expected = 3)
            letDebounceExpire()

            // THEN the first reload and the two retries
            fixture.reloads.get() shouldBe 3
        }

        @Test
        fun `onRepositoryChanged - should stop reloading when the bar hides`() {
            // GIVEN
            val fixture = Fixture(retryDelays = listOf(300.milliseconds, 300.milliseconds))
            fixture.showBehind()
            fixture.observeTips(tips())
            fixture.observeTips(tips(sourceRemote = "pushed"))
            awaitReloads(fixture = fixture, expected = 1)

            // WHEN the reload found that the pull request is up to date
            fixture.showBehind(commits = 0)
            Thread.sleep(RETRY_SETTLE_MILLIS)

            // THEN
            fixture.reloads.get() shouldBe 1
        }
    }

    @Nested
    inner class RebaseLinkVisibility {
        @Test
        fun `rebaseLinkModeOf - should map every combination of the inputs`() {
            // GIVEN every combination of the three inputs: Air available, red bar visible, rebase in progress
            val combinations = listOf(
                Triple(true, false, false),
                Triple(true, false, true),
                Triple(true, true, false),
                Triple(true, true, true),
                Triple(false, false, false),
                Triple(false, false, true),
                Triple(false, true, false),
                Triple(false, true, true)
            )

            // WHEN
            val results = combinations.map { (air, red, rebase) ->
                rebaseLinkModeOf(isAirAvailable = air, isRedBarVisible = red, isRebaseInProgress = rebase)
            }

            // THEN
            results shouldBe listOf(
                RebaseLinkMode.Link,
                RebaseLinkMode.InProgress,
                RebaseLinkMode.Hidden,
                RebaseLinkMode.Hidden,
                RebaseLinkMode.Hidden,
                RebaseLinkMode.Hidden,
                RebaseLinkMode.Hidden,
                RebaseLinkMode.Hidden
            )
        }
    }

    private companion object {
        val DEBOUNCE = 100.milliseconds
        const val SETTLE_MILLIS = 400L
        const val POLL_MILLIS = 10L
        const val RETRY_SETTLE_MILLIS = 800L
        const val AWAIT_NANOS = 3_000_000_000L
    }
}