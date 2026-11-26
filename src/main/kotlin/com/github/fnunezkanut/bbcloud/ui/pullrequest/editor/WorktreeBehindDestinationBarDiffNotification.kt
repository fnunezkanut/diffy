package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.intellij.diff.FrameDiffTool
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import javax.swing.JComponent

/**
 * Creates the yellow "pull request is behind its destination branch" bar of a diff view.
 *
 * The bar is created for every diff viewer of a plugin-managed pull request worktree, because it describes the pull
 * request and not the file of the diff. A diff cannot collect its notifications again, so the panel hides itself while
 * there is nothing to show.
 */
internal object WorktreeBehindDestinationBarDiffNotification {
    /**
     * Returns the yellow bar of [viewer], or null when the project is not a plugin-managed pull request worktree or the
     * viewer is gone. The panel is disposed with the viewer.
     */
    fun create(project: Project, viewer: FrameDiffTool.DiffViewer?): JComponent? {
        val projectBasePath = project.basePath
        val workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        if (!PullRequestWorktreeProject.isPluginManagedWorktree(
                projectBasePath = projectBasePath,
                workTreeDirectory = workTreeDirectory
            )
        ) {
            LOG.debug("Yellow bar of diff skipped: the project is not a plugin-managed pull request worktree")
            return null
        }
        if (viewer == null) {
            LOG.debug("Yellow bar of diff skipped: the diff has no viewer")
            return null
        }
        val bar = WorktreeBehindDestinationBarPanel.create(project = project, toggleVisibility = true)
        if (!Disposer.tryRegister(viewer, bar)) {
            LOG.debug("Yellow bar of diff skipped: the diff viewer is already disposed")
            bar.dispose()
            return null
        }
        LOG.debug("Attached the yellow bar to a diff viewer")
        return bar
    }

    private val LOG = logger<WorktreeBehindDestinationBarDiffNotification>()
}