package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.git.BranchSyncStatus
import com.github.fnunezkanut.bbcloud.git.PrBranchRole
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncOutcome
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncResult
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSkipReason
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncSnapshot
import com.github.fnunezkanut.bbcloud.git.PrBranchSyncStepResult
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class WorktreeLocalBehindRemoteBarViewModelTest {
    private val prWorktree: Path = Path.of("/tmp/.worktrees/repo--pr-9")

    private fun status(behind: Int): BranchSyncStatus {
        if (behind == 0) {
            return BranchSyncStatus.UpToDate(commitsAhead = 0)
        }
        return BranchSyncStatus.Behind(
            commitsBehind = behind,
            commitsAhead = 0,
            localRevision = "old",
            remoteRevision = "new",
            checkedOutAt = null,
            checkedOutWorktreeDirty = null
        )
    }

    private fun snapshot(sourceBehind: Int, source: String = "feature/foo"): PrBranchSyncSnapshot {
        return PrBranchSyncSnapshot(
            remoteName = "origin",
            sourceBranch = source,
            destinationBranch = "main",
            prWorktreePath = prWorktree,
            source = status(sourceBehind),
            destination = status(0)
        )
    }

    private fun tips(sourceRemote: String): ObservedBranchTips {
        return ObservedBranchTips(
            headBranch = "feature/foo",
            headRevision = "head",
            sourceLocal = "local",
            destinationLocal = "local",
            sourceRemote = sourceRemote,
            destinationRemote = "remote"
        )
    }

    /** The view model with fakes for every collaborator, running everything inline. */
    private inner class Fixture {
        var banner = WorktreeTodoBannerUiState(
            isWorktree = true,
            repoSlug = "repo",
            pullRequestId = 9L,
            sourceBranch = "feature/foo",
            destinationBranch = "main"
        )
        var lastSnapshot: PrBranchSyncSnapshot? = null
        var evaluateBlock: suspend (Boolean) -> PrBranchSyncSnapshot? = { null }
        var updateBlock: suspend () -> PrBranchSyncResult? = { null }
        var currentTips = tips(sourceRemote = "remote")
        val evaluations = mutableListOf<Boolean>()
        var updateCalls = 0
        var reloads = 0
        var visibilityChanges = 0
        val failures = mutableListOf<Pair<String, String?>>()

        val viewModel: WorktreeLocalBehindRemoteBarViewModel = WorktreeLocalBehindRemoteBarViewModel.forTesting(
            project = mockk<Project>(relaxed = true),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dependencies = WorktreeLocalBehindRemoteBarViewModel.Dependencies(
                lastSnapshot = { lastSnapshot },
                evaluate = { _, _, fetch ->
                    evaluations.add(fetch)
                    evaluateBlock(fetch)
                },
                updateOnUserRequest = { _, _ ->
                    updateCalls++
                    updateBlock()
                },
                bannerState = { banner },
                requestReload = { reloads++ },
                notifyFailure = { title, message -> failures.add(title to message) },
                onVisibilityChanged = { visibilityChanges++ },
                runOnEdt = { action -> action() },
                readTips = { _, _, _ -> currentTips }
            )
        )

        fun changeBanner(next: WorktreeTodoBannerUiState) {
            val previous = banner
            banner = next
            viewModel.onBannerChanged(previous = previous, current = next)
        }
    }

    @Nested
    inner class Snapshots {
        @Test
        fun `onSnapshot - should show the state of a matching snapshot`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))

            // THEN
            assertSoftly {
                fixture.viewModel.state.value.shouldNotBeNull().roles shouldBe listOf(PrBranchRole.Source)
                fixture.viewModel.isVisible() shouldBe true
            }
        }

        @Test
        fun `onSnapshot - should ignore a snapshot of other branches`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2, source = "feature/other"))

            // THEN
            fixture.viewModel.state.value.shouldBeNull()
        }

        @Test
        fun `onSnapshot - should ignore a snapshot until the banner knows the branches and apply it then`() {
            // GIVEN the banner does not know the branches yet and the sync service has a snapshot
            val fixture = Fixture()
            val unknown = fixture.banner.copy(sourceBranch = null, destinationBranch = null)
            fixture.banner = unknown
            fixture.lastSnapshot = snapshot(sourceBehind = 2)

            // WHEN the snapshot is published
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))

            // THEN it is ignored
            fixture.viewModel.state.value.shouldBeNull()

            // WHEN the branches become known
            fixture.changeBanner(unknown.copy(sourceBranch = "feature/foo", destinationBranch = "main"))

            // THEN the last snapshot is applied
            fixture.viewModel.state.value.shouldNotBeNull()
        }

        @Test
        fun `onSnapshot - should clear the state for an up to date snapshot`() {
            // GIVEN
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))

            // WHEN
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 0))

            // THEN
            fixture.viewModel.state.value.shouldBeNull()
        }
    }

    @Nested
    inner class Visibility {
        @Test
        fun `onVisibilityChanged - should be called once when the bar appears and once when it disappears`() {
            // GIVEN
            val fixture = Fixture()

            // WHEN the bar appears, then the same state arrives again, then the bar disappears
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            val afterAppearing = fixture.visibilityChanges
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            val afterRepeat = fixture.visibilityChanges
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 0))

            // THEN
            assertSoftly {
                afterAppearing shouldBe 1
                afterRepeat shouldBe 1
                fixture.visibilityChanges shouldBe 2
            }
        }

        @Test
        fun `onVisibilityChanged - should be called when the pull request stops and starts being open`() {
            // GIVEN a visible bar
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            val open = fixture.banner

            // WHEN the banner reports that the pull request is not open, then open again
            fixture.changeBanner(open.copy(isWorktree = false))
            val afterClosing = fixture.visibilityChanges
            val hiddenWhileClosed = fixture.viewModel.visible.value
            fixture.changeBanner(open)

            // THEN
            assertSoftly {
                afterClosing shouldBe 2
                hiddenWhileClosed shouldBe false
                fixture.visibilityChanges shouldBe 3
                fixture.viewModel.visible.value shouldBe true
            }
        }
    }

    @Nested
    inner class Evaluation {
        @Test
        fun `requestEvaluation - should do nothing without branch names`() {
            // GIVEN
            val fixture = Fixture()
            fixture.banner = fixture.banner.copy(sourceBranch = null, destinationBranch = null)

            // WHEN
            fixture.viewModel.requestEvaluation(fetch = true)

            // THEN
            fixture.evaluations shouldBe emptyList()
        }

        @Test
        fun `requestEvaluation - should show the result of an evaluation`() {
            // GIVEN
            val fixture = Fixture()
            fixture.evaluateBlock = { snapshot(sourceBehind = 3) }

            // WHEN
            fixture.viewModel.requestEvaluation(fetch = true)

            // THEN
            assertSoftly {
                fixture.evaluations shouldBe listOf(true)
                fixture.viewModel.state.value.shouldNotBeNull()
            }
        }

        @Test
        fun `requestEvaluation - should cancel the previous evaluation`() {
            // GIVEN an evaluation that never finishes on its own
            val fixture = Fixture()
            val cancelled = mutableListOf<Boolean>()
            fixture.evaluateBlock = { fetch ->
                try {
                    awaitCancellation()
                } finally {
                    cancelled.add(fetch)
                }
            }

            // WHEN a second evaluation is requested
            fixture.viewModel.requestEvaluation(fetch = true)
            fixture.viewModel.requestEvaluation(fetch = false)

            // THEN only the first one was cancelled
            assertSoftly {
                fixture.evaluations shouldBe listOf(true, false)
                cancelled shouldBe listOf(true)
            }
        }

        @Test
        fun `requestEvaluation - should do nothing while a branch update runs`() {
            // GIVEN a running update
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            fixture.updateBlock = { CompletableDeferred<PrBranchSyncResult?>().await() }
            fixture.viewModel.requestUpdate()

            // WHEN
            fixture.viewModel.requestEvaluation(fetch = true)

            // THEN
            fixture.evaluations shouldBe emptyList()
        }

        @Test
        fun `onRepositoryChanged - should evaluate once for equal branch tips and again when a tip moves`() {
            // GIVEN
            val fixture = Fixture()
            val repository = mockk<GitRepository>(relaxed = true)

            // WHEN the same tips are observed twice, then a tip moves
            fixture.viewModel.onRepositoryChanged(repository)
            fixture.viewModel.onRepositoryChanged(repository)
            val afterSameTips = fixture.evaluations.size
            fixture.currentTips = tips(sourceRemote = "moved")
            fixture.viewModel.onRepositoryChanged(repository)

            // THEN the evaluations never fetch again
            assertSoftly {
                afterSameTips shouldBe 1
                fixture.evaluations shouldBe listOf(false, false)
            }
        }
    }

    @Nested
    inner class Update {
        private fun movedResult(): PrBranchSyncResult {
            return PrBranchSyncResult(
                outcome = PrBranchSyncOutcome(results = mapOf(PrBranchRole.Source to PrBranchSyncStepResult.Updated(path = null))),
                after = snapshot(sourceBehind = 0)
            )
        }

        private fun skippedResult(): PrBranchSyncResult {
            val skipped = PrBranchSyncStepResult.Skipped(reason = PrBranchSyncSkipReason.LocalCommitsNotOnRemote)
            return PrBranchSyncResult(
                outcome = PrBranchSyncOutcome(results = mapOf(PrBranchRole.Source to skipped)),
                after = snapshot(sourceBehind = 2)
            )
        }

        @Test
        fun `requestUpdate - should disable the link, ignore a second click and ignore new snapshots while it runs`() {
            // GIVEN a visible bar and an update that is still running
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            fixture.updateBlock = { CompletableDeferred<PrBranchSyncResult?>().await() }

            // WHEN
            fixture.viewModel.requestUpdate()
            fixture.viewModel.requestUpdate()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 0))

            // THEN
            assertSoftly {
                fixture.updateCalls shouldBe 1
                fixture.viewModel.state.value.shouldNotBeNull().updateInProgress shouldBe true
            }
        }

        @Test
        fun `requestUpdate - should reload the page once after a branch moved`() {
            // GIVEN
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            val result = CompletableDeferred<PrBranchSyncResult?>()
            fixture.updateBlock = { result.await() }
            fixture.viewModel.requestUpdate()

            // WHEN
            result.complete(movedResult())

            // THEN
            assertSoftly {
                fixture.reloads shouldBe 1
                fixture.failures shouldBe emptyList()
                fixture.viewModel.state.value.shouldBeNull()
            }
        }

        @Test
        fun `requestUpdate - should notify and not reload when a branch was skipped and no ref moved`() {
            // GIVEN
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            val result = CompletableDeferred<PrBranchSyncResult?>()
            fixture.updateBlock = { result.await() }
            fixture.viewModel.requestUpdate()

            // WHEN
            result.complete(skippedResult())

            // THEN the bar stays and its link works again
            assertSoftly {
                fixture.reloads shouldBe 0
                fixture.failures.map { failure -> failure.first } shouldBe listOf("Could not update local branch \"feature/foo\"")
                fixture.viewModel.state.value.shouldNotBeNull().updateInProgress shouldBe false
            }
        }

        @Test
        fun `requestUpdate - should notify once for a null result`() {
            // GIVEN
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            val result = CompletableDeferred<PrBranchSyncResult?>()
            fixture.updateBlock = { result.await() }
            fixture.viewModel.requestUpdate()

            // WHEN
            result.complete(null)

            // THEN
            assertSoftly {
                fixture.failures shouldBe listOf("Could not update local branch \"feature/foo\"" to null)
                fixture.reloads shouldBe 0
                fixture.viewModel.state.value.shouldNotBeNull().updateInProgress shouldBe false
            }
        }

        @Test
        fun `requestUpdate - should do nothing while a page reload runs`() {
            // GIVEN the banner reports a reload in flight
            val fixture = Fixture()
            fixture.viewModel.onSnapshot(snapshot(sourceBehind = 2))
            fixture.banner = fixture.banner.copy(refreshEnabled = false)

            // WHEN
            fixture.viewModel.requestUpdate()

            // THEN
            assertSoftly {
                fixture.updateCalls shouldBe 0
                fixture.viewModel.state.value.shouldNotBeNull().updateInProgress shouldBe false
            }
        }
    }
}