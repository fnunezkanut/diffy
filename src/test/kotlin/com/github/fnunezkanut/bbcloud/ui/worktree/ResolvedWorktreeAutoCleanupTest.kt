package com.github.fnunezkanut.bbcloud.ui.worktree

import com.github.fnunezkanut.bbcloud.bitbucket.DiffyOpenPullRequestService
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestListResult
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyWorktreeService
import com.github.fnunezkanut.bbcloud.project.ResolvedWorktreeCandidate
import com.github.fnunezkanut.bbcloud.project.ResolvedWorktreeCleanupSummary
import com.github.fnunezkanut.bbcloud.project.WatchedProjectWorktree
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class ResolvedWorktreeAutoCleanupTest {
    private val configuration = mockk<DiffyConfigurationService>()
    private val openService = mockk<DiffyOpenPullRequestService>()
    private var worktrees: List<WatchedProjectWorktree> =
        listOf(WatchedProjectWorktree(repoSlug = "repo", prNumber = 5, worktreePath = Path.of("/wt/repo--pr-5")))
    private val removed = mutableListOf<List<ResolvedWorktreeCandidate>>()
    private var pruneAll = false
    private var gate: CompletableDeferred<Unit>? = null

    init {
        every { configuration.workTreeDirectoryOrNull() } returns "/wt"
        every { configuration.organizationUrlOrNull() } returns "https://bitbucket.org/acme/"
        coEvery { openService.listAllOpenPullRequestsForRepository(workspace = any(), repoSlug = any()) } returns PullRequestListResult.Failed()
    }

    private fun cleanup(): ResolvedWorktreeAutoCleanup {
        return ResolvedWorktreeAutoCleanup(
            configurationService = configuration,
            openPullRequestService = openService,
            worktreeService = mockk<DiffyWorktreeService>(),
            worktreeOpener = mockk<PullRequestWorktreeOpener>(),
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            isPruneAllInProgress = { pruneAll },
            ioDispatcher = Dispatchers.Unconfined,
            listWorktreesForRepo = { _, _ -> worktrees },
            registeredWorktrees = { emptyList() },
            resolveSourceCheckout = { _, _ -> Path.of("/src/repo") },
            removeCandidates = { _, _, candidates ->
                gate?.await()
                removed.add(candidates)
                ResolvedWorktreeCleanupSummary(removed = candidates.size, skippedInUse = 0, closeFailed = 0, failed = 0)
            }
        )
    }

    @Test
    fun `clean - an empty resolved set makes no request`() {
        // WHEN
        val job = cleanup().clean(workspace = "acme", repoSlug = "repo", resolvedPrNumbers = emptySet())

        // THEN
        job shouldBe null
        coVerify(exactly = 0) { openService.listAllOpenPullRequestsForRepository(workspace = any(), repoSlug = any()) }
    }

    @Test
    fun `clean - a repository without local work trees makes no Bitbucket request`() {
        // GIVEN
        worktrees = emptyList()

        // WHEN
        runBlocking { cleanup().clean(workspace = "acme", repoSlug = "repo", resolvedPrNumbers = setOf(5L))?.join() }

        // THEN
        coVerify(exactly = 0) { openService.listAllOpenPullRequestsForRepository(workspace = any(), repoSlug = any()) }
    }

    @Test
    fun `clean - is skipped while pruning all watched projects runs`() {
        // GIVEN
        pruneAll = true

        // WHEN
        val job = cleanup().clean(workspace = "acme", repoSlug = "repo", resolvedPrNumbers = setOf(5L))

        // THEN
        job shouldBe null
    }

    @Test
    fun `clean - removes the work tree of a resolved pull request, falling back to the resolved ids when the open listing fails`() {
        // WHEN
        runBlocking { cleanup().clean(workspace = "acme", repoSlug = "repo", resolvedPrNumbers = setOf(5L))?.join() }

        // THEN
        removed.map { candidates -> candidates.map { it.prNumber } } shouldBe listOf(listOf(5L))
    }

    @Test
    fun `clean - a second request for the same repository is skipped while the first runs, another repository is not`() {
        // GIVEN a removal that is still running
        gate = CompletableDeferred()
        val subject = cleanup()
        val first = subject.clean(workspace = "acme", repoSlug = "repo", resolvedPrNumbers = setOf(5L))

        // WHEN
        val duplicate = subject.clean(workspace = "acme", repoSlug = "repo", resolvedPrNumbers = setOf(5L))
        val other = subject.clean(workspace = "acme", repoSlug = "other", resolvedPrNumbers = setOf(5L))
        val runningWhileBusy = subject.isRunning()
        gate?.complete(Unit)
        runBlocking {
            first?.join()
            other?.join()
        }

        // THEN
        listOf(duplicate == null, other != null, runningWhileBusy, subject.isRunning()) shouldBe listOf(true, true, true, false)
    }
}