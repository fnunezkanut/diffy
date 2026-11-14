package com.github.fnunezkanut.bbcloud.ui.worktree

import com.github.fnunezkanut.bbcloud.git.OpenProjectLocator
import com.github.fnunezkanut.bbcloud.git.WorktreeRemovalResult
import com.github.fnunezkanut.bbcloud.project.AllPullRequestWorktreesRemover
import com.github.fnunezkanut.bbcloud.project.AllWorktreesRemovalSummary
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeFolder
import com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.ProjectCloser
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("unit")
class DiffyAllWorktreesRemovalServiceTest {
    private val root: Path = Path.of("/tmp/wt")

    private fun folder(number: Long): PullRequestWorktreeFolder {
        return PullRequestWorktreeFolder(worktreePath = root.resolve("repo--pr-$number"), repoSlug = "repo", prNumber = number)
    }

    private fun remover(calls: MutableList<String>): AllPullRequestWorktreesRemover {
        return AllPullRequestWorktreesRemover(
            openProjectLocator = OpenProjectLocator { null },
            projectCloser = ProjectCloser { _ -> true },
            removeWorktree = { _, path, _ ->
                calls += path.fileName.toString()
                WorktreeRemovalResult.Success
            },
            deleteOrphan = { _, _ -> WorktreeRemovalResult.Success },
            resolveSourceRoot = { _, _ -> Path.of("/tmp/clone") },
            isGitWorktree = { _ -> true },
            isBeingOpened = { _ -> false },
            markRemoving = { _ -> true },
            unmarkRemoving = { _ -> }
        )
    }

    /** A progress runner whose task is cancelled after its first item, like a status bar cancel or a closed window. */
    private fun cancelAfterFirstItem(): suspend (Int, suspend (BulkRemovalStep) -> AllWorktreesRemovalSummary) -> AllWorktreesRemovalSummary {
        return { _, block ->
            coroutineScope {
                lateinit var task: Deferred<AllWorktreesRemovalSummary>
                task = async {
                    block { index, _, action ->
                        val itemSummary = action()
                        if (index == 0) {
                            task.cancel()
                        }
                        itemSummary
                    }
                }
                task.await()
            }
        }
    }

    private val folders = listOf(folder(1), folder(2), folder(3))
    private val current = folders[0]

    @Test
    fun `runBulkRemoval - should remove the hosting work tree last`() {
        // GIVEN
        val calls = mutableListOf<String>()

        // WHEN
        val summary = runBlocking {
            runBulkRemoval(
                folders = folders,
                workTreeDirectory = root,
                remover = remover(calls),
                isCurrentProject = { it == current.worktreePath },
                isProjectDisposed = { false },
                runWithProgress = { _, block -> block { _, _, action -> action() } }
            )
        }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(removed = 3)
            calls shouldBe listOf("repo--pr-2", "repo--pr-3", "repo--pr-1")
        }
    }

    @Test
    fun `runBulkRemoval - should continue without progress when the hosting window was closed by hand`() {
        // GIVEN
        val calls = mutableListOf<String>()

        // WHEN
        val summary = runBlocking {
            runBulkRemoval(
                folders = folders,
                workTreeDirectory = root,
                remover = remover(calls),
                isCurrentProject = { it == current.worktreePath },
                isProjectDisposed = { true },
                runWithProgress = cancelAfterFirstItem()
            )
        }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(removed = 3)
            calls shouldBe listOf("repo--pr-2", "repo--pr-3", "repo--pr-1")
        }
    }

    @Test
    fun `runBulkRemoval - should stop and count not processed items on a user cancel, keeping the hosting window`() {
        // GIVEN
        val calls = mutableListOf<String>()

        // WHEN
        val summary = runBlocking {
            runBulkRemoval(
                folders = folders,
                workTreeDirectory = root,
                remover = remover(calls),
                isCurrentProject = { it == current.worktreePath },
                isProjectDisposed = { false },
                runWithProgress = cancelAfterFirstItem()
            )
        }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(removed = 1, notProcessed = 2)
            calls shouldBe listOf("repo--pr-2")
        }
    }

    @Test
    fun `runBulkRemoval - should always release the bulk removal guard`() {
        // GIVEN
        PullRequestWorktreeRemovalRegistry.resetForTests()
        PullRequestWorktreeRemovalRegistry.tryStartBulkRemoval()

        // WHEN
        try {
            PullRequestWorktreeRemovalRegistry.finishBulkRemoval()
        } finally {
            PullRequestWorktreeRemovalRegistry.resetForTests()
        }

        // THEN
        PullRequestWorktreeRemovalRegistry.isBulkRemovalInProgress() shouldBe false
    }
}