package com.github.fnunezkanut.bbcloud.ui.worktree

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.project.AllPullRequestWorktreesRemover
import com.github.fnunezkanut.bbcloud.project.AllWorktreesRemovalSummary
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeFolder
import com.github.fnunezkanut.bbcloud.project.normalizedFolderKey
import com.github.fnunezkanut.bbcloud.project.orderCurrentProjectLast
import com.github.fnunezkanut.bbcloud.project.summaryNotification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** Runs one item of the bulk removal and reports it as a progress step. */
internal typealias BulkRemovalStep = suspend (
    index: Int,
    folder: PullRequestWorktreeFolder,
    action: suspend () -> AllWorktreesRemovalSummary
) -> AllWorktreesRemovalSummary

/**
 * Removes all PR work trees as a cancellable background task in the status bar. The job runs on the application scope,
 * so closing the window that started it does not stop it. The work tree of the hosting window is removed last, after
 * the progress task, and its window is closed as part of it.
 */
@Service(Service.Level.APP)
internal class DiffyAllWorktreesRemovalService(private val coroutineScope: CoroutineScope) {
    /** True while a bulk removal runs, in any window. */
    fun isRunning(): Boolean {
        return PullRequestWorktreeRemovalRegistry.isBulkRemovalInProgress()
    }

    /**
     * Starts the removal and returns at once. The caller must already hold [PullRequestWorktreeRemovalRegistry.tryStartBulkRemoval];
     * the guard is released when the job ends. [onFinished] runs on the EDT unless the hosting project was disposed.
     */
    fun start(
        project: Project,
        folders: List<PullRequestWorktreeFolder>,
        workTreeDirectory: Path,
        remover: AllPullRequestWorktreesRemover,
        onFinished: () -> Unit
    ) {
        val hostKey = project.basePath?.let { basePath -> normalizedFolderKey(path = basePath) }
        coroutineScope.launch {
            try {
                val summary = runBulkRemoval(
                    folders = folders,
                    workTreeDirectory = workTreeDirectory,
                    remover = remover,
                    isCurrentProject = { path -> hostKey != null && normalizedFolderKey(path = path.toString()) == hostKey },
                    isProjectDisposed = { project.isDisposed },
                    runWithProgress = { size, block -> runWithStatusBarProgress(project = project, size = size, total = folders.size, block = block) }
                )
                report(project = project, summary = summary, onFinished = onFinished)
            } catch (exception: CancellationException) {
                LOG.info("Removing all PR work trees stopped because the IDE is closing or the plugin unloads")
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not remove all PR work trees", exception)
                reportFailure(project = project, onFinished = onFinished)
            } finally {
                PullRequestWorktreeRemovalRegistry.finishBulkRemoval()
            }
        }
    }

    private suspend fun runWithStatusBarProgress(
        project: Project,
        size: Int,
        total: Int,
        block: suspend (BulkRemovalStep) -> AllWorktreesRemovalSummary
    ): AllWorktreesRemovalSummary {
        return withBackgroundProgress(project, DiffyBundle.message("recentProjects.removeAllWorktrees.progress.title"), TaskCancellation.cancellable()) {
            reportSequentialProgress(size = size) { reporter ->
                block { index, folder, action ->
                    val text = DiffyBundle.message(
                        "recentProjects.removeAllWorktrees.progress.item",
                        index + 1,
                        total,
                        folder.worktreePath.fileName.toString()
                    )
                    reporter.itemStep(text) { action() }
                }
            }
        }
    }

    private suspend fun report(project: Project, summary: AllWorktreesRemovalSummary, onFinished: () -> Unit) {
        withContext(NonCancellable + Dispatchers.EDT) {
            val (messageKey, type) = summaryNotification(summary = summary)
            var content = DiffyBundle.message(messageKey, summary.removed, summary.notRemoved)
            if (summary.removedWithoutGit > 0) {
                content += " " + DiffyBundle.message("recentProjects.removeAllWorktrees.withoutGit")
            }
            LOG.info("Removed ${summary.removed} PR work trees; ${summary.notRemoved} were not removed")
            val disposed = project.isDisposed
            notify(project = if (disposed) null else project, content = content, type = type)
            if (!disposed) {
                onFinished()
            }
        }
    }

    private suspend fun reportFailure(project: Project, onFinished: () -> Unit) {
        withContext(NonCancellable + Dispatchers.EDT) {
            val disposed = project.isDisposed
            notify(
                project = if (disposed) null else project,
                content = DiffyBundle.message("recentProjects.removeAllWorktrees.failed"),
                type = NotificationType.ERROR
            )
            if (!disposed) {
                onFinished()
            }
        }
    }

    private fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                title = "",
                content = content,
                type = type
            ).notify(project)
    }

    companion object {
        private val LOG = logger<DiffyAllWorktreesRemovalService>()
        private const val NOTIFICATION_GROUP_ID = "Diffy"

        fun getInstance(): DiffyAllWorktreesRemovalService {
            return ApplicationManager.getApplication().service<DiffyAllWorktreesRemovalService>()
        }
    }
}

/**
 * The decision logic of the bulk removal, free of platform calls so it can be unit tested.
 *
 * The folder of the hosting window is split out and removed last, outside the progress task. When the progress task is
 * cancelled, [isProjectDisposed] tells why: a disposed hosting project means the window was closed by hand, so the
 * remaining folders are removed without progress; otherwise the user cancelled, so the remaining folders (and the
 * hosting folder) are counted as not processed and the hosting window stays open. A cancellation of the calling
 * coroutine itself (IDE exit, plugin unload) is rethrown.
 *
 * [runWithProgress] receives the number of items and a block to run with a [BulkRemovalStep].
 */
internal suspend fun runBulkRemoval(
    folders: List<PullRequestWorktreeFolder>,
    workTreeDirectory: Path,
    remover: AllPullRequestWorktreesRemover,
    isCurrentProject: (Path) -> Boolean,
    isProjectDisposed: () -> Boolean,
    runWithProgress: suspend (size: Int, block: suspend (BulkRemovalStep) -> AllWorktreesRemovalSummary) -> AllWorktreesRemovalSummary
): AllWorktreesRemovalSummary {
    val (others, current) = orderCurrentProjectLast(folders = folders, isCurrentProject = isCurrentProject)
    var done = AllWorktreesRemovalSummary()
    var completed = 0
    if (others.isNotEmpty()) {
        try {
            runWithProgress(others.size) { onStep ->
                remover.removeAll(
                    folders = others,
                    workTreeDirectory = workTreeDirectory,
                    onStep = { index, folder, action ->
                        val itemSummary = onStep(index, folder, action)
                        done += itemSummary
                        completed = index + 1
                        itemSummary
                    }
                )
            }
        } catch (exception: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                LOG.info("Removing all PR work trees was cancelled after $completed of ${others.size} items")
                throw exception
            }
            if (!isProjectDisposed()) {
                LOG.info("User cancelled removing all PR work trees after $completed of ${others.size} items")
                val skippedCurrent = if (current != null) 1 else 0
                return done + AllWorktreesRemovalSummary(notProcessed = others.size - completed + skippedCurrent)
            }
            LOG.info("The hosting window closed; removing the remaining PR work trees without progress")
            done += remover.removeAll(folders = others.drop(completed), workTreeDirectory = workTreeDirectory)
        }
    }
    if (current != null) {
        done += remover.removeOne(folder = current, workTreeDirectory = workTreeDirectory)
    }
    return done
}

private val LOG = logger<DiffyAllWorktreesRemovalService>()