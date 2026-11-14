package com.github.fnunezkanut.bbcloud.ui.worktree

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.DiffyWorktreeService
import com.github.fnunezkanut.bbcloud.git.OpenProjectLocatorImpl
import com.github.fnunezkanut.bbcloud.project.AllPullRequestWorktreesRemover
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeFolder
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeListing
import com.github.fnunezkanut.bbcloud.project.listPullRequestWorktreeFolders
import com.github.fnunezkanut.bbcloud.ui.pullrequest.decline.IntelliJProjectCloser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * The UI side of "Remove all PR Work Trees": checks the guards, lists the PR work tree folders, asks once for
 * confirmation, and hands over to [DiffyAllWorktreesRemovalService], which runs the removal as a background task.
 *
 * [coroutineScope] is the tool window scope, used only for the listing and the dialog. [sourceRootResolverFactory] is
 * called when the removal starts and must capture everything it needs, because the panel may be disposed while the
 * job runs.
 */
internal class AllPullRequestWorktreesRemovalLauncher(
    private val project: Project,
    private val configurationService: DiffyConfigurationService,
    private val coroutineScope: CoroutineScope,
    private val worktreeOpener: PullRequestWorktreeOpener,
    private val isPruneInProgress: () -> Boolean,
    private val sourceRootResolverFactory: () -> (worktreePath: Path, repoSlug: String) -> Path?
) {
    /**
     * Starts the flow; call it on the EDT. [onFinished] is called on the EDT on every exit path (guards, cancelled
     * dialog, completion), except when the hosting window was closed by the removal.
     */
    fun start(onFinished: () -> Unit) {
        val workTreeDirectoryString = configurationService.workTreeDirectoryOrNull()
        if (workTreeDirectoryString == null) {
            LOG.info("Cannot remove all PR work trees: no projects directory")
            notify(messageKey = "recentProjects.removeAllWorktrees.noWorkTreeDirectory", type = NotificationType.WARNING)
            onFinished()
            return
        }
        if (isPruneInProgress()) {
            LOG.info("Cannot remove all PR work trees: pruning is running")
            notify(messageKey = "workspace.watchedProjects.worktree.remove.pruneBusy", type = NotificationType.WARNING)
            onFinished()
            return
        }
        if (PullRequestWorktreeRemovalRegistry.isBulkRemovalInProgress()) {
            LOG.info("Cannot remove all PR work trees: another removal is running")
            notify(messageKey = "recentProjects.removeAllWorktrees.busy", type = NotificationType.WARNING)
            onFinished()
            return
        }
        val workTreeDirectory = Path.of(workTreeDirectoryString)
        coroutineScope.launch {
            try {
                listConfirmAndHandOver(workTreeDirectory = workTreeDirectory, onFinished = onFinished)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LOG.warn("Could not start removing all PR work trees", exception)
                withContext(Dispatchers.EDT) {
                    notify(messageKey = "recentProjects.removeAllWorktrees.failed", type = NotificationType.ERROR)
                    onFinished()
                }
            }
        }
    }

    private suspend fun listConfirmAndHandOver(workTreeDirectory: Path, onFinished: () -> Unit) {
        val (folders, ignoredCount, resolveSourceRoot) = withContext(Dispatchers.IO) {
            val children = PullRequestWorktreeListing.listChildDirectories(workTreeDirectory)
            val listed = listPullRequestWorktreeFolders(workTreeDirectory = workTreeDirectory, listChildren = { children })
            Triple(listed, children.size - listed.size, sourceRootResolverFactory())
        }
        LOG.debug("Found ${folders.size} PR work tree folders; ignored $ignoredCount other folders")
        if (folders.isEmpty()) {
            withContext(Dispatchers.EDT) {
                notify(messageKey = "recentProjects.removeAllWorktrees.none", type = NotificationType.INFORMATION)
                onFinished()
            }
            return
        }
        val confirmed = withContext(Dispatchers.EDT) {
            askToRemove(count = folders.size)
        }
        LOG.info("User ${if (confirmed) "confirmed" else "cancelled"} removing all PR work trees")
        if (!confirmed) {
            withContext(Dispatchers.EDT) { onFinished() }
            return
        }
        withContext(Dispatchers.EDT) {
            handOver(
                folders = folders,
                workTreeDirectory = workTreeDirectory,
                resolveSourceRoot = resolveSourceRoot,
                onFinished = onFinished
            )
        }
    }

    private fun handOver(
        folders: List<PullRequestWorktreeFolder>,
        workTreeDirectory: Path,
        resolveSourceRoot: (Path, String) -> Path?,
        onFinished: () -> Unit
    ) {
        if (!PullRequestWorktreeRemovalRegistry.tryStartBulkRemoval()) {
            LOG.info("Cannot remove all PR work trees: another window started the removal in the meantime")
            notify(messageKey = "recentProjects.removeAllWorktrees.busy", type = NotificationType.WARNING)
            onFinished()
            return
        }
        try {
            val worktreeService = DiffyWorktreeService.getInstance()
            val remover = AllPullRequestWorktreesRemover(
                openProjectLocator = OpenProjectLocatorImpl(),
                projectCloser = IntelliJProjectCloser(),
                removeWorktree = worktreeService::removeWorktree,
                deleteOrphan = worktreeService::deleteOrphanWorktreeFolder,
                resolveSourceRoot = resolveSourceRoot,
                isBeingOpened = { path -> path in worktreeOpener.worktreesBeingOpened() },
                markRemoving = PullRequestWorktreeRemovalRegistry::tryMarkRemoving,
                unmarkRemoving = PullRequestWorktreeRemovalRegistry::unmarkRemoving
            )
            DiffyAllWorktreesRemovalService.getInstance().start(
                project = project,
                folders = folders,
                workTreeDirectory = workTreeDirectory,
                remover = remover,
                onFinished = onFinished
            )
        } catch (exception: Exception) {
            PullRequestWorktreeRemovalRegistry.finishBulkRemoval()
            throw exception
        }
    }

    private fun askToRemove(count: Int): Boolean {
        return Messages.showYesNoDialog(
            project,
            DiffyBundle.message("recentProjects.removeAllWorktrees.confirm.message", count),
            DiffyBundle.message("recentProjects.removeAllWorktrees.confirm.title"),
            DiffyBundle.message("recentProjects.removeAllWorktrees.confirm.yes"),
            DiffyBundle.message("recentProjects.removeAllWorktrees.confirm.no"),
            Messages.getWarningIcon()
        ) == Messages.YES
    }

    private fun notify(messageKey: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(
                title = "",
                content = DiffyBundle.message(messageKey),
                type = type
            ).notify(project)
    }

    private companion object {
        val LOG = logger<AllPullRequestWorktreesRemovalLauncher>()
        const val NOTIFICATION_GROUP_ID = "Diffy"
    }
}