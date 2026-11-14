package com.github.fnunezkanut.bbcloud.project

import com.github.fnunezkanut.bbcloud.git.OpenProjectLocator
import com.github.fnunezkanut.bbcloud.git.WorktreeRemovalResult
import com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.ProjectCloser
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** One PR work tree folder found directly under the work tree root. */
data class PullRequestWorktreeFolder(val worktreePath: Path, val repoSlug: String, val prNumber: Long)

/** Totals of one "Remove all PR Work Trees" run. [removedWithoutGit] is included in [removed]. */
data class AllWorktreesRemovalSummary(
    val removed: Int = 0,
    val removedWithoutGit: Int = 0,
    val closeFailed: Int = 0,
    val failed: Int = 0,
    val skippedInUse: Int = 0,
    val notProcessed: Int = 0
) {
    val notRemoved: Int
        get() = closeFailed + failed + skippedInUse + notProcessed

    operator fun plus(other: AllWorktreesRemovalSummary): AllWorktreesRemovalSummary {
        return AllWorktreesRemovalSummary(
            removed = removed + other.removed,
            removedWithoutGit = removedWithoutGit + other.removedWithoutGit,
            closeFailed = closeFailed + other.closeFailed,
            failed = failed + other.failed,
            skippedInUse = skippedInUse + other.skippedInUse,
            notProcessed = notProcessed + other.notProcessed
        )
    }
}

/**
 * Thrown by [AllPullRequestWorktreesRemover.removeAll] when its coroutine was cancelled between two items. [partial]
 * holds the counts of the items done so far (without `notProcessed`); [nextIndex] is the index of the first item that
 * was not started.
 */
internal class BulkRemovalCancelled(val partial: AllWorktreesRemovalSummary, val nextIndex: Int) :
    CancellationException("Removing all PR work trees was cancelled")

/**
 * Lists the PR work tree folders (`<repoSlug>--pr-<n>`) directly under [workTreeDirectory], sorted by slug and then PR
 * number. Other folders are ignored and never deleted.
 */
fun listPullRequestWorktreeFolders(
    workTreeDirectory: Path,
    listChildren: (Path) -> List<Path> = PullRequestWorktreeListing::listChildDirectories
): List<PullRequestWorktreeFolder> {
    val children = runCatching { listChildren(workTreeDirectory) }.getOrDefault(emptyList())
    val folders = children.mapNotNull { child ->
        val folderName = child.fileName?.toString() ?: return@mapNotNull null
        val slug = PullRequestWorktreeLayout.repoSlugFromWorktreeFolderName(folderName = folderName)
        val prNumber = PullRequestWorktreeLayout.prNumberFromWorktreeFolderName(folderName = folderName)
        if (slug == null || prNumber == null) {
            null
        } else {
            PullRequestWorktreeFolder(
                worktreePath = child.toAbsolutePath().normalize(),
                repoSlug = slug,
                prNumber = prNumber
            )
        }
    }
    return folders.sortedWith(compareBy<PullRequestWorktreeFolder> { folder -> folder.repoSlug }.thenBy { folder -> folder.prNumber })
}

/** Splits [folders] into the other folders and, separately, the folder of the hosting window (or null). */
fun orderCurrentProjectLast(
    folders: List<PullRequestWorktreeFolder>,
    isCurrentProject: (Path) -> Boolean
): Pair<List<PullRequestWorktreeFolder>, PullRequestWorktreeFolder?> {
    val current = folders.firstOrNull { folder -> isCurrentProject(folder.worktreePath) }
    return folders.filter { folder -> folder != current } to current
}

/** The message key and notification type that report [summary]: `done` when nothing was left, otherwise `partial`. */
fun summaryNotification(summary: AllWorktreesRemovalSummary): Pair<String, NotificationType> {
    return if (summary.notRemoved == 0) {
        "recentProjects.removeAllWorktrees.done" to NotificationType.INFORMATION
    } else {
        "recentProjects.removeAllWorktrees.partial" to NotificationType.WARNING
    }
}

/**
 * Removes PR work tree folders one at a time. For each one, an IDE window open on it is closed first (only if one is
 * open). Then the work tree is removed through git from its source checkout, or, when the folder is not a git work tree
 * or the source checkout is unknown, deleted from disk. One failing folder never stops the others.
 */
internal class AllPullRequestWorktreesRemover(
    private val openProjectLocator: OpenProjectLocator,
    private val projectCloser: ProjectCloser,
    private val removeWorktree: suspend (sourceCheckoutRoot: Path, worktreePath: Path, workTreeDirectory: Path) -> WorktreeRemovalResult,
    private val deleteOrphan: suspend (worktreePath: Path, workTreeDirectory: Path) -> WorktreeRemovalResult,
    private val resolveSourceRoot: (worktreePath: Path, repoSlug: String) -> Path?,
    private val isGitWorktree: (Path) -> Boolean = ::isLinkedGitWorktree,
    private val isBeingOpened: (Path) -> Boolean,
    private val markRemoving: (Path) -> Boolean,
    private val unmarkRemoving: (Path) -> Unit
) {
    /** Removes one folder. The returned summary has exactly one counter set to 1. */
    suspend fun removeOne(folder: PullRequestWorktreeFolder, workTreeDirectory: Path): AllWorktreesRemovalSummary {
        val path = folder.worktreePath
        if (isBeingOpened(path) || !markRemoving(path)) {
            LOG.info("Skipping the work tree of PR #${folder.prNumber}: it is being opened or removed")
            return AllWorktreesRemovalSummary(skippedInUse = 1)
        }
        try {
            val openProject = openProjectLocator.findOpenProjectForPath(path = path)
            if (openProject != null && !projectCloser.close(project = openProject)) {
                LOG.info("Kept the work tree of PR #${folder.prNumber}: its IDE window could not be closed")
                return AllWorktreesRemovalSummary(closeFailed = 1)
            }
            return removeClosed(folder = folder, workTreeDirectory = workTreeDirectory)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            LOG.warn("Could not remove the work tree of PR #${folder.prNumber}", exception)
            return AllWorktreesRemovalSummary(failed = 1)
        } finally {
            unmarkRemoving(path)
        }
    }

    private suspend fun removeClosed(folder: PullRequestWorktreeFolder, workTreeDirectory: Path): AllWorktreesRemovalSummary {
        val path = folder.worktreePath
        val sourceRoot = if (isGitWorktree(path)) resolveSourceRoot(path, folder.repoSlug) else null
        if (sourceRoot != null) {
            return when (val result = removeWorktree(sourceRoot, path, workTreeDirectory)) {
                WorktreeRemovalResult.Success -> {
                    AllWorktreesRemovalSummary(removed = 1)
                }
                is WorktreeRemovalResult.Failed -> {
                    LOG.warn("Could not remove the work tree of PR #${folder.prNumber}: ${result.output}", result.cause)
                    AllWorktreesRemovalSummary(failed = 1)
                }
            }
        }
        LOG.info("Deleting the folder of PR #${folder.prNumber} without git: no source checkout or not a linked work tree")
        return when (val result = deleteOrphan(path, workTreeDirectory)) {
            WorktreeRemovalResult.Success -> {
                AllWorktreesRemovalSummary(removed = 1, removedWithoutGit = 1)
            }
            is WorktreeRemovalResult.Failed -> {
                LOG.warn("Could not delete the folder of PR #${folder.prNumber}: ${result.output}", result.cause)
                AllWorktreesRemovalSummary(failed = 1)
            }
        }
    }

    /**
     * Removes [folders] one by one. Each item runs through [onStep] (for progress reporting). An item that has started
     * always runs to completion. When the coroutine is cancelled between items, [BulkRemovalCancelled] carries the
     * partial result.
     */
    suspend fun removeAll(
        folders: List<PullRequestWorktreeFolder>,
        workTreeDirectory: Path,
        onStep: suspend (index: Int, folder: PullRequestWorktreeFolder, action: suspend () -> AllWorktreesRemovalSummary) -> AllWorktreesRemovalSummary =
            { _, _, action -> action() }
    ): AllWorktreesRemovalSummary {
        var total = AllWorktreesRemovalSummary()
        for ((index, folder) in folders.withIndex()) {
            if (!currentCoroutineContext().isActive) {
                throw BulkRemovalCancelled(partial = total, nextIndex = index)
            }
            val itemSummary = withContext(NonCancellable) {
                onStep(index, folder) { removeOne(folder = folder, workTreeDirectory = workTreeDirectory) }
            }
            total += itemSummary
        }
        LOG.debug("Removed all PR work trees: $total")
        return total
    }

    private companion object {
        val LOG = logger<AllPullRequestWorktreesRemover>()
    }
}

/** True for a real folder (not a symbolic link) whose `.git` is a file, which is how a linked git work tree looks. */
private fun isLinkedGitWorktree(path: Path): Boolean {
    return !Files.isSymbolicLink(path) && Files.isRegularFile(path.resolve(".git"))
}