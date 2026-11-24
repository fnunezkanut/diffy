package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.intellij.diff.FrameDiffTool
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import javax.swing.JComponent

/**
 * Creates the orange "not in the pull request yet" bar of a diff view. The panel reads the shared state, so it renders
 * nothing for a file without unpushed or uncommitted changes and while the pull request is not proven open.
 */
internal object WorktreeUnpushedChangesBarDiffNotification {
    /**
     * Returns the orange bar of [viewer] for the file at [relativePath], or null when the project is not a
     * plugin-managed pull request worktree, the path is unknown, or the viewer is gone. The panel is disposed with the
     * viewer.
     */
    fun create(project: Project, viewer: FrameDiffTool.DiffViewer?, relativePath: String?): JComponent? {
        if (relativePath == null) {
            LOG.debug("Orange bar of diff skipped: the diff has no file path")
            return null
        }
        val isManaged = PullRequestWorktreeProject.isPluginManagedWorktree(
            projectBasePath = project.basePath,
            workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        )
        if (!isManaged) {
            LOG.debug("Orange bar of diff skipped: the project is not a plugin-managed pull request worktree")
            return null
        }
        if (viewer == null) {
            LOG.debug("Orange bar of diff skipped: the diff has no viewer")
            return null
        }
        val bar = WorktreeUnpushedChangesBarPanel.create(project = project, relativePath = relativePath)
        if (!Disposer.tryRegister(viewer, bar)) {
            LOG.debug("Orange bar of diff skipped: the diff viewer is already disposed")
            bar.dispose()
            return null
        }
        LOG.debug("Attached the orange bar to a diff viewer")
        return bar
    }

    private val LOG = logger<WorktreeUnpushedChangesBarDiffNotification>()
}