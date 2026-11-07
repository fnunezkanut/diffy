package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.ui.DiffyToolWindowPanel
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/** Selects the Diffy - Bitbucket PR Review Current Project listing from an editor-owned project link. */
class DiffyPullRequestToolWindowNavigator(private val project: Project) {
    fun showCurrentProjectPullRequests() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (toolWindow == null) {
            LOG.info("Cannot navigate to Current Project: Diffy - Bitbucket PR Review tool window is unavailable")
            return
        }
        LOG.info("User navigated from a pull request editor to Current Project")
        val panel = project.service<DiffyToolWindowPanel>()
        toolWindow.show {
            if (!panel.showCurrentProjectPullRequestsListing()) {
                LOG.info("Cannot navigate to Current Project: listing content is unavailable")
            }
        }
    }

    companion object {
        private val LOG = logger<DiffyPullRequestToolWindowNavigator>()
        private const val TOOL_WINDOW_ID = "Diffy - Bitbucket PR Review"
    }
}