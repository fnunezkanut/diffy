package com.github.fnunezkanut.bbcloud.project

import com.github.fnunezkanut.bbcloud.git.OpenProjectLocator
import com.github.fnunezkanut.bbcloud.git.WorktreeRemovalResult
import com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.ProjectCloser
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

@Tag("unit")
class AllPullRequestWorktreesRemoverTest {
    private val root: Path = Path.of("/tmp/wt")
    private val source: Path = Path.of("/tmp/clone")
    private var cancelRunningJob: () -> Unit = {}

    private fun folder(slug: String, number: Long): PullRequestWorktreeFolder {
        return PullRequestWorktreeFolder(worktreePath = root.resolve("$slug--pr-$number"), repoSlug = slug, prNumber = number)
    }

    private class Recorder {
        val calls = mutableListOf<String>()
        val marked = mutableSetOf<Path>()
    }

    private fun remover(
        recorder: Recorder = Recorder(),
        openProject: (Path) -> Project? = { null },
        closeSucceeds: Boolean = true,
        sourceRoot: Path? = source,
        isGit: Boolean = true,
        beingOpened: Boolean = false,
        removeResult: (Path) -> WorktreeRemovalResult = { WorktreeRemovalResult.Success },
        orphanResult: WorktreeRemovalResult = WorktreeRemovalResult.Success
    ): AllPullRequestWorktreesRemover {
        return AllPullRequestWorktreesRemover(
            openProjectLocator = OpenProjectLocator { path -> openProject(path) },
            projectCloser = ProjectCloser { _ ->
                recorder.calls += "close"
                closeSucceeds
            },
            removeWorktree = { _, path, _ ->
                recorder.calls += "remove ${path.fileName}"
                removeResult(path)
            },
            deleteOrphan = { path, _ ->
                recorder.calls += "orphan ${path.fileName}"
                orphanResult
            },
            resolveSourceRoot = { _, _ -> sourceRoot },
            isGitWorktree = { _ -> isGit },
            isBeingOpened = { _ -> beingOpened },
            markRemoving = { path -> recorder.marked.add(path) },
            unmarkRemoving = { path -> recorder.marked.remove(path) }
        )
    }

    @Test
    fun `listPullRequestWorktreeFolders - should keep only slug--pr-N folders sorted by slug then PR number`() {
        // GIVEN
        val children = listOf("notes", "repo--pr-abc", "repo--pr-0", "--pr-5", "b--pr-2", "a--pr-10", "a--pr-9").map { root.resolve(it) }

        // WHEN
        val folders = listPullRequestWorktreeFolders(workTreeDirectory = root, listChildren = { children })

        // THEN
        folders.map { it.worktreePath.fileName.toString() } shouldBe listOf("a--pr-9", "a--pr-10", "b--pr-2")
    }

    @Test
    fun `orderCurrentProjectLast - should split the hosting work tree out`() {
        // GIVEN
        val a = folder("a", 1)
        val b = folder("b", 2)

        // WHEN
        val (others, current) = orderCurrentProjectLast(folders = listOf(a, b), isCurrentProject = { it == a.worktreePath })

        // THEN
        assertSoftly {
            others shouldBe listOf(b)
            current shouldBe a
        }
    }

    @Test
    fun `removeAll - should remove every folder one by one in order`() {
        // GIVEN
        val recorder = Recorder()
        val remover = remover(recorder = recorder)

        // WHEN
        val summary = runBlocking { remover.removeAll(folders = listOf(folder("a", 1), folder("b", 2)), workTreeDirectory = root) }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(removed = 2)
            recorder.calls shouldBe listOf("remove a--pr-1", "remove b--pr-2")
        }
    }

    @Test
    fun `removeOne - should close the window only when one is open`() {
        // GIVEN
        val withWindow = Recorder()
        val withoutWindow = Recorder()
        val project = mockk<Project>(relaxed = true)

        // WHEN
        runBlocking {
            remover(recorder = withWindow, openProject = { project }).removeOne(folder("a", 1), root)
            remover(recorder = withoutWindow).removeOne(folder("a", 1), root)
        }

        // THEN
        assertSoftly {
            withWindow.calls shouldBe listOf("close", "remove a--pr-1")
            withoutWindow.calls shouldBe listOf("remove a--pr-1")
        }
    }

    @Test
    fun `removeOne - should keep the folder when the window refuses to close`() {
        // GIVEN
        val recorder = Recorder()
        val remover = remover(recorder = recorder, openProject = { mockk<Project>(relaxed = true) }, closeSucceeds = false)

        // WHEN
        val summary = runBlocking { remover.removeOne(folder("a", 1), root) }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(closeFailed = 1)
            recorder.calls shouldBe listOf("close")
        }
    }

    @Test
    fun `removeOne - should skip a work tree that is being opened or already being removed`() {
        // GIVEN
        val opening = remover(beingOpened = true)
        val recorder = Recorder()
        val target = folder("a", 1)
        recorder.marked.add(target.worktreePath)
        val alreadyRemoving = remover(recorder = recorder)

        // WHEN
        val first = runBlocking { opening.removeOne(target, root) }
        val second = runBlocking { alreadyRemoving.removeOne(target, root) }

        // THEN
        assertSoftly {
            first shouldBe AllWorktreesRemovalSummary(skippedInUse = 1)
            second shouldBe AllWorktreesRemovalSummary(skippedInUse = 1)
            recorder.calls shouldBe emptyList()
        }
    }

    @Test
    fun `removeOne - should delete the folder without git when the source is unknown`() {
        // GIVEN
        val recorder = Recorder()
        val remover = remover(recorder = recorder, sourceRoot = null)

        // WHEN
        val summary = runBlocking { remover.removeOne(folder("a", 1), root) }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(removed = 1, removedWithoutGit = 1)
            recorder.calls shouldBe listOf("orphan a--pr-1")
        }
    }

    @Test
    fun `removeOne - should count a failed orphan delete as failed`() {
        // GIVEN
        val remover = remover(sourceRoot = null, orphanResult = WorktreeRemovalResult.Failed(output = "denied"))

        // WHEN
        val summary = runBlocking { remover.removeOne(folder("a", 1), root) }

        // THEN
        summary shouldBe AllWorktreesRemovalSummary(failed = 1)
    }

    @Test
    fun `removeOne - should delete a symbolic link or full clone without git even when a source resolves`() {
        // GIVEN
        val recorder = Recorder()
        val remover = remover(recorder = recorder, isGit = false)

        // WHEN
        val summary = runBlocking { remover.removeOne(folder("a", 1), root) }

        // THEN
        assertSoftly {
            summary.removedWithoutGit shouldBe 1
            recorder.calls shouldBe listOf("orphan a--pr-1")
        }
    }

    @Test
    fun `removeAll - should continue after a failure`() {
        // GIVEN
        val remover = remover(removeResult = { path ->
            if (path.fileName.toString() ==
                "a--pr-1"
            ) {
                WorktreeRemovalResult.Failed(output = "x")
            } else {
                WorktreeRemovalResult.Success
            }
        })

        // WHEN
        val summary = runBlocking { remover.removeAll(folders = listOf(folder("a", 1), folder("b", 2)), workTreeDirectory = root) }

        // THEN
        summary shouldBe AllWorktreesRemovalSummary(removed = 1, failed = 1)
    }

    @Test
    fun `removeOne - should always unmark the path, also on exception`() {
        // GIVEN
        val recorder = Recorder()
        val remover = remover(recorder = recorder, removeResult = { throw IllegalStateException("boom") })

        // WHEN
        val summary = runBlocking { remover.removeOne(folder("a", 1), root) }

        // THEN
        assertSoftly {
            summary shouldBe AllWorktreesRemovalSummary(failed = 1)
            recorder.marked shouldBe emptySet()
        }
    }

    @Test
    fun `removeAll - should carry nextIndex and the partial summary in BulkRemovalCancelled`() {
        // GIVEN
        val remover = remover()
        val folders = listOf(folder("a", 1), folder("b", 2), folder("c", 3))

        // WHEN
        val cancelled = runBlocking {
            var result: Throwable? = null
            val job = launch {
                try {
                    remover.removeAll(
                        folders = folders,
                        workTreeDirectory = root,
                        onStep = { index, _, action ->
                            val itemSummary = action()
                            if (index == 0) {
                                cancelRunningJob.invoke()
                            }
                            itemSummary
                        }
                    )
                } catch (exception: CancellationException) {
                    result = exception
                }
            }
            cancelRunningJob = { job.cancel() }
            job.join()
            result
        }

        // THEN
        cancelled.shouldBeInstanceOf<BulkRemovalCancelled>()
        assertSoftly {
            cancelled.partial shouldBe AllWorktreesRemovalSummary(removed = 1)
            cancelled.nextIndex shouldBe 1
        }
    }

    @Test
    fun `summaryNotification - should pick done or partial`() {
        // GIVEN / WHEN
        val done = summaryNotification(AllWorktreesRemovalSummary(removed = 2))
        val partial = summaryNotification(AllWorktreesRemovalSummary(removed = 1, failed = 1))

        // THEN
        assertSoftly {
            done shouldBe ("recentProjects.removeAllWorktrees.done" to NotificationType.INFORMATION)
            partial shouldBe ("recentProjects.removeAllWorktrees.partial" to NotificationType.WARNING)
        }
    }
}