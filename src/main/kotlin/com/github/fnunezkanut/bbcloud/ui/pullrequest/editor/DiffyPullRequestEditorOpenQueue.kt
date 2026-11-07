package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentLinkedQueue

/** Hands off pull request editor opens until the target project is ready. */
@Service(Service.Level.PROJECT)
class DiffyPullRequestEditorOpenQueue {
    private val pendingRequests = ConcurrentLinkedQueue<DiffyPendingEditorOpen>()

    fun enqueue(request: DiffyPendingEditorOpen) {
        pendingRequests.add(request)
        LOG.debug("Queued pull request editor open for #${request.summary.id}")
    }

    fun drainAndOpen(project: Project) {
        val opener = project.service<DiffyPullRequestEditorOpener>()
        while (true) {
            val request = pendingRequests.poll() ?: break
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                try {
                    val opened = opener.open(
                        summary = request.summary,
                        branchName = request.branchName,
                        repositoryWebUrl = request.repositoryWebUrl,
                        repositorySlug = request.repositorySlug
                    )
                    if (!opened) {
                        LOG.warn("Could not open the pull request editor for #${request.summary.id}")
                    }
                } catch (exception: Exception) {
                    LOG.warn("Could not open the pull request editor for #${request.summary.id}", exception)
                }
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyPullRequestEditorOpenQueue>()

        fun getInstance(project: Project): DiffyPullRequestEditorOpenQueue {
            return project.service()
        }
    }
}