package com.github.fnunezkanut.bbcloud.ui.air

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.PrRemoteResolver
import com.github.fnunezkanut.bbcloud.git.ProjectGitRepositoryLookup
import com.github.fnunezkanut.bbcloud.git.RebaseBackupRefs
import com.github.fnunezkanut.bbcloud.git.RebaseBackupResult
import com.github.fnunezkanut.bbcloud.git.RebaseBackupWriter
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.github.fnunezkanut.bbcloud.review.BehindDestinationBarUiState
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.repo.GitRemote
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the "Rebase onto latest with AI" prompt and puts it into a new Air chat thread, without submitting it. It
 * works only in a plugin-managed pull request work tree, so a stale bar can never send the prompt from another project.
 *
 * Before the prompt is built, the plugin saves `HEAD` (and the uncommitted tracked changes) under backup refs through
 * [RebaseBackupWriter], and a notification offers the restore command. If the backup fails, the prompt is not sent.
 * A click while a backup of the same project is still running is ignored.
 */
internal object AirPullRequestRebaser {
    private const val NOTIFICATION_GROUP_ID = "Diffy"
    private const val SHORT_SHA_LENGTH = 10

    private val LOG = logger<AirPullRequestRebaser>()
    private val backupWriter = RebaseBackupWriter()
    private val backupsInFlight: MutableSet<Project> = ConcurrentHashMap.newKeySet()

    private data class RebaseTarget(val pullRequestId: Long, val sourceBranch: String, val destinationBranch: String)

    /** Call it on the EDT from the link click. */
    fun rebase(project: Project, state: BehindDestinationBarUiState) {
        rebase(
            project = project,
            target = RebaseTarget(
                pullRequestId = state.pullRequestId,
                sourceBranch = state.sourceBranch,
                destinationBranch = state.destinationBranch
            )
        )
    }

    /** Rebases the work tree's pull request from the Checks card; call it on the EDT from the link click. */
    fun rebase(project: Project, pullRequestId: Long, sourceBranch: String, destinationBranch: String) {
        rebase(
            project = project,
            target = RebaseTarget(pullRequestId = pullRequestId, sourceBranch = sourceBranch, destinationBranch = destinationBranch)
        )
    }

    /** Whether [project] is a plugin-managed pull request work tree, the only place the rebase prompt can run. */
    fun isAvailableIn(project: Project): Boolean {
        return PullRequestWorktreeProject.isPluginManagedWorktree(
            projectBasePath = project.basePath,
            workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        )
    }

    private fun rebase(project: Project, target: RebaseTarget) {
        if (!isAvailableIn(project)) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${target.pullRequestId}: not a pull request work tree")
            return
        }
        if (!backupsInFlight.add(project)) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${target.pullRequestId}: a backup is already running")
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                backUpAndSend(project = project, target = target)
            } finally {
                backupsInFlight.remove(project)
            }
        }
    }

    /** Runs on a background thread. */
    private fun backUpAndSend(project: Project, target: RebaseTarget) {
        if (project.isDisposed) {
            return
        }
        val remoteName = resolveRemoteName(project = project)
        LOG.debug("Rebase prompt of pull request #${target.pullRequestId} uses remote $remoteName")
        val basePath = project.basePath
        if (basePath == null) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${target.pullRequestId}: the project has no base path")
            onEdt(project = project) { notifyBackupFailed(project = project) }
            return
        }
        val backup = backupWriter.create(project = project, workTree = Path.of(basePath), pullRequestId = target.pullRequestId)
        onEdt(project = project) {
            when (backup) {
                is RebaseBackupResult.Created -> {
                    send(project = project, target = target, remoteName = remoteName, backup = backup)
                }
                is RebaseBackupResult.Failed -> {
                    LOG.warn("Backup before rebase of pull request #${target.pullRequestId} failed; the rebase prompt was not sent: ${backup.reason}")
                    notifyBackupFailed(project = project)
                }
            }
        }
    }

    private fun onEdt(project: Project, runnable: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(runnable, ModalityState.nonModal(), project.disposed)
    }

    private fun resolveRemoteName(project: Project): String {
        val repository = ProjectGitRepositoryLookup.findRepository(project)
        if (repository == null) {
            return GitRemote.ORIGIN
        }
        return PrRemoteResolver.remoteName(repository = repository)
    }

    private fun send(project: Project, target: RebaseTarget, remoteName: String, backup: RebaseBackupResult.Created) {
        val text = AirRebasePullRequestPrompt.build(
            request = RebasePullRequestRequest(
                pullRequestId = target.pullRequestId,
                sourceBranch = target.sourceBranch,
                destinationBranch = target.destinationBranch,
                remoteName = remoteName,
                repositoryPath = project.basePath,
                headSha = backup.headSha,
                backupRef = backup.headRef,
                wipBackupRef = backup.wipRef
            )
        )
        LOG.debug("Sending the rebase prompt for pull request #${target.pullRequestId} to a new Air chat")
        AirChatBridge.sendToNewChatInput(project = project, text = text, label = AirChatBridge.REBASE_LABEL) { outcome ->
            val copiedToClipboard = outcome == AirSendOutcome.CopiedToClipboard
            if (copiedToClipboard) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(DiffyBundle.message("air.rebase.copiedToClipboard"), NotificationType.WARNING)
                    .notify(project)
            }
            notifyBackupCreated(project = project, target = target, backup = backup, offerCopyAction = !copiedToClipboard)
        }
    }

    /**
     * Tells the user where the backup is. The Copy restore command action is left out when the prompt was copied to the
     * clipboard, so it cannot overwrite the prompt before the user pastes it.
     */
    private fun notifyBackupCreated(project: Project, target: RebaseTarget, backup: RebaseBackupResult.Created, offerCopyAction: Boolean) {
        val shortSha = backup.headSha.take(SHORT_SHA_LENGTH)
        val wipRef = backup.wipRef
        val content = if (wipRef == null) {
            DiffyBundle.message("air.rebase.backup.created", shortSha, backup.headRef)
        } else {
            DiffyBundle.message("air.rebase.backup.createdWithWip", shortSha, backup.headRef, wipRef)
        }
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(content, NotificationType.INFORMATION)
        if (offerCopyAction) {
            notification.addAction(
                NotificationAction.createSimple(DiffyBundle.message("air.rebase.backup.copyRestoreCommand")) {
                    LOG.info("Copy restore command clicked for pull request #${target.pullRequestId}")
                    val command = RebaseBackupRefs.restoreCommand(backupRef = backup.headRef, wipRef = wipRef)
                    CopyPasteManager.getInstance().setContents(StringSelection(command))
                }
            )
        }
        notification.notify(project)
    }

    private fun notifyBackupFailed(project: Project) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(DiffyBundle.message("air.rebase.backup.failed"), NotificationType.ERROR)
            .notify(project)
    }
}