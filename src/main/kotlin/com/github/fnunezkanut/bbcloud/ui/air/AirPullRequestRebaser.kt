package com.github.fnunezkanut.bbcloud.ui.air

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.git.PrRemoteResolver
import com.github.fnunezkanut.bbcloud.git.ProjectGitRepositoryLookup
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.github.fnunezkanut.bbcloud.review.BehindDestinationBarUiState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.repo.GitRemote

/**
 * Builds the "Rebase onto latest with AI" prompt and puts it into a new Air chat thread, without submitting it. It
 * works only in a plugin-managed pull request work tree, so a stale bar can never send the prompt from another project.
 */
internal object AirPullRequestRebaser {
    private const val NOTIFICATION_GROUP_ID = "Diffy"

    private val LOG = logger<AirPullRequestRebaser>()

    /** Call it on the EDT from the link click. */
    fun rebase(project: Project, state: BehindDestinationBarUiState) {
        val isManaged = PullRequestWorktreeProject.isPluginManagedWorktree(
            projectBasePath = project.basePath,
            workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        )
        if (!isManaged) {
            LOG.info("Rebase onto latest with AI ignored for pull request #${state.pullRequestId}: not a pull request work tree")
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val remoteName = resolveRemoteName(project = project)
            LOG.debug("Rebase prompt of pull request #${state.pullRequestId} uses remote $remoteName")
            ApplicationManager.getApplication().invokeLater(
                { send(project = project, state = state, remoteName = remoteName) },
                ModalityState.nonModal(),
                project.disposed
            )
        }
    }

    private fun resolveRemoteName(project: Project): String {
        val repository = ProjectGitRepositoryLookup.findRepository(project)
        if (repository == null) {
            return GitRemote.ORIGIN
        }
        return PrRemoteResolver.remoteName(repository = repository)
    }

    private fun send(project: Project, state: BehindDestinationBarUiState, remoteName: String) {
        val text = AirRebasePullRequestPrompt.build(
            request = RebasePullRequestRequest(
                pullRequestId = state.pullRequestId,
                sourceBranch = state.sourceBranch,
                destinationBranch = state.destinationBranch,
                remoteName = remoteName,
                repositoryPath = project.basePath
            )
        )
        LOG.debug("Sending the rebase prompt for pull request #${state.pullRequestId} to a new Air chat")
        AirChatBridge.sendToNewChatInput(project = project, text = text, label = AirChatBridge.REBASE_LABEL) { outcome ->
            if (outcome == AirSendOutcome.CopiedToClipboard) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(DiffyBundle.message("air.rebase.copiedToClipboard"), NotificationType.WARNING)
                    .notify(project)
            }
        }
    }
}