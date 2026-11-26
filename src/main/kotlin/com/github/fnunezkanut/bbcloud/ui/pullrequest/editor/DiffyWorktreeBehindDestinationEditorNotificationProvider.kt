package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import com.github.fnunezkanut.bbcloud.config.DiffyConfigurationService
import com.github.fnunezkanut.bbcloud.project.PullRequestWorktreeProject
import com.github.fnunezkanut.bbcloud.review.WorktreeBehindDestinationBarViewModel
import com.github.fnunezkanut.bbcloud.review.WorktreeTodoBannerViewModel
import com.github.fnunezkanut.bbcloud.ui.pullrequest.create.DiffyNewPullRequestVirtualFile
import com.github.fnunezkanut.bbcloud.ui.pullrequest.help.DiffyWorktreesHelpVirtualFile
import com.intellij.diff.editor.DiffViewerVirtualFile
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import java.util.WeakHashMap
import java.util.function.Function
import javax.swing.JComponent

/**
 * Shows the yellow "pull request is behind its destination branch" bar directly under the red bar of every file opened
 * in a plugin-managed pull request work tree, including the `.pr` page, while Bitbucket reports that the pull request
 * lacks commits of its destination branch and the pull request is proven open.
 *
 * The bar is added and removed: this provider returns nothing while the bar is hidden, and
 * [WorktreeBehindDestinationBarViewModel] asks the platform to collect the notifications of this provider again
 * whenever the visibility changes.
 */
class DiffyWorktreeBehindDestinationEditorNotificationProvider :
    EditorNotificationProvider,
    DumbAware {
    private val bars = WeakHashMap<FileEditor, WorktreeBehindDestinationBarPanel>()

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file is DiffyNewPullRequestVirtualFile) {
            LOG.debug("Yellow bar skipped: the file is the new pull request form")
            return null
        }
        if (file is DiffyWorktreesHelpVirtualFile) {
            LOG.debug("Yellow bar skipped: the file is the work trees help page")
            return null
        }
        // The diff views get the yellow bar inside the diff through PullRequestDiffBarsDiffExtension.
        if (file is DiffViewerVirtualFile) {
            LOG.debug("Yellow bar skipped: the diff view gets the yellow bar from the diff extension")
            return null
        }
        val projectBasePath = project.basePath
        if (projectBasePath == null) {
            LOG.debug("Yellow bar skipped: the project has no base path")
            return null
        }
        val workTreeDirectory = DiffyConfigurationService.getInstance().workTreeDirectoryOrNull()
        if (!PullRequestWorktreeProject.isPluginManagedWorktree(
                projectBasePath = projectBasePath,
                workTreeDirectory = workTreeDirectory
            )
        ) {
            LOG.debug("Yellow bar skipped: the project is not a plugin-managed pull request worktree")
            return null
        }
        val barViewModel = WorktreeBehindDestinationBarViewModel.getInstance(project)
        barViewModel.ensureStarted()
        WorktreeTodoBannerViewModel.getInstance(project).ensureInitialized()
        if (!barViewModel.isVisible()) {
            LOG.debug("Yellow bar skipped: the pull request is not behind its destination branch, or is not proven open")
            return null
        }
        return Function<FileEditor, JComponent?> { fileEditor ->
            barFor(project = project, fileEditor = fileEditor)
        }
    }

    private fun barFor(project: Project, fileEditor: FileEditor): JComponent {
        val existing = bars[fileEditor]
        if (existing != null) {
            return existing
        }
        val bar = WorktreeBehindDestinationBarPanel.create(project = project, toggleVisibility = false)
        bars[fileEditor] = bar
        if (!Disposer.tryRegister(fileEditor, bar)) {
            bar.dispose()
        }
        return bar
    }

    companion object {
        private val LOG = logger<DiffyWorktreeBehindDestinationEditorNotificationProvider>()
    }
}