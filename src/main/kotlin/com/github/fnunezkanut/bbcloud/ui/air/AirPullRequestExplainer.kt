package com.github.fnunezkanut.bbcloud.ui.air

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.ui.main.AirExplainPullRequestPrompt
import com.github.fnunezkanut.bbcloud.ui.main.ExplainPullRequestRequest
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/** Builds the "Explain this PR with AI" prompt and puts it into a new Air chat thread, without submitting it. */
internal object AirPullRequestExplainer {
    private const val NOTIFICATION_GROUP_ID = "Diffy"

    private val LOG = logger<AirPullRequestExplainer>()

    /** Call it on the EDT from the button click. */
    fun explain(project: Project?, request: ExplainPullRequestRequest, workspace: String, repositorySlug: String) {
        if (project == null) {
            LOG.info("Explain this PR with AI ignored for pull request #${request.pullRequestId}: no project")
            return
        }
        val text = AirExplainPullRequestPrompt.build(
            request = request,
            repositoryPath = project.basePath,
            workspace = workspace,
            repositorySlug = repositorySlug
        )
        LOG.debug("Sending the explain prompt for pull request #${request.pullRequestId} to a new Air chat")
        AirChatBridge.sendToNewChatInput(project = project, text = text) { outcome ->
            if (outcome == AirSendOutcome.CopiedToClipboard) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(DiffyBundle.message("air.explain.copiedToClipboard"), NotificationType.WARNING)
                    .notify(project)
            }
        }
    }
}